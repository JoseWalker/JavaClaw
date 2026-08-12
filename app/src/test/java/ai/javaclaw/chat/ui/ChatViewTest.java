package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the transcript does when the reader acts on it.
 *
 * <p>Two things are pinned here. The first is what the navigation column exists for: before it, a
 * click renamed the bar and nothing else, so the reader was shown the title of one conversation
 * above the messages of another - which is worse than a sidebar that does not respond at all. So
 * the transcript must be replaced, by the right turns in the right order, with nothing of the
 * previous conversation left under the new title.
 *
 * <p>The second is what happens when an answer comes back wrong. Answering again and asking
 * differently are both "take the conversation back to this point and continue", and the way both
 * of them fail is by continuing without taking anything back: the question is put a second time
 * into a history that already contains it, and every later turn is generated against a
 * conversation nobody had. The tests below assert the count, because the count is the defect.
 */
class ChatViewTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    @DisplayName("opening a conversation paints its stored turns into the transcript")
    void loadsTheStoredTranscript() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do virtual threads work?"), assistant("They unmount when blocked."));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());

        view.openConversation(summary("web-abc"));

        assertThat(roles(view)).containsExactly("user", "assistant");
        assertThat(transcriptText(view))
                .contains("How do virtual threads work?")
                .contains("They unmount when blocked.");
    }

    @Test
    @DisplayName("switching conversations leaves nothing of the previous one behind")
    void replacesThePreviousTranscript() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("First conversation"));
        transcripts.give("web-def", user("Second conversation"), assistant("Answered."));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        view.openConversation(summary("web-abc"));

        view.openConversation(summary("web-def"));

        assertThat(transcriptText(view)).doesNotContain("First conversation");
        assertThat(roles(view)).containsExactly("user", "assistant");
    }

    @Test
    @DisplayName("an assistant turn read back from the store is markup, not asterisks")
    void rendersStoredAnswersAsMarkup() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("List them"), assistant("- **Gradle** is incremental\n- Maven is not\n"));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());

        view.openConversation(summary("web-abc"));

        assertThat(tags(view)).contains("ul", "li", "strong");
        assertThat(transcriptText(view)).doesNotContain("**");
    }

    @Test
    @DisplayName("a conversation with nothing in it shows the empty state instead of a blank page")
    void showsTheEmptyStateForANewChat() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("Something"));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        view.openConversation(summary("web-abc"));

        view.openConversation(summary("web-new"));

        assertThat(roles(view)).isEmpty();
        assertThat(emptyState(view).isVisible()).isTrue();
    }

    @Test
    @DisplayName("a starter puts its question in the composer rather than sending it")
    void aStarterFillsTheComposer() {
        RecordingAgent agent = new RecordingAgent();
        ChatView view = new ChatView(agent, executor, new FakeTranscripts(), new ConversationActivity());

        List<Element> starters = descendants(view.getElement())
                .filter(element -> element.getClassList().contains("jc-starter"))
                .toList();
        assertThat(starters).isNotEmpty();
        click(starters.get(0));

        // Filling, not sending. The reader has not agreed to the question yet, and a click that
        // silently spends a model call is a click nobody can take back.
        assertThat(agent.asked).isEmpty();
        assertThat(composerValue(view)).isEqualTo(starters.get(0).getTextRecursively());
        assertThat(emptyState(view).isVisible()).isTrue();
    }

    @Test
    @DisplayName("the starters disappear with the empty state, rather than sitting under a transcript")
    void startersBelongToTheEmptyState() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("Something"), assistant("Answered."));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());

        view.openConversation(summary("web-abc"));

        assertThat(emptyState(view).isVisible()).isFalse();
    }

    @Test
    @DisplayName("the view binds to the conversation it was given, so the next question goes there")
    void bindsToTheSelectedConversation() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("Something"));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());

        view.openConversation(summary("web-abc"));

        assertThat(view.conversationId()).isEqualTo("web-abc");
    }

    @Test
    @DisplayName("a conversation id of the wrong shape is refused, and the view stays where it was")
    void refusesAnIdOfTheWrongShape() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("Something"));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        view.openConversation(summary("web-abc"));

        view.openConversation(summary("../../etc/passwd"));

        assertThat(view.conversationId()).isEqualTo("web-abc");
        assertThat(transcriptText(view)).contains("Something");
    }

    // ------------------------------------------------------- while it streams

    /**
     * Disabling a focused field moves focus to the document body, which costs the reader their
     * place for the whole turn: the caret is gone from the control they are sitting at, and the
     * next Tab starts again from the top of the page rather than from the composer. Read-only
     * refuses the same keystrokes and keeps the element focusable and in the tab order, which is
     * the only difference that matters here.
     */
    @Test
    @DisplayName("a streaming turn locks the composer without taking it out of the tab order")
    void keepsTheComposerFocusableWhileStreaming() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");

        click(action(view, "Send message"));

        assertThat(composer(view).isReadOnly()).isTrue();
        assertThat(composer(view).isEnabled()).isTrue();
        assertThat(composer(view).getElement().getProperty("disabled", false)).isFalse();
    }

    @Test
    @DisplayName("stopping a turn hands the composer back")
    void releasesTheComposerWhenTheTurnIsStopped() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        click(action(view, "Stop responding"));

        assertThat(composer(view).isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("Enter is refused while a turn streams rather than sending a second question")
    void refusesASecondQuestionWhileStreaming() {
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        composer(view).setValue("And again");
        click(action(view, "Stop responding"));

        assertThat(agent.asked).containsExactly("How do they work?");
    }

    // ------------------------------------------------------- answering again

    @Test
    @DisplayName("regenerating asks the same question once, not twice")
    void regenerateDoesNotDuplicateTheHistory() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do they work?"), assistant("A poor answer."));
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, transcripts);
        view.openConversation(summary("web-abc"));

        click(action(view, "Answer again"));

        assertThat(agent.asked).containsExactly("How do they work?");
        // One question, no answer yet. The rejected answer and the copy of the question that came
        // with it are both gone; what is here is the question, put once.
        assertThat(transcripts.turns("web-abc")).extracting(TranscriptTurn::text)
                .containsExactly("How do they work?");
    }

    @Test
    @DisplayName("regenerating drops the answer it replaces rather than stacking a second one under it")
    void regenerateReplacesTheAnswerOnScreen() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do they work?"), assistant("A poor answer."));
        ChatView view = attached(new RecordingAgent(), transcripts);
        view.openConversation(summary("web-abc"));

        click(action(view, "Answer again"));

        assertThat(transcriptText(view)).doesNotContain("A poor answer.");
        assertThat(roles(view)).containsExactly("user", "assistant");
    }

    @Test
    @DisplayName("a regenerate the store refuses changes nothing and says so")
    void aRefusedRegenerateLeavesTheConversationAlone() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do they work?"), assistant("A poor answer."));
        transcripts.refuseRewinds();
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, transcripts);
        view.openConversation(summary("web-abc"));

        click(action(view, "Answer again"));

        // Asking anyway would append the question to the conversation the reader was trying to
        // leave - the one outcome worse than the answer they already had.
        assertThat(agent.asked).isEmpty();
        assertThat(transcriptText(view)).contains("A poor answer.");
        assertThat(transcriptText(view)).contains("could not be re-asked");
    }

    // ------------------------------------------------------- asking differently

    @Test
    @DisplayName("editing a question asks the new wording and drops everything that followed the old one")
    void editingRebranchesTheConversation() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc",
                user("How do they work?"), assistant("A poor answer."),
                user("And then?"), assistant("Another poor answer."));
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, transcripts);
        view.openConversation(summary("web-abc"));

        click(action(view, "Edit and ask again"));
        editor(view).setValue("How do they compare to platform threads?");
        click(action(view, "Ask again"));

        assertThat(agent.asked).containsExactly("How do they compare to platform threads?");
        assertThat(transcripts.turns("web-abc")).extracting(TranscriptTurn::text)
                .containsExactly("How do they compare to platform threads?");
        assertThat(transcriptText(view))
                .doesNotContain("A poor answer.")
                .doesNotContain("And then?")
                .doesNotContain("Another poor answer.");
    }

    @Test
    @DisplayName("editing the later of two questions leaves the exchange before it standing")
    void editingKeepsWhatCameBeforeIt() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc",
                user("How do they work?"), assistant("A good answer."),
                user("And then?"), assistant("A poor answer."));
        ChatView view = attached(new RecordingAgent(), transcripts);
        view.openConversation(summary("web-abc"));

        List<Element> edits = actions(view, "Edit and ask again");
        assertThat(edits).hasSize(2);
        click(edits.get(1));
        editor(view).setValue("What about pinning?");
        click(action(view, "Ask again"));

        assertThat(transcripts.turns("web-abc")).extracting(TranscriptTurn::text)
                .containsExactly("How do they work?", "A good answer.", "What about pinning?");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A view inside a UI and answering on the calling thread.
     *
     * <p>Both halves are needed to act on a turn: sending needs a UI to push tokens back through,
     * and asserting what the agent was asked needs the ask to have happened by the time the test
     * looks.
     */
    private static ChatView attached(Agent agent, ConversationTranscripts transcripts) {
        ChatView view = new ChatView(agent, new SameThreadExecutor(), transcripts, new ConversationActivity());
        new UI().add(view);
        return view;
    }

    private static ConversationSummary summary(String id) {
        return new ConversationSummary(id, "A conversation", "", "Web", List.of(), Optional.empty(),
                ConversationSummary.Source.STORED);
    }

    private static ConversationMessage user(String text) {
        return new ConversationMessage(ConversationMessage.Role.USER, text);
    }

    private static ConversationMessage assistant(String text) {
        return new ConversationMessage(ConversationMessage.Role.ASSISTANT, text);
    }

    private static List<String> roles(ChatView view) {
        return descendants(view.getElement())
                .filter(element -> element.getClassList().contains("jc-message"))
                .map(element -> element.getAttribute("data-role"))
                .toList();
    }

    private static List<String> tags(ChatView view) {
        // Text nodes have no tag and throw rather than answering, so they are dropped here.
        return descendants(view.getElement())
                .filter(element -> !element.isTextNode())
                .map(Element::getTag)
                .toList();
    }

    private static String transcriptText(ChatView view) {
        return view.getElement().getTextRecursively();
    }

    private static List<Element> actions(ChatView view, String label) {
        return descendants(view.getElement())
                .filter(element -> label.equals(element.getAttribute("aria-label")))
                .toList();
    }

    private static Element action(ChatView view, String label) {
        return actions(view, label).stream().findFirst()
                .orElseThrow(() -> new AssertionError("The transcript offers no control named " + label));
    }

    /** The message editor, told apart from the composer by the label it carries. */
    private static TextArea editor(ChatView view) {
        return descendants(view.getElement())
                .map(Element::getComponent)
                .flatMap(Optional::stream)
                .filter(TextArea.class::isInstance)
                .map(TextArea.class::cast)
                .filter(area -> "Edit your message".equals(area.getAriaLabel().orElse("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No message is being edited"));
    }

    private static Element emptyState(ChatView view) {
        return descendants(view.getElement())
                .filter(element -> element.getClassList().contains("jc-empty"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The empty state is not in the transcript at all"));
    }

    private static String composerValue(ChatView view) {
        return composer(view).getValue();
    }

    /** The composer at the foot of the view, told apart from a message editor by its label. */
    private static TextArea composer(ChatView view) {
        return descendants(view.getElement())
                .map(Element::getComponent)
                .flatMap(Optional::stream)
                .filter(TextArea.class::isInstance)
                .map(TextArea.class::cast)
                .filter(area -> "Message".equals(area.getAriaLabel().orElse("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The view has no composer"));
    }

    private static void click(Element element) {
        Component component = element.getComponent().orElseThrow();
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
    }

    private static java.util.stream.Stream<Element> descendants(Element root) {
        List<Element> found = new ArrayList<>();
        collect(root, found);
        return found.stream();
    }

    private static void collect(Element element, List<Element> into) {
        element.getChildren().forEach(child -> {
            into.add(child);
            collect(child, into);
        });
    }

    /** An agent that is never asked anything: some of these tests are about reading, not answering. */
    private static final class SilentAgent implements Agent {

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }
    }

    /**
     * An agent that records what it is asked and answers nothing.
     *
     * <p>Deliberately silent rather than instantly complete: a callback would have to be delivered
     * through {@code UI.access}, which needs a session these tests have no reason to build, and
     * what is under test here is which question was put and how often - not what came back.
     */
    private static final class RecordingAgent implements Agent {

        private final List<String> asked = new ArrayList<>();

        @Override
        public String respondTo(String conversationId, String question) {
            asked.add(question);
            return "";
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            asked.add(question);
            return "";
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }
    }

    /** Runs the response on the calling thread, so a test can assert straight after the click. */
    private static final class SameThreadExecutor extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    /** The transcript store as a map, with the one refusal the view has to cope with available. */
    private static final class FakeTranscripts implements ConversationTranscripts {

        private final Map<String, List<TranscriptTurn>> byConversation = new LinkedHashMap<>();
        private boolean refuseRewinds;
        private int minted;

        void give(String id, ConversationMessage... messages) {
            List<TranscriptTurn> turns = new ArrayList<>();
            for (ConversationMessage message : messages) {
                turns.add(new TranscriptTurn(mint(), message.role(), message.text()));
            }
            byConversation.put(id, turns);
        }

        void refuseRewinds() {
            refuseRewinds = true;
        }

        private String mint() {
            return String.format("%016x", ++minted);
        }

        @Override
        public List<TranscriptTurn> turns(String id) {
            return List.copyOf(byConversation.getOrDefault(id, List.of()));
        }

        @Override
        public Optional<TranscriptTurn> append(String id, ConversationMessage.Role role, String text) {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            TranscriptTurn turn = new TranscriptTurn(mint(), role, text);
            byConversation.computeIfAbsent(id, key -> new ArrayList<>()).add(turn);
            return Optional.of(turn);
        }

        @Override
        public Optional<List<TranscriptTurn>> rewindTo(String id, String turnId) {
            if (refuseRewinds) {
                return Optional.empty();
            }
            List<TranscriptTurn> turns = byConversation.getOrDefault(id, List.of());
            for (int i = 0; i < turns.size(); i++) {
                if (turns.get(i).id().equals(turnId)) {
                    List<TranscriptTurn> surviving = new ArrayList<>(turns.subList(0, i));
                    byConversation.put(id, surviving);
                    return Optional.of(List.copyOf(surviving));
                }
            }
            return Optional.empty();
        }
    }
}
