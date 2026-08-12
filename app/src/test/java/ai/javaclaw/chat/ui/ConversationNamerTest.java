package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a conversation is called once there is something to ask about.
 *
 * <p>The property under test is not "a name was produced". It is that a reader can tell the rows
 * apart <em>at a glance</em>: the occurrence counter the fallback prepends keeps two labels unequal
 * and tells nobody anything, so every assertion here strips it before comparing. That is the exact
 * state the column was in - six rows reading "(2) 15 most significant features…" through
 * "(6) …" - and it is what a counter-based duplicate check cannot see.
 *
 * <p>The other half is the boundary. The name arrives from a model, is not under this application's
 * control, and ends up in a row label and a {@code title} attribute. The tests below pin that a
 * response which is not shaped like a name changes nothing at all, rather than being cleaned up into
 * something that looks like one.
 */
class ConversationNamerTest {

    private static final String SAME_PROMPT =
            "List the 15 most significant features added to Java between version 8 and version 21, "
                    + "one per line, each starting with a bold name followed by one sentence.";

    private static final String SOME_ANSWER = "Records, sealed types, pattern matching, virtual threads.";

    /** The width the column decides labels at, and the width the reader reads them at. */
    private static final int COLUMN = 34;

    /** How much of a label is on screen before an ellipsis can be the only thing separating two. */
    private static final int GLANCE = 24;

    /** The occurrence marker the fallback prepends, which is not a name and must not stand in for one. */
    private static final Pattern MARKER = Pattern.compile("^\\(\\d+\\)\\s*");

    @Test
    @DisplayName("six threads opened with the same prompt are still six distinguishable rows")
    void sameOpeningStillProducesDistinctLabels() throws InterruptedException {
        // What the reference produced on this identical corpus: names that differ because they were
        // asked for, not because a counter was bolted onto the front of one derivation.
        List<String> asModelsAnswer = List.of(
                "Java Feature Evolution", "Java Version Features", "Java 8 To 21 Highlights",
                "Language Changes Since Java 8", "Notable JDK Additions", "Java Platform Milestones");
        AtomicInteger call = new AtomicInteger();
        CountDownLatch named = new CountDownLatch(asModelsAnswer.size());

        FakeMemory memory = new FakeMemory();
        for (int index = 1; index <= asModelsAnswer.size(); index++) {
            memory.put("web-" + index, new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        }
        ConversationNamer namer = namer(prompt -> reply(asModelsAnswer.get(call.getAndIncrement())));
        namer.onNamed(named::countDown);
        ConversationDirectory directory = new StoredConversationDirectory(memory, new ConversationActivity(), namer);

        // The first read is what asks; it is also drawn immediately, under the derived title. Only
        // a few names are ever asked for at once - a store of two hundred conversations must not
        // become two hundred model calls on one page load - so the rest are asked for on the next
        // render, which is exactly what a name arriving triggers.
        awaitNaming(directory, named);

        Map<String, String> labels = ConversationTitles.forColumn(directory.conversations(), COLUMN);

        assertThat(labels).hasSize(6);
        assertThat(labels.values().stream().map(ConversationNamerTest::glance).toList())
                .as("what the reader can actually read of each row, counter aside")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a name already given to another thread is sent back to be asked again")
    void asksAgainWhenTheNameIsAlreadyTaken() throws InterruptedException {
        // Measured against the live model, not imagined: five of six threads opened with this exact
        // prompt came back named "Java 8 to 21 Features", because the model is naming each one in
        // isolation and they really are the same question. The namer is the only party that knows
        // what it has already handed out, so it is the only one that can say so.
        List<String> prompts = new java.util.ArrayList<>();
        CountDownLatch named = new CountDownLatch(2);
        FakeMemory memory = new FakeMemory();
        memory.put("web-1", new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        memory.put("web-2", new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        ConversationNamer namer = namer(prompt -> {
            String text = prompt.getContents();
            prompts.add(text);
            return reply(text.contains("already in use") ? "Java Records And Sealed Types" : "Java 8 To 21 Features");
        });
        namer.onNamed(named::countDown);
        ConversationDirectory directory = new StoredConversationDirectory(memory, new ConversationActivity(), namer);

        awaitNaming(directory, named);

        assertThat(directory.conversations()).extracting(ConversationSummary::title)
                .containsExactlyInAnyOrder("Java 8 To 21 Features", "Java Records And Sealed Types");
        // Exactly one retry, and only for the thread that collided. Asking twice for every thread
        // would double the cost of drawing a column to solve a problem most columns do not have.
        assertThat(prompts).hasSize(3);
    }

    @Test
    @DisplayName("a second attempt that collides too is still kept - a real name beats a counter")
    void keepsAColloidingNameRatherThanNone() throws InterruptedException {
        CountDownLatch named = new CountDownLatch(2);
        FakeMemory memory = new FakeMemory();
        memory.put("web-1", new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        memory.put("web-2", new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        ConversationNamer namer = namer(prompt -> reply("Java 8 To 21 Features"));
        namer.onNamed(named::countDown);
        ConversationDirectory directory = new StoredConversationDirectory(memory, new ConversationActivity(), namer);

        awaitNaming(directory, named);

        // Two threads that really are the same question are allowed to end up with the same name;
        // what separates them then is the column's occurrence marker, which is what it is for.
        assertThat(directory.conversations()).extracting(ConversationSummary::title)
                .containsExactly("Java 8 To 21 Features", "Java 8 To 21 Features");
    }

    @Test
    @DisplayName("until the name arrives the derived title stands, so no row is ever nameless")
    void fallsBackToTheDerivedTitle() {
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage("Compare Gradle and Maven in exactly two sentences."),
                new AssistantMessage(SOME_ANSWER));
        // A model that never answers stands in for one that has not answered yet.
        ConversationNamer namer = namer(null);

        ConversationSummary row = new StoredConversationDirectory(memory, new ConversationActivity(), namer)
                .conversations().get(0);

        assertThat(row.title()).isEqualTo("Gradle and Maven");
    }

    @Test
    @DisplayName("a conversation is named once, however many times the column is drawn")
    void asksOnlyOnce() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch named = new CountDownLatch(1);
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage(SAME_PROMPT), new AssistantMessage(SOME_ANSWER));
        ConversationNamer namer = namer(prompt -> {
            calls.incrementAndGet();
            return reply("Java Feature Evolution");
        });
        namer.onNamed(named::countDown);
        ConversationDirectory directory = new StoredConversationDirectory(memory, new ConversationActivity(), namer);

        awaitNaming(directory, named);
        directory.conversations();
        directory.conversations();

        // Renaming on every render would also move a row's label under a reader using it to find
        // the row, which is the reason this is once rather than merely cheap.
        assertThat(calls.get()).isEqualTo(1);
        assertThat(directory.conversations().get(0).title()).isEqualTo("Java Feature Evolution");
    }

    @Test
    @DisplayName("a conversation with no reply yet is not named - there is nothing to name it from")
    void waitsForAnExchange() {
        AtomicInteger calls = new AtomicInteger();
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage(SAME_PROMPT));
        ConversationNamer namer = namer(prompt -> {
            calls.incrementAndGet();
            return reply("Anything At All");
        });

        new StoredConversationDirectory(memory, new ConversationActivity(), namer).conversations();

        assertThat(calls.get()).isZero();
    }

    // ------------------------------------------------------------------ the boundary

    @Test
    @DisplayName("a name is accepted only if the whole of it is shaped like one")
    void acceptsOnlyWhatIsShapedLikeAName() {
        assertThat(ConversationNamer.usableName("Virtual Threads Explained")).contains("Virtual Threads Explained");
        // Models wrap one-line answers, and the wrapper is not part of the name.
        assertThat(ConversationNamer.usableName("\"Virtual Threads\"")).contains("Virtual Threads");
        assertThat(ConversationNamer.usableName("Virtual threads.")).contains("Virtual threads");
        assertThat(ConversationNamer.usableName("  Virtual\n threads  ")).contains("Virtual threads");
    }

    @Test
    @DisplayName("anything that is not a name leaves the row exactly as it was")
    void refusesEverythingElse() {
        // All-or-nothing on purpose: trimming a bad response into a good one means guessing which
        // part of it was the name, and that guess eventually puts half a refusal in the column.
        assertThat(ConversationNamer.usableName(null)).isEmpty();
        assertThat(ConversationNamer.usableName("")).isEmpty();
        assertThat(ConversationNamer.usableName("   ")).isEmpty();
        assertThat(ConversationNamer.usableName("Sure! Here is a title for your conversation "
                + "about the Java platform and its many features")).isEmpty();
        // Markup never reaches an attribute, because it never gets past the shape.
        assertThat(ConversationNamer.usableName("<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(ConversationNamer.usableName("Threads\" onmouseover=\"x")).isEmpty();
        assertThat(ConversationNamer.usableName("a".repeat(400))).isEmpty();
        assertThat(ConversationNamer.usableName("x")).isEmpty();
    }

    @Test
    @DisplayName("a model that throws is a title that did not change, not a page that failed")
    void survivesAModelThatThrows() {
        FakeMemory memory = new FakeMemory();
        memory.put("web", new UserMessage("Compare Gradle and Maven in exactly two sentences."),
                new AssistantMessage(SOME_ANSWER));
        ConversationNamer namer = namer(prompt -> {
            throw new IllegalStateException("the model is not reachable");
        });

        ConversationSummary row = new StoredConversationDirectory(memory, new ConversationActivity(), namer)
                .conversations().get(0);

        assertThat(row.title()).isEqualTo("Gradle and Maven");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What a reader has to go on: the first {@link #GLANCE} characters, with the occurrence marker
     * taken off first.
     *
     * <p>The marker is deliberately stripped. It keeps two labels unequal, which is why the fallback
     * prepends it, but it says nothing about either conversation - so a column separated only by
     * counters passes a duplicate check and still cannot be navigated.
     */
    private static String glance(String label) {
        String named = MARKER.matcher(label).replaceFirst("");
        return named.length() <= GLANCE ? named : named.substring(0, GLANCE);
    }

    /**
     * Drives the directory the way the column does, until every name has landed.
     *
     * <p>Re-reading is not test scaffolding: in the application a name arriving is what makes the
     * sidebar draw itself again, and drawing itself again is what asks for the next batch. This is
     * that loop, with a clock on it.
     */
    private static void awaitNaming(ConversationDirectory directory, CountDownLatch named)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (named.getCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            directory.conversations();
        }
        assertThat(named.await(1, TimeUnit.SECONDS)).as("every conversation was named").isTrue();
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** A namer wired to one model, or to none at all when {@code model} is null. */
    static ConversationNamer namer(ChatModel model) {
        return new ConversationNamer(new SoleBean<>(model));
    }

    /** An in-memory stand-in for the chat store. */
    private static class FakeMemory implements ChatMemoryRepository {

        private final Map<String, List<Message>> conversations = new LinkedHashMap<>();

        void put(String conversationId, Message... messages) {
            conversations.put(conversationId, List.of(messages));
        }

        @Override
        public List<String> findConversationIds() {
            return List.copyOf(conversations.keySet());
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
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
