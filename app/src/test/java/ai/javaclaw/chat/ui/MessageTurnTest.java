package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one turn is allowed to offer, and when.
 *
 * <p>Before this component existed a turn was an anonymous {@code Div}: it could be read and
 * nothing else, so an answer that came back wrong left the reader retyping their question. The
 * controls added here are only worth having if they cannot lie, and there are two ways for them to
 * lie. One is offering to act on a turn the store has no record of - the button would be pressed
 * and nothing whatsoever would happen. The other is offering to act on a turn that is still being
 * written, where "regenerate" would mean cancelling a stream and "copy" would take half a sentence.
 * Both are pinned below.
 */
class MessageTurnTest {

    @Test
    @DisplayName("an answer that has not started yet says it is thinking, rather than sitting empty")
    void anEmptyAnswerSaysItIsThinking() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());

        assertThat(answer.getClassNames()).contains("jc-message--thinking");
        assertThat(text(answer)).contains("Thinking");
    }

    @Test
    @DisplayName("the first token replaces the thinking indicator with the answer itself")
    void theFirstTokenEndsTheThinkingIndicator() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());

        answer.append("Virtual threads ");

        assertThat(answer.getClassNames()).doesNotContain("jc-message--thinking");
        assertThat(text(answer)).contains("Virtual threads").doesNotContain("Thinking");
    }

    @Test
    @DisplayName("a turn still being written offers no controls at all")
    void aStreamingTurnHasNoActions() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.identify("0123456789abcdef");

        answer.append("Half a sen");

        assertThat(actions(answer)).isEmpty();
    }

    @Test
    @DisplayName("a settled answer offers copy and regenerate")
    void aSettledAnswerOffersCopyAndRegenerate() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.append("They unmount when blocked.");
        answer.identify("0123456789abcdef");

        answer.markComplete();

        assertThat(actions(answer)).containsExactly("Copy message", "Answer again");
    }

    @Test
    @DisplayName("a settled question offers copy and edit, not regenerate")
    void aSettledQuestionOffersCopyAndEdit() {
        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "How do they work?", new NoActions());
        question.identify("0123456789abcdef");

        question.markComplete();

        assertThat(actions(question)).containsExactly("Copy message", "Edit and ask again");
    }

    @Test
    @DisplayName("a turn the store never recorded offers nothing that would address it")
    void anUnrecordedTurnOffersOnlyCopy() {
        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "How do they work?", new NoActions());

        question.markComplete();

        // Copy needs no identity - the text is in front of the reader. Rewinding does, and a button
        // that cannot name the turn it would rewind to is a control that does nothing when pressed.
        assertThat(actions(question)).containsExactly("Copy message");
    }

    @Test
    @DisplayName("an answer that failed offers the one control that can help: asking again")
    void aFailedAnswerOffersRegenerateAlone() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.identify("0123456789abcdef");

        answer.fail(FailureNotice.UNREACHABLE);

        // Copying this UI's own apology is not a thing anyone wants to paste somewhere.
        assertThat(actions(answer)).containsExactly("Answer again");
        assertThat(answer.getClassNames()).contains("jc-message--failed");
        // The bubble can only ever say one of this application's own sentences: there is no
        // signature on this class that a provider's message fits into. See FailureNoticeTest.
        assertThat(text(answer)).contains(FailureNotice.UNREACHABLE.sentence());
    }

    @Test
    @DisplayName("editing opens on the message as written and puts the bubble back when cancelled")
    void cancellingAnEditRestoresTheMessage() {
        RecordingActions actions = new RecordingActions();
        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "How do they work?", actions);
        question.identify("0123456789abcdef");
        question.markComplete();

        click(action(question, "Edit and ask again"));
        assertThat(editor(question).getValue()).isEqualTo("How do they work?");
        click(action(question, "Discard the edit"));

        assertThat(question.getClassNames()).doesNotContain("jc-message--editing");
        assertThat(actions.edited).isEmpty();
        assertThat(text(question)).contains("How do they work?");
    }

    @Test
    @DisplayName("sending an edit reports the new text, and only once")
    void sendingAnEditReportsTheNewText() {
        RecordingActions actions = new RecordingActions();
        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "How do they work?", actions);
        question.identify("0123456789abcdef");
        question.markComplete();

        click(action(question, "Edit and ask again"));
        editor(question).setValue("How do they compare to platform threads?");
        click(action(question, "Ask again"));

        assertThat(actions.edited).containsExactly("How do they compare to platform threads?");
    }

    @Test
    @DisplayName("an edit emptied down to whitespace is not sent")
    void anEmptyEditIsRefused() {
        RecordingActions actions = new RecordingActions();
        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "How do they work?", actions);
        question.identify("0123456789abcdef");
        question.markComplete();

        click(action(question, "Edit and ask again"));
        editor(question).setValue("   ");
        click(action(question, "Ask again"));

        // Refused where the reader can still see their message, rather than accepted and turned
        // into a question the agent has to make something of.
        assertThat(actions.edited).isEmpty();
        assertThat(question.getClassNames()).contains("jc-message--editing");
    }

    @Test
    @DisplayName("regenerating reports the answer it was asked about")
    void regenerateReportsItsTurn() {
        RecordingActions actions = new RecordingActions();
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", actions);
        answer.append("They unmount when blocked.");
        answer.identify("0123456789abcdef");
        answer.markComplete();

        click(action(answer, "Answer again"));

        assertThat(actions.regenerated).containsExactly(answer);
    }

    @Test
    @DisplayName("a completed answer is markup; the reader's own message stays the text they typed")
    void onlyTheAnswerIsParsedAsMarkdown() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.append("- **Gradle** is incremental\n");
        answer.markComplete();

        MessageTurn question = new MessageTurn(ConversationMessage.Role.USER, "**not bold**", new NoActions());
        question.markComplete();

        assertThat(tags(answer)).contains("ul", "li", "strong");
        assertThat(tags(question)).doesNotContain("strong");
        assertThat(text(question)).contains("**not bold**");
    }

    @Test
    @DisplayName("a turn the reader stopped is marked, so half an answer is not read as a whole one")
    void aStoppedTurnIsMarked() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.identify("0123456789abcdef");
        answer.append("They unmount");

        answer.stop();

        assertThat(text(answer)).contains("Stopped");
        assertThat(actions(answer)).containsExactly("Copy message", "Answer again");
    }

    /**
     * The transcript is a {@code role="log"} region, and a log region announces what is added to it.
     *
     * <p>An audit put a {@code MutationObserver} on the transcript across one streamed answer and
     * measured 289 nodes added and 284 removed, carrying 48,562 characters, for a final answer of 772
     * - a 63-fold amplification, because the block still being written was torn down and rebuilt on
     * every single token. The reference product under the same instrumentation amplified 1.6-fold.
     *
     * <p>Almost all of that churn is avoidable and none of it is useful: a sentence arriving as
     * ordinary prose is one text node getting longer, and rebuilding it means the region reports the
     * whole sentence as newly added text several times a second. Where the tail really does need
     * typesetting - a code span closing, emphasis completing - the rebuild is the only correct answer
     * and still happens; this is about the ninety-odd tokens in between.
     *
     * <p>Fails on the original, which replaced the node on every token.
     */
    @Test
    @DisplayName("prose arriving token by token grows one node rather than replacing it each time")
    void doesNotRebuildThePlainTailOnEveryToken() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.append("Virtual ");
        // The node behind the element, not the element: Element is a fresh handle on every lookup,
        // and it is the node that is either updated or detached and replaced on the wire.
        StateNode firstTail = tail(answer).getNode();

        answer.append("threads ");
        answer.append("unmount ");
        answer.append("when they block.");

        assertThat(tail(answer).getNode()).as("the tail was replaced rather than extended")
                .isSameAs(firstTail);
        assertThat(tail(answer).getText()).isEqualTo("Virtual threads unmount when they block.");
    }

    /**
     * The other half: growing a node in place must not be allowed to skip a rebuild that was needed.
     *
     * <p>The tail is re-typeset on every token precisely because a later token can change what an
     * earlier one meant - two asterisks are two asterisks until the closing pair arrives, and then
     * they are emphasis. An optimisation that kept the text node would freeze the answer as the
     * source it was typed in, which is the defect the tail typesetting exists to fix.
     */
    @Test
    @DisplayName("a tail that becomes markup is still typeset, not left as the source")
    void stillTypesetsATailThatBecomesMarkup() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());

        answer.append("Use **virtual");
        assertThat(text(answer)).contains("**virtual");

        answer.append(" threads** here");

        assertThat(tags(answer)).contains("strong");
        assertThat(text(answer)).doesNotContain("**");
    }

    /**
     * The same question as the two tests above, asked of the block a fence opens.
     *
     * <p>A live audit of the running build watched {@code ```bash} sit on screen as body prose with
     * the snippet under it in the proportional prose font and no {@code pre} in the document at all.
     * A code block is the one part of an answer where the font is the information - a reader
     * skimming a snippet is reading indentation - so the seconds before the closing fence arrives
     * are exactly the seconds it is worth having.
     *
     * <p>Fails on the original: the tail's fenced run was emitted as one text node holding the
     * delimiter and the source together.
     */
    @Test
    @DisplayName("a code block arriving token by token is drawn as code from its opening fence")
    void drawsAnOpenCodeBlockAsCode() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());

        answer.append("```bash\n");
        answer.append("./gradlew ");
        answer.append("build\n");

        assertThat(tags(answer)).contains("figure", "pre", "code");
        assertThat(text(answer)).contains("./gradlew build").doesNotContain("```");
    }

    /**
     * And the churn half of it, which is why the block is grown rather than rebuilt.
     *
     * <p>The transcript is a {@code role="log"} region. Re-rendering a sixty-line snippet on every
     * token would report the whole snippet as newly added text dozens of times a second, which is
     * the amplification {@link #doesNotRebuildThePlainTailOnEveryToken} exists to prevent - and
     * drawing the block properly must not buy back the defect that test pins.
     *
     * <p>Fails on the original for a different reason than it would pass for: there was no code
     * element in the streaming tail to keep.
     */
    @Test
    @DisplayName("code arriving inside an open block extends it rather than rebuilding it")
    void growsAnOpenCodeBlockInPlace() {
        MessageTurn answer = new MessageTurn(ConversationMessage.Role.ASSISTANT, "", new NoActions());
        answer.append("```bash\n./gradlew ");
        StateNode firstBlock = element(answer, "figure").getNode();

        answer.append("build");

        assertThat(element(answer, "figure").getNode())
                .as("the code block was replaced rather than extended")
                .isSameAs(firstBlock);
        assertThat(text(answer)).contains("./gradlew build");
    }

    // ------------------------------------------------------------------ helpers

    /** The one element with this tag inside a turn. */
    private static Element element(MessageTurn turn, String tag) {
        return descendants(turn.getElement())
                .filter(element -> !element.isTextNode())
                .filter(element -> tag.equals(element.getTag()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("This turn holds no <" + tag + ">"));
    }

    /**
     * The one node the block still being written is drawn into.
     *
     * @param turn a streaming assistant turn whose tail is ordinary prose
     * @return its single tail node
     * @throws AssertionError when the tail is not a single text node, which is the state these tests
     *                        are about rather than something to paper over
     */
    private static Element tail(MessageTurn turn) {
        List<Element> tails = descendants(turn.getElement())
                .filter(Element::isTextNode)
                .filter(node -> !node.getText().isBlank())
                .filter(node -> !"You".equals(node.getText()) && !"JavaClaw".equals(node.getText()))
                .toList();
        assertThat(tails).as("the answer is not being drawn as one run of text").hasSize(1);
        return tails.getFirst();
    }

    /** The accessible names of the controls this turn currently offers, in the order shown. */
    private static List<String> actions(MessageTurn turn) {
        return descendants(turn.getElement())
                .filter(element -> element.getClassList().contains("jc-msg-action"))
                .map(element -> element.getAttribute("aria-label"))
                .toList();
    }

    private static Element action(MessageTurn turn, String label) {
        return descendants(turn.getElement())
                .filter(element -> label.equals(element.getAttribute("aria-label")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("This turn offers no control named " + label));
    }

    private static TextArea editor(MessageTurn turn) {
        return descendants(turn.getElement())
                .map(Element::getComponent)
                .flatMap(Optional::stream)
                .filter(TextArea.class::isInstance)
                .map(TextArea.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("This turn is not being edited"));
    }

    private static String text(MessageTurn turn) {
        return turn.getElement().getTextRecursively();
    }

    private static List<String> tags(MessageTurn turn) {
        return descendants(turn.getElement())
                .filter(element -> !element.isTextNode())
                .map(Element::getTag)
                .toList();
    }

    private static void click(Element element) {
        Component component = element.getComponent().orElseThrow();
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
    }

    private static Stream<Element> descendants(Element root) {
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

    /** A view that is never asked for anything: these tests are about the turn's own offer. */
    private static final class NoActions implements MessageTurn.Actions {

        @Override
        public void regenerate(MessageTurn answer) {
            throw new AssertionError("Not expected in this test");
        }

        @Override
        public void editAndResend(MessageTurn question, String text) {
            throw new AssertionError("Not expected in this test");
        }
    }

    private static final class RecordingActions implements MessageTurn.Actions {

        private final List<MessageTurn> regenerated = new ArrayList<>();
        private final List<String> edited = new ArrayList<>();

        @Override
        public void regenerate(MessageTurn answer) {
            regenerated.add(answer);
        }

        @Override
        public void editAndResend(MessageTurn question, String text) {
            edited.add(text);
        }
    }
}
