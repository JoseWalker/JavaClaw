package ai.javaclaw.chat.ui;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the UI is allowed to forget, and what it is not.
 *
 * <p>This store exists because of a verified defect one layer down: the agent's memory is a sliding
 * window of twenty messages, and that window is written back to the same file it was read from, so
 * the twenty-first exchange deletes the first one from disk. A transcript read out of that store is
 * therefore not a record of the conversation - it is a record of what the model still needs, which
 * is a different thing that happens to look the same for the first ten exchanges.
 *
 * <p>So the tests below pin the separation rather than the file format. A transcript keeps every
 * turn regardless of what the window did; rewinding to a turn drops that turn and everything after
 * it from both places at once, because a model still holding an abandoned answer would carry it
 * into the next reply; and nothing that is not an id this application minted is ever allowed to
 * become a path.
 */
class StoredTranscriptsTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("a transcript keeps every turn, even past the twenty the model's window holds")
    void keepsWhatTheContextWindowDrops() throws IOException {
        FakeMemory memory = new FakeMemory();
        StoredTranscripts transcripts = transcripts(memory, new EmptyDirectory());

        for (int i = 1; i <= 30; i++) {
            transcripts.append("web-abc", ConversationMessage.Role.USER, "Question " + i);
        }
        // What the agent's own memory does to itself: the window keeps the last twenty and the
        // truncation is written back over the file it came from.
        memory.trimTo(20);

        assertThat(transcripts.turns("web-abc")).hasSize(30);
        assertThat(transcripts.turns("web-abc").get(0).text()).isEqualTo("Question 1");
    }

    @Test
    @DisplayName("a conversation the UI has never owned is adopted from whatever the store still has")
    void adoptsAConversationTheStoreAlreadyHad() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new FixedDirectory(
                user("How do virtual threads work?"), assistant("They unmount when blocked.")));

        List<TranscriptTurn> turns = transcripts.turns("web-abc");

        assertThat(turns).extracting(TranscriptTurn::text)
                .containsExactly("How do virtual threads work?", "They unmount when blocked.");
        // Adopted once. The second read must come from the transcript, or every reload would fold
        // the conversation back to the window's truncated view of it.
        assertThat(transcripts.turns("web-abc")).hasSize(2);
    }

    @Test
    @DisplayName("every turn is given an identity, because a position in a list is not one")
    void turnsAreIdentified() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new EmptyDirectory());

        TranscriptTurn first = transcripts.append("web-abc", ConversationMessage.Role.USER, "Same").orElseThrow();
        TranscriptTurn second = transcripts.append("web-abc", ConversationMessage.Role.USER, "Same").orElseThrow();

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(transcripts.turns("web-abc")).extracting(TranscriptTurn::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("rewinding to a turn drops that turn and everything after it")
    void rewindDropsTheTurnAndItsTail() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new EmptyDirectory());
        transcripts.append("web-abc", ConversationMessage.Role.USER, "First question");
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "First answer");
        TranscriptTurn second = transcripts.append("web-abc", ConversationMessage.Role.USER, "Second question")
                .orElseThrow();
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "Second answer");

        List<TranscriptTurn> surviving = transcripts.rewindTo("web-abc", second.id()).orElseThrow();

        assertThat(surviving).extracting(TranscriptTurn::text)
                .containsExactly("First question", "First answer");
        assertThat(transcripts.turns("web-abc")).hasSize(2);
    }

    @Test
    @DisplayName("rewinding takes the model's context back with it, so the abandoned tail cannot return")
    void rewindRewritesTheContextWindow() throws IOException {
        FakeMemory memory = new FakeMemory();
        StoredTranscripts transcripts = transcripts(memory, new EmptyDirectory());
        transcripts.append("web-abc", ConversationMessage.Role.USER, "First question");
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "First answer");
        TranscriptTurn second = transcripts.append("web-abc", ConversationMessage.Role.USER, "Second question")
                .orElseThrow();
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "Second answer");

        transcripts.rewindTo("web-abc", second.id());

        // Without this the next reply is generated against a history that still contains the answer
        // the reader just rejected, and the model dutifully agrees with itself.
        assertThat(memory.findByConversationId("web-abc")).extracting(Message::getText)
                .containsExactly("First question", "First answer");
    }

    @Test
    @DisplayName("rewinding to the first turn leaves no conversation behind for the model to continue")
    void rewindToTheOpeningTurnClearsTheContext() throws IOException {
        FakeMemory memory = new FakeMemory();
        StoredTranscripts transcripts = transcripts(memory, new EmptyDirectory());
        TranscriptTurn first = transcripts.append("web-abc", ConversationMessage.Role.USER, "Only question")
                .orElseThrow();
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "Only answer");

        assertThat(transcripts.rewindTo("web-abc", first.id()).orElseThrow()).isEmpty();
        assertThat(transcripts.turns("web-abc")).isEmpty();
        assertThat(memory.findByConversationId("web-abc")).isEmpty();
    }

    @Test
    @DisplayName("a turn id that is not in this conversation rewinds nothing at all")
    void refusesToRewindToATurnItCannotFind() throws IOException {
        FakeMemory memory = new FakeMemory();
        StoredTranscripts transcripts = transcripts(memory, new EmptyDirectory());
        transcripts.append("web-abc", ConversationMessage.Role.USER, "First question");
        transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "First answer");

        // Refusing outright rather than guessing a position: truncating a transcript at a turn
        // nobody named would delete an exchange the reader never asked to lose.
        assertThat(transcripts.rewindTo("web-abc", "0123456789abcdef")).isEmpty();
        assertThat(transcripts.turns("web-abc")).hasSize(2);
    }

    @Test
    @DisplayName("an id that is not one this application mints never becomes a path")
    void refusesAnIdOfTheWrongShape() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new EmptyDirectory());

        assertThat(transcripts.append("../../etc/passwd", ConversationMessage.Role.USER, "Hi")).isEmpty();
        assertThat(transcripts.turns("../../etc/passwd")).isEmpty();
        assertThat(transcripts.rewindTo("../../etc/passwd", "0123456789abcdef")).isEmpty();

        try (Stream<Path> written = Files.walk(workspace)) {
            assertThat(written.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    @DisplayName("a transcript that cannot be parsed is set aside rather than read as an empty one")
    void quarantinesAnUnreadableTranscript() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new EmptyDirectory());
        transcripts.append("web-abc", ConversationMessage.Role.USER, "Something worth keeping");
        Path file = workspace.resolve("transcripts").resolve("web-abc.json");
        Files.writeString(file, "{ this is not the transcript we wrote");

        // The read has to keep working - the reader is looking at a chat window, not a database -
        // but the bytes are the only copy of something and are not overwritten in place.
        assertThat(transcripts.turns("web-abc")).isEmpty();
        try (Stream<Path> aside = Files.list(file.getParent())) {
            assertThat(aside.map(path -> path.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("web-abc.json.unreadable-"));
        }
    }

    @Test
    @DisplayName("a blank turn is not recorded, so an answer that never arrived leaves no hole")
    void refusesToRecordNothing() throws IOException {
        StoredTranscripts transcripts = transcripts(new FakeMemory(), new EmptyDirectory());

        assertThat(transcripts.append("web-abc", ConversationMessage.Role.ASSISTANT, "   ")).isEmpty();
        assertThat(transcripts.turns("web-abc")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private StoredTranscripts transcripts(ChatMemoryRepository memory, ConversationDirectory directory)
            throws IOException {
        return new StoredTranscripts(new FileSystemResource(workspace), directory, memory, new ObjectMapper());
    }

    private static ConversationMessage user(String text) {
        return new ConversationMessage(ConversationMessage.Role.USER, text);
    }

    private static ConversationMessage assistant(String text) {
        return new ConversationMessage(ConversationMessage.Role.ASSISTANT, text);
    }

    /** A store the UI has never written to. */
    private static final class EmptyDirectory implements ConversationDirectory {

        @Override
        public List<ConversationSummary> conversations() {
            return List.of();
        }

        @Override
        public Optional<ConversationSummary> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<ConversationMessage> transcript(String id) {
            return List.of();
        }
    }

    /** A store that already holds one conversation, whatever it is asked for. */
    private record FixedDirectory(ConversationMessage... turns) implements ConversationDirectory {

        @Override
        public List<ConversationSummary> conversations() {
            return List.of();
        }

        @Override
        public Optional<ConversationSummary> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<ConversationMessage> transcript(String id) {
            return List.of(turns);
        }
    }

    /** Chat memory as a map, with the window's own truncation available to be triggered by hand. */
    private static final class FakeMemory implements ChatMemoryRepository {

        private final Map<String, List<Message>> stored = new LinkedHashMap<>();

        void trimTo(int window) {
            stored.replaceAll((id, messages) -> messages.size() <= window
                    ? messages
                    : List.copyOf(messages.subList(messages.size() - window, messages.size())));
        }

        @Override
        public List<String> findConversationIds() {
            return List.copyOf(stored.keySet());
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
            return stored.getOrDefault(conversationId, List.of());
        }

        @Override
        public void saveAll(String conversationId, List<Message> messages) {
            stored.put(conversationId, new ArrayList<>(messages));
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            stored.remove(conversationId);
        }
    }
}
