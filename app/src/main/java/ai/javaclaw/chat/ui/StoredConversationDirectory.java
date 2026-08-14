package ai.javaclaw.chat.ui;

import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Backs the sidebar with the conversations the agent actually has, and with nothing else.
 *
 * <p>The store is reached through Spring AI's {@link ChatMemoryRepository} rather than through the
 * concrete file-system repository, so this class depends on an interface the application already
 * treats as its chat-memory contract and on nothing internal to another module.
 *
 * <p>What that interface can supply is thin: a list of conversation ids and their messages. So a
 * title is asked for rather than taken: once a conversation has had one exchange,
 * {@link ConversationNamer} asks the model what it is about, and until that answer arrives - or when
 * it never does - the title falls back to {@link ConversationTitles}, which reduces the opening
 * message to its topic. The reduction alone is not enough, and the store is why: six threads really
 * were opened here with the same question, and six rows reduced to the same topic separated by an
 * occurrence counter is a column nobody can navigate. The folder is derived from the channel encoded
 * in the id ({@code web}, {@code telegram-123456}). Both are real, if inferred. Times come from
 * {@link ConversationActivity}, which reports only what this process watched happen.
 *
 * <h2>What this adapter refuses to make up</h2>
 *
 * <p>An earlier version of this class appended seven invented conversations, filed under invented
 * folders and carrying invented tags, so that the column would look inhabited while the store held
 * one chat. That is a UI teaching its reader to trust data that does not exist, and the moment it is
 * on screen there is no way for anyone to tell which half of the list is real. It is gone. Folders,
 * tags and a recorded modification time remain genuinely unavailable, and the honest response to
 * that is a shorter list, not a fuller one.
 *
 * <p>Neither title is stable across a restart, and that is a real defect this adapter cannot fix.
 * Chat memory is a sliding window, so once a conversation runs past it the earliest question is
 * dropped from the store and the derived title moves to whatever is now oldest; and an asked-for
 * name lives in the naming component's memory, which the store cannot be told about. Both want a
 * title recorded once beside the conversation, which is the store's job.
 */
@Component
class StoredConversationDirectory implements ConversationDirectory {

    /**
     * Where a conversation that cannot be summarised goes. Reading the store is the one thing here
     * that can fail per row, and the sidebar's answer is to drop that row rather than fail the
     * column - so this log is the only place the reason survives.
     */
    private static final Logger log = LoggerFactory.getLogger(StoredConversationDirectory.class);

    /**
     * How much of the opening message becomes the row's excerpt. Long enough to recognise a
     * conversation by, short enough that the column stays two lines; the sidebar also clamps
     * visually, but cutting here is what keeps a pasted file out of the summary object entirely.
     */
    private static final int PREVIEW_LENGTH = 160;

    /** The chat store, and the only source of what was actually said in a conversation. */
    private final ChatMemoryRepository repository;

    /** Recency, for ordering the column. Reports only what this process watched happen. */
    private final ConversationActivity activity;

    /** Asked-for names, and the thing that tells this adapter a row's title has changed. */
    private final ConversationNamer namer;

    /**
     * Wires the adapter to the three things it reads. Spring supplies all of them.
     *
     * <p>Three collaborators rather than one because each answers a different question and only one
     * of them is the store: what was said, when it was last used, and what it has been named. An
     * adapter that reached into the store's file layout for the other two would trade a missing
     * feature for a coupling that breaks the day the store changes format - which is the trade the
     * class note above refuses.
     *
     * @param repository the chat store, never null
     * @param activity the in-process record of when each conversation was last used, never null
     * @param namer the component that names conversations and announces when one is named
     */
    StoredConversationDirectory(ChatMemoryRepository repository, ConversationActivity activity,
                                ConversationNamer namer) {
        this.repository = repository;
        this.activity = activity;
        this.namer = namer;
    }

    @Override
    public Registration onChanged(Runnable listener) {
        // The one thing that changes under the UI today is a conversation acquiring its real name.
        return namer.onNamed(listener);
    }

    @Override
    public List<ConversationSummary> conversations() {
        List<ConversationSummary> rows = new ArrayList<>(storedConversations());
        // Recency first, because that is how a conversation is looked for. Rows this process never
        // saw used have no time at all and sort below the ones that do, rather than being given a
        // date that would put them somewhere specific and wrong. Title breaks the remaining ties so
        // the column does not reshuffle itself between two renders of the same data.
        rows.sort(Comparator
                .comparing((ConversationSummary row) -> row.updatedAt().orElse(Instant.EPOCH),
                        Comparator.reverseOrder())
                .thenComparing(ConversationSummary::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    @Override
    public Optional<ConversationSummary> find(String id) {
        if (!ConversationIds.isWellFormed(id)) {
            return Optional.empty();
        }
        return conversations().stream().filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public List<ConversationMessage> transcript(String id) {
        // Checked before the store is touched rather than after: an id of the wrong shape is not a
        // conversation that happens to be missing, it is a value that has no business becoming a
        // file name, and the store is the wrong place to find that out.
        if (!ConversationIds.isWellFormed(id)) {
            log.warn("Refused to open a conversation whose id is not of the expected shape");
            return List.of();
        }
        return messagesOf(id).stream()
                .map(StoredConversationDirectory::toTurn)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Narrows one stored message to a turn the transcript can draw.
     *
     * <p>Anything that is neither the reader nor the assistant - a system prompt, a tool exchange -
     * is dropped rather than guessed at. It was never meant to be read as part of the conversation,
     * and painting it as one of the two speakers would attribute it to somebody.
     *
     * @param message one message as the store holds it
     * @return the turn, or empty for anything that is neither the reader nor the assistant
     */
    private static Optional<ConversationMessage> toTurn(Message message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return switch (message.getMessageType()) {
            case USER -> Optional.of(new ConversationMessage(ConversationMessage.Role.USER, text));
            case ASSISTANT -> Optional.of(new ConversationMessage(ConversationMessage.Role.ASSISTANT, text));
            case SYSTEM, TOOL -> Optional.empty();
        };
    }

    /**
     * Reads the store, tolerating a store that is missing, empty or broken.
     *
     * <p>The sidebar is chrome around the chat; it must not be the reason the page fails to render.
     * A failure here is logged once and degrades to an empty section rather than propagating.
     *
     * @return the rows, in store order; empty when the store is unreadable, which is deliberately
     *     indistinguishable from an empty store as far as the column is concerned
     */
    private List<ConversationSummary> storedConversations() {
        List<String> ids;
        try {
            ids = repository.findConversationIds();
        } catch (RuntimeException e) {
            log.warn("Could not list conversations for the sidebar; showing none", e);
            return List.of();
        }
        List<ConversationSummary> rows = new ArrayList<>();
        for (String id : ids) {
            summarise(id).ifPresent(rows::add);
        }
        return rows;
    }

    /**
     * Turns one stored conversation into a row, or decides it is not one.
     *
     * <p>Every reason a row can be absent is answered here rather than by the caller, because they
     * are all the same answer: a conversation that cannot be described is left out of the column
     * instead of drawn as a placeholder. An id that does not match the minted shape is a foreign
     * file or a bug and is never offered as somewhere to click; an empty conversation has no opening
     * message to title itself with.
     *
     * <p>Has one deliberate side effect: it asks {@link ConversationNamer} to name the conversation,
     * without waiting. The row is drawn now under its derived title and redrawn when a name arrives,
     * so a slow or unreachable model costs the sidebar nothing.
     *
     * @param id a conversation id from the store, possibly malformed
     * @return the row, or empty when this conversation is not something the column can honestly show
     */
    private Optional<ConversationSummary> summarise(String id) {
        if (!ConversationIds.isWellFormed(id)) {
            // Nothing this application writes can produce such an id, so one in the store is either
            // a foreign file or a bug. Either way it is not offered as somewhere to click.
            log.warn("Skipping a stored conversation whose id is not of the expected shape");
            return Optional.empty();
        }
        List<Message> messages = messagesOf(id);
        if (messages.isEmpty()) {
            return Optional.empty();
        }

        String firstQuestion = firstText(messages, MessageType.USER);
        String firstAnswer = firstText(messages, MessageType.ASSISTANT);
        String lastAnything = lastText(messages);
        // Asked for once, on the first render that finds this conversation with a reply in it. The
        // call is not waited on - the row is drawn now, under its derived title, and redrawn when
        // the name arrives - so a slow or unreachable model costs the column nothing.
        namer.requestName(id, firstQuestion, firstAnswer);
        // The title is left at its full length. What a 280px column can show is the column's own
        // decision, and it needs the untruncated title to tell two similar threads apart with.
        String title = namer.nameOf(id).orElseGet(() -> ConversationTitles.topic(firstQuestion));
        String preview = ConversationTitles.shorten(
                lastAnything.isBlank() ? firstQuestion : lastAnything, PREVIEW_LENGTH);

        return Optional.of(new ConversationSummary(
                id,
                title,
                preview,
                channelOf(id),
                // Tags are not recorded anywhere. An empty list is the truthful answer; a guessed
                // one would be indistinguishable on screen from a label the reader chose.
                List.of(),
                activity.lastActivity(id),
                ConversationSummary.Source.STORED));
    }

    /**
     * One read of the store, with a conversation that cannot be read treated as absent.
     *
     * @param id a well-formed conversation id
     * @return the messages, or an empty list when the conversation cannot be read - never null, so
     *     the caller needs no guard
     */
    private List<Message> messagesOf(String id) {
        try {
            List<Message> messages = repository.findByConversationId(id);
            return messages == null ? List.of() : messages;
        } catch (RuntimeException e) {
            log.warn("Could not read conversation {}; treating it as empty", id, e);
            return List.of();
        }
    }

    /**
     * The first message of a kind that actually said something.
     *
     * <p>Blank texts are skipped rather than returned, because a stored turn with no text is not the
     * opening question and titling a row from it would produce an empty title where a usable one was
     * two messages further down.
     *
     * @param messages the conversation as stored, in order
     * @param type the kind of turn wanted
     * @return the text, or the empty string when the conversation has no such turn - never null, so
     *     callers can pass it straight to the titling code without a guard
     */
    private static String firstText(List<Message> messages, MessageType type) {
        return messages.stream()
                .filter(message -> message.getMessageType() == type)
                .map(Message::getText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
    }

    /**
     * The most recent thing said in the conversation, which is what the row's excerpt shows.
     *
     * <p>System turns are excluded on purpose. A system prompt is instructions the application gave
     * the model, not something anyone in the conversation said, and it is often the longest text in
     * the store - so a column that included it would show the same block of scaffolding as the
     * excerpt of every row.
     *
     * <p>A forward scan rather than a reverse one because the store hands back a list whose reverse
     * iteration is not guaranteed to be cheap, and the lists are bounded by the memory window.
     *
     * @param messages the conversation as stored, in order
     * @return the last non-blank, non-system text, or the empty string when there is none
     */
    private static String lastText(List<Message> messages) {
        String text = "";
        for (Message message : messages) {
            String candidate = message.getText();
            if (candidate != null && !candidate.isBlank() && message.getMessageType() != MessageType.SYSTEM) {
                text = candidate;
            }
        }
        return text;
    }

    /**
     * The channel a conversation belongs to, read off its id.
     *
     * <p>Conversation ids are channel names - {@code web}, {@code telegram-123456789} - so the part
     * before the first dash is genuine metadata rather than a guess, and it is the closest thing to
     * a folder the store has today. It is not a folder the reader can create, move or rename, and
     * the sidebar does not offer to: a control for something the store cannot record is a promise.
     *
     * @param id a well-formed conversation id
     * @return the channel name, capitalised; a fallback heading when the id carries no channel,
     *     never null and never blank
     */
    private static String channelOf(String id) {
        int dash = id.indexOf('-');
        String channel = dash > 0 ? id.substring(0, dash) : id;
        if (channel.isBlank()) {
            return "Conversations";
        }
        return channel.substring(0, 1).toUpperCase(Locale.ROOT) + channel.substring(1);
    }
}
