package ai.javaclaw.chat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Keeps each conversation as a file of its own, beside the agent's memory rather than inside it.
 *
 * <p>One JSON array per conversation under {@code {workspace}/transcripts/}, rewritten whole on
 * every change. A transcript is a few kilobytes and changes once per turn, so an append-only log
 * would buy nothing and cost a compaction pass; rewriting is also what makes a rewind a single
 * write rather than a tombstone the reader would have to be shown.
 *
 * <h2>Adoption</h2>
 *
 * <p>Conversations existed before this store did, and they still arrive from other channels. The
 * first time one is read here and no file of its own exists, whatever the chat store still holds is
 * copied in and becomes the transcript. That is the honest amount: the window may already have
 * eaten the opening exchanges, and nothing can recover those - but from that moment on the
 * conversation stops shrinking.
 *
 * <h2>What is never trusted</h2>
 *
 * <p>Neither the conversation id nor the file's contents. The id is checked against
 * {@link ConversationIds} before it is allowed anywhere near a path, and the resolved path is
 * checked to still be inside this directory afterwards, because an allow-list and a containment
 * check fail in different ways. Every field read back off disk is validated by shape - the role has
 * to be one of the two the transcript can draw, the turn id has to look like one this class minted
 * - and a file that does not parse is moved aside rather than read as an empty conversation, since
 * "no turns" and "unreadable" are the same on screen and opposite on disk.
 */
@Component
class StoredTranscripts implements ConversationTranscripts {

    private static final Logger log = LoggerFactory.getLogger(StoredTranscripts.class);

    /** The shape {@link #newTurnId()} produces, and the only shape read back off disk. */
    private static final Pattern TURN_ID = Pattern.compile("[0-9a-f]{16}");

    /**
     * Ceiling on one conversation. Far above any transcript a person will read and far below the
     * point at which rewriting the file on every turn becomes something the reader would feel; it
     * exists so a runaway loop cannot grow a file until the disk decides the matter.
     */
    private static final int MAX_TURNS = 5_000;

    private static final String SUFFIX = ".json";

    private final Path transcriptsDir;
    private final ConversationDirectory directory;
    private final ChatMemoryRepository memory;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    /**
     * One lock for the whole store rather than one per conversation.
     *
     * <p>Every mutation is a read of a few kilobytes, an edit in memory and one write, and two
     * browser tabs on the same conversation are the case that actually has to be serialised. A
     * striped lock would buy concurrency between conversations that nobody is waiting on.
     */
    private final Object lock = new Object();

    StoredTranscripts(@Value("${agent.workspace:Unknown}") Resource workspaceDir,
                      ConversationDirectory directory,
                      ChatMemoryRepository memory,
                      ObjectMapper json) throws IOException {
        this.transcriptsDir = workspaceDir.getFilePath().resolve("transcripts").normalize();
        this.directory = directory;
        this.memory = memory;
        this.json = json;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public List<TranscriptTurn> turns(String id) {
        if (!ConversationIds.isWellFormed(id)) {
            log.warn("Refused to read a transcript whose conversation id is not of the expected shape");
            return List.of();
        }
        synchronized (lock) {
            return load(id);
        }
    }

    /**
     * The turns of one conversation, adopting it from the chat store the first time it is asked
     * for. Must be called under {@link #lock}.
     */
    private List<TranscriptTurn> load(String id) {
        Path file = fileFor(id);
        if (file == null) {
            return List.of();
        }
        if (Files.exists(file)) {
            List<TranscriptTurn> read = read(file);
            if (read != null) {
                return read;
            }
            quarantine(file);
        }
        return adopt(id);
    }

    /**
     * Copies whatever the chat store still holds into a transcript of this UI's own.
     *
     * <p>Writing on a read is deliberate and is the point of the method: this is the moment the UI
     * takes responsibility for a conversation, and a conversation adopted but not written would be
     * re-adopted after the next context-window truncation, folding it back to the shortened view
     * every single time.
     */
    private List<TranscriptTurn> adopt(String id) {
        List<TranscriptTurn> adopted = directory.transcript(id).stream()
                .map(message -> new TranscriptTurn(newTurnId(), message.role(), message.text()))
                .toList();
        if (adopted.isEmpty()) {
            // A chat started a moment ago. Nothing to keep, and an empty file per unused chat would
            // litter the workspace with conversations that were never had.
            return List.of();
        }
        return write(id, adopted) ? adopted : List.of();
    }

    /**
     * @return the turns held in this file, or null when it could not be read as a transcript
     */
    private List<TranscriptTurn> read(Path file) {
        try {
            JsonNode root = json.readTree(Files.readAllBytes(file));
            if (!root.isArray()) {
                return null;
            }
            List<TranscriptTurn> turns = new ArrayList<>();
            for (JsonNode node : root) {
                TranscriptTurn turn = toTurn(node);
                if (turn == null) {
                    return null;
                }
                turns.add(turn);
            }
            return List.copyOf(turns);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the transcript at {}", file.getFileName(), e);
            return null;
        }
    }

    /**
     * Narrows one entry of the file to a turn, or refuses it.
     *
     * <p>Deliberately all-or-nothing at the caller: a single unrecognised entry fails the whole
     * read, because a transcript silently missing its third turn is a conversation that reads as
     * though something else was said.
     *
     * @return the turn, or null when this entry is not one
     */
    private static TranscriptTurn toTurn(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String id = node.path("id").stringValue("");
        String role = node.path("role").stringValue("");
        JsonNode text = node.path("text");
        if (!TURN_ID.matcher(id).matches() || !text.isString()) {
            return null;
        }
        for (ConversationMessage.Role candidate : ConversationMessage.Role.values()) {
            if (candidate.name().equals(role)) {
                return new TranscriptTurn(id, candidate, text.stringValue(), outcomeOf(node));
            }
        }
        return null;
    }

    /**
     * How a turn ended, according to the file.
     *
     * <p>Absent means spoken, which is what every turn written before this field existed is, and
     * anything unrecognised means spoken too. That direction is deliberate: reading a strange value
     * as a failure would draw a real answer under the failure tint and hide it from the model's
     * history, which loses the reader something. Reading it as spoken shows what is there.
     *
     * @param node one entry of the file, already known to be an object
     * @return the outcome, never null
     */
    private static TranscriptTurn.Outcome outcomeOf(JsonNode node) {
        return TranscriptTurn.Outcome.FAILED.name().equals(node.path("outcome").stringValue(""))
                ? TranscriptTurn.Outcome.FAILED
                : TranscriptTurn.Outcome.SPOKEN;
    }

    // ------------------------------------------------------------------ writing

    @Override
    public Optional<TranscriptTurn> append(String id, ConversationMessage.Role role, String text) {
        if (role == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        return record(id, role, text, TranscriptTurn.Outcome.SPOKEN);
    }

    @Override
    public Optional<TranscriptTurn> appendFailure(String id, FailureNotice notice) {
        if (notice == null) {
            return Optional.empty();
        }
        // The text is the notice's own sentence and can be nothing else, which is what keeps a
        // provider's message off the page it would be read back onto.
        return record(id, ConversationMessage.Role.ASSISTANT, notice.sentence(),
                TranscriptTurn.Outcome.FAILED);
    }

    /**
     * Adds one turn to a conversation's file.
     *
     * <p>The one place a turn is minted, so the ceiling, the id check and the "only when it is
     * genuinely on disk" rule are applied to an answer and to a failure alike rather than in two
     * places that can drift.
     *
     * @param id      conversation id, checked here and not assumed to have been checked
     * @param role    who the turn belongs to
     * @param text    what to record, already known to be non-blank
     * @param outcome whether this is something that was said or something that went wrong
     * @return the recorded turn, or empty when nothing was written
     */
    private Optional<TranscriptTurn> record(String id, ConversationMessage.Role role, String text,
                                            TranscriptTurn.Outcome outcome) {
        if (!ConversationIds.isWellFormed(id)) {
            return Optional.empty();
        }
        synchronized (lock) {
            List<TranscriptTurn> turns = load(id);
            if (turns.size() >= MAX_TURNS) {
                log.warn("Refused to extend conversation {} past {} turns", id, MAX_TURNS);
                return Optional.empty();
            }
            TranscriptTurn turn = new TranscriptTurn(newTurnId(), role, text, outcome);
            List<TranscriptTurn> extended = new ArrayList<>(turns);
            extended.add(turn);
            // An unwritten turn is one the reader could be offered a regenerate button for and then
            // watch do nothing, so the turn is only handed back when it is genuinely on disk.
            return write(id, extended) ? Optional.of(turn) : Optional.empty();
        }
    }

    @Override
    public Optional<List<TranscriptTurn>> rewindTo(String id, String turnId) {
        if (!ConversationIds.isWellFormed(id) || turnId == null || !TURN_ID.matcher(turnId).matches()) {
            return Optional.empty();
        }
        synchronized (lock) {
            List<TranscriptTurn> turns = load(id);
            int at = indexOf(turns, turnId);
            if (at < 0) {
                log.warn("Refused to rewind conversation {}: it holds no turn with that id", id);
                return Optional.empty();
            }
            List<TranscriptTurn> surviving = List.copyOf(turns.subList(0, at));
            if (!write(id, surviving)) {
                return Optional.empty();
            }
            if (!rewriteContext(id, surviving)) {
                // The transcript moved and the model's history did not, which is the one state this
                // must not be left in: the next reply would be generated against turns the reader
                // has just discarded. Put the transcript back and report the rewind as refused.
                write(id, turns);
                return Optional.empty();
            }
            return Optional.of(surviving);
        }
    }

    private static int indexOf(List<TranscriptTurn> turns, String turnId) {
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).id().equals(turnId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Makes the agent's history agree with the transcript.
     *
     * <p>The whole surviving transcript is handed over rather than the last twenty turns of it. How
     * much of it the model is shown is the memory window's decision and not this class's, and
     * passing a pre-trimmed list would be this UI quietly adopting the very truncation it keeps a
     * transcript in order to survive.
     *
     * @return true when the history now matches, false when it could not be rewritten
     */
    private boolean rewriteContext(String id, List<TranscriptTurn> surviving) {
        try {
            // A failed turn is this application's own notice, and telling the model it said that
            // would put an apology it never wrote into its history - where it would be answered,
            // summarised and referred back to. It is a record of the conversation, not part of it.
            List<TranscriptTurn> spoken = surviving.stream().filter(turn -> !turn.failed()).toList();
            if (spoken.isEmpty()) {
                memory.deleteByConversationId(id);
                return true;
            }
            List<Message> messages = spoken.stream().map(StoredTranscripts::toMessage).toList();
            memory.saveAll(id, messages);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not rewind the agent's history for conversation {}", id, e);
            return false;
        }
    }

    private static Message toMessage(TranscriptTurn turn) {
        return turn.role() == ConversationMessage.Role.USER
                ? new UserMessage(turn.text())
                : new AssistantMessage(turn.text());
    }

    /**
     * Replaces one conversation's file, or removes it when nothing is left.
     *
     * <p>Written to a neighbouring temporary file and moved into place, so a process that dies
     * mid-write leaves the previous transcript intact rather than a half-written one - the file is
     * the only copy of everything the context window has already dropped.
     *
     * @return true when the transcript on disk now says exactly this
     */
    private boolean write(String id, List<TranscriptTurn> turns) {
        Path file = fileFor(id);
        if (file == null) {
            return false;
        }
        try {
            if (turns.isEmpty()) {
                Files.deleteIfExists(file);
                return true;
            }
            Files.createDirectories(transcriptsDir);
            ArrayNode array = json.createArrayNode();
            for (TranscriptTurn turn : turns) {
                ObjectNode node = array.addObject();
                node.put("id", turn.id());
                node.put("role", turn.role().name());
                node.put("text", turn.text());
                // Written only for a failure, so a transcript of ordinary turns is byte-identical
                // to the ones written before this field existed and no reader has to be migrated.
                if (turn.failed()) {
                    node.put("outcome", turn.outcome().name());
                }
            }
            Path temporary = Files.createTempFile(transcriptsDir, id, ".writing");
            Files.write(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(array));
            move(temporary, file);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write the transcript for conversation {}", id, e);
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot promise the swap. A replacing move is still one call and
            // still better than truncating the destination and writing into it.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Moves a transcript that could not be parsed out of the way.
     *
     * <p>Not deleted, and not overwritten in place. Whatever is in it is the only record of the
     * exchanges the context window has already dropped, and a file this class cannot read is still
     * a file a person can.
     */
    private void quarantine(Path file) {
        Path aside = file.resolveSibling(file.getFileName() + ".unreadable-"
                + Instant.now().toEpochMilli());
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Set aside an unreadable transcript as {}", aside.getFileName());
        } catch (IOException e) {
            log.warn("Could not set aside the unreadable transcript {}", file.getFileName(), e);
        }
    }

    // ------------------------------------------------------------------ paths and ids

    /**
     * Where one conversation is kept.
     *
     * <p>The id has already been matched against the allow-listed shape by every caller, which is
     * what makes it safe to concatenate; the containment check below is the second lock on the same
     * door, and it is here because the shape and the filesystem are two different authorities on
     * what a name means.
     *
     * @return the file, or null when the id does not resolve inside this store
     */
    private Path fileFor(String id) {
        Path file = transcriptsDir.resolve(id + SUFFIX).normalize();
        if (!file.startsWith(transcriptsDir)) {
            log.warn("Refused a transcript path that resolves outside the transcript directory");
            return null;
        }
        return file;
    }

    /**
     * A fresh turn id: eight random bytes as hex.
     *
     * <p>Random rather than a counter, because a counter would have to be read out of the file
     * before it could be incremented and would then be wrong for every conversation that was
     * rewound. Nothing depends on it being unguessable, but it does have to survive two tabs
     * appending in the same millisecond.
     */
    private String newTurnId() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
