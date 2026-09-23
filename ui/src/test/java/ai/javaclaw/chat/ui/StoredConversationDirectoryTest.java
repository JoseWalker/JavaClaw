package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the sidebar is allowed to say about the conversations that exist.
 *
 * <p>The rule this pins down is that every row describes something real. An earlier version of this
 * adapter appended seven invented conversations, with invented folders and invented tags, so that
 * the column looked full while the store held one chat; that is a UI teaching its reader to trust
 * data that does not exist, and the tests below exist so it cannot come back.
 *
 * <p>The other half is the transcript. A sidebar that lists conversations but cannot open one is
 * navigation that does not navigate, so the adapter has to hand back the messages too - and has to
 * do it without ever letting an id it did not recognise reach the store.
 */
class StoredConversationDirectoryTest {

    @Test
    @DisplayName("every row describes a conversation the store actually holds")
    void listsOnlyStoredConversations() {
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage("How do virtual threads work?"), new AssistantMessage("They unmount."));
        memory.put("telegram-42", new UserMessage("Ping"));

        List<ConversationSummary> rows = directory(memory).conversations();

        assertThat(rows).extracting(ConversationSummary::id).containsExactlyInAnyOrder("web", "telegram-42");
        assertThat(rows).allMatch(row -> row.source() == ConversationSummary.Source.STORED);
    }

    @Test
    @DisplayName("an empty store produces an empty list rather than something to look at")
    void inventsNothingForAnEmptyStore() {
        assertThat(directory(new FakeMemory()).conversations()).isEmpty();
    }

    @Test
    @DisplayName("no row carries a folder or a tag the store never recorded")
    void claimsNoFoldersOrTags() {
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage("Anything"));

        ConversationSummary row = directory(memory).conversations().get(0);

        // The heading is the channel encoded in the id, which is genuine metadata. Tags are not
        // recorded anywhere, so there are none - not a plausible-looking guess.
        assertThat(row.group()).isEqualTo("Web");
        assertThat(row.tags()).isEmpty();
    }

    @Test
    @DisplayName("the title is the topic of the first question, and the preview the last thing said")
    void derivesTitleAndPreview() {
        FakeMemory memory = new FakeMemory();
        memory.put("web",
                new SystemMessage("You are JavaClaw."),
                new UserMessage("Compare Gradle and Maven in exactly two sentences."),
                new AssistantMessage("Gradle builds incrementally."));

        ConversationSummary row = directory(memory).conversations().get(0);

        // Not the question verbatim. "Compare" and "in exactly two sentences" describe the request
        // and the shape of the answer; neither says which conversation this is, and in a 280px
        // column they are what pushes the part that does say so past the ellipsis.
        assertThat(row.title()).isEqualTo("Gradle and Maven");
        assertThat(row.preview()).isEqualTo("Gradle builds incrementally.");
    }

    @Test
    @DisplayName("the conversation used most recently sorts first, and one never used sorts last")
    void ordersByRecordedActivity() {
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage("Oldest"));
        memory.put("web-aaa", new UserMessage("Newest"));
        memory.put("web-bbb", new UserMessage("Never touched"));

        ConversationActivity activity = new ConversationActivity();
        activity.recordAt("web", Instant.now().minus(2, ChronoUnit.HOURS));
        activity.recordAt("web-aaa", Instant.now());

        List<ConversationSummary> rows = new StoredConversationDirectory(memory, activity, unnamed()).conversations();

        assertThat(rows).extracting(ConversationSummary::id).containsExactly("web-aaa", "web", "web-bbb");
        assertThat(rows.get(2).updatedAt()).isEmpty();
    }

    @Test
    @DisplayName("opening a conversation returns its turns, with the system prompt left out of it")
    void readsTheTranscript() {
        FakeMemory memory = new FakeMemory();
        memory.put("web",
                new SystemMessage("You are JavaClaw."),
                new UserMessage("Hello"),
                new AssistantMessage("Hello back."));

        List<ConversationMessage> transcript = directory(memory).transcript("web");

        assertThat(transcript).containsExactly(
                new ConversationMessage(ConversationMessage.Role.USER, "Hello"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "Hello back."));
    }

    @Test
    @DisplayName("an id of the wrong shape never reaches the store")
    void refusesIdsOfTheWrongShape() {
        FakeMemory memory = new FakeMemory();

        assertThat(directory(memory).transcript("../../etc/passwd")).isEmpty();
        assertThat(directory(memory).find("../../etc/passwd")).isEmpty();
        assertThat(memory.reads).isEmpty();
    }

    @Test
    @DisplayName("a store that throws leaves an empty sidebar rather than a failed page")
    void survivesABrokenStore() {
        ChatMemoryRepository broken = new FakeMemory() {
            @Override
            public List<String> findConversationIds() {
                throw new IllegalStateException("workspace is gone");
            }
        };

        assertThat(directory(broken).conversations()).isEmpty();
    }

    @Test
    @DisplayName("a conversation that cannot be read is skipped, not fatal to the ones that can")
    void skipsAnUnreadableConversation() {
        FakeMemory memory = new FakeMemory() {
            @Override
            public List<Message> findByConversationId(String conversationId) {
                if ("web-broken".equals(conversationId)) {
                    throw new IllegalStateException("truncated file");
                }
                return super.findByConversationId(conversationId);
            }
        };
        memory.put("web-broken", new UserMessage("Unreadable"));
        memory.put("web", new UserMessage("Readable"));

        assertThat(directory(memory).conversations())
                .extracting(ConversationSummary::id)
                .containsExactly("web");
    }

    private static ConversationDirectory directory(ChatMemoryRepository memory) {
        return new StoredConversationDirectory(memory, new ConversationActivity(), unnamed());
    }

    /**
     * A namer with no model behind it, so every title in this class is the derived one.
     *
     * <p>These tests are about what the adapter may say about the store, and the derivation is what
     * it says when nothing has been asked. What happens once a name has been asked for belongs in
     * {@link ConversationNamerTest}, where it can be waited for.
     */
    private static ConversationNamer unnamed() {
        return new ConversationNamer(new SoleBean<>(null));
    }

    /** An in-memory stand-in for the chat store, which also records what was asked of it. */
    private static class FakeMemory implements ChatMemoryRepository {

        private final Map<String, List<Message>> conversations = new LinkedHashMap<>();
        private final List<String> reads = new java.util.ArrayList<>();

        void put(String conversationId, Message... messages) {
            conversations.put(conversationId, List.of(messages));
        }

        @Override
        public List<String> findConversationIds() {
            return List.copyOf(conversations.keySet());
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
            reads.add(conversationId);
            return conversations.getOrDefault(conversationId, List.of());
        }

        @Override
        public void saveAll(String conversationId, List<Message> messages) {
            conversations.put(conversationId, List.copyOf(messages));
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            conversations.remove(conversationId);
        }
    }
}
