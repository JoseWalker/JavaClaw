package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.KeyDownEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.change.MapPutChange;
import com.vaadin.flow.server.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

    /**
     * Clicking a row pushed 4456 characters through a polite log region - one insertion of them
     * 4203 characters long - with {@code aria-busy} reading false the whole way. A screen reader
     * read an entire conversation out because the reader glanced at it. The streaming path already
     * did the right thing with the same one attribute, sixteen thousand mutations at a time; this
     * is that mechanism applied to the other way a transcript changes wholesale.
     */
    @Test
    @DisplayName("a conversation is loaded quietly and then named, rather than read out in full")
    void aLoadIsMutedAndThenAnnounced() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do virtual threads work?"), assistant("They unmount."));
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        Element transcript = byClass(view, "jc-transcript");
        transcripts.watch(() -> transcript.getAttribute("aria-busy"));

        view.openConversation(summary("web-abc"));

        assertThat(transcripts.busyWhileReading).isEqualTo("true");
        // And released again, or the region would stay muted for every answer after it.
        assertThat(transcript.getAttribute("aria-busy")).isEqualTo("false");
        // Said out loud, because a log region announces what is added to it and nothing announces
        // that four thousand characters of it belong to a different conversation than before.
        assertThat(byClass(view, "jc-announcer").getTextRecursively())
                .isEqualTo("Opened A conversation");
    }

    /**
     * The muting was there, and the browser never saw it.
     *
     * <p>Both calls - the one that mutes the region and the one that lets it go - happened inside
     * a single server visit, and a visit carries one value per attribute: whatever it was set to
     * last. So the response that pushed 4203 characters of somebody else's conversation into a
     * polite log region carried {@code aria-busy="false"} along with them, which is the value the
     * audit read in the DOM and the reason a screen reader read the conversation out loud.
     *
     * <p>The test above this one passes on exactly that code, and that is the point of writing
     * this one. It stands inside the store read and sees a state that only ever existed on the
     * server. What assistive technology reads is the response, so the response is what is asserted
     * here: the value the browser is actually handed, taken from the change the server collects
     * for the wire rather than from the field the server kept it in.
     */
    @Test
    @DisplayName("the response that carries a loaded conversation carries the muting with it")
    void aLoadIsMutedInTheResponseThatCarriesIt() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do virtual threads work?"), assistant("They unmount."));
        UI ui = UiHarness.ui();
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        ui.add(view);
        Element transcript = byClass(view, "jc-transcript");
        // The first render is not what is under test; draining it leaves only the load behind.
        ariaBusySent(ui, transcript);

        view.openConversation(summary("web-abc"));

        // The turns are on screen, and the response that put them there says the region is being
        // rewritten. Both halves matter: muting a region the reader is still waiting for would be
        // a load that costs a round trip before anything appears.
        assertThat(roles(view)).containsExactly("user", "assistant");
        assertThat(ariaBusySent(ui, transcript)).isEqualTo("true");

        confirmPaintedFrame(ui);

        // And released once the browser has drawn a frame with the new transcript already muted,
        // so there is no paint in which the added text sits in a region that claims to be idle.
        assertThat(ariaBusySent(ui, transcript)).isEqualTo("false");
    }

    /**
     * A reader who clicks two rows in quick succession has two quiet windows in flight, and the
     * frame the first one was waiting for arrives while the second is still loading. Releasing on
     * it would unmute the region in the middle of the second rebuild - the original defect, put
     * back by the fix for it.
     */
    @Test
    @DisplayName("a frame confirmed for the conversation just left does not unmute the one arriving")
    void aStaleFrameDoesNotUnmuteTheNextLoad() {
        FakeTranscripts transcripts = new FakeTranscripts();
        transcripts.give("web-abc", user("How do virtual threads work?"));
        transcripts.give("web-def", user("And structured concurrency?"));
        UI ui = UiHarness.ui();
        ChatView view = new ChatView(new SilentAgent(), executor, transcripts, new ConversationActivity());
        ui.add(view);
        Element transcript = byClass(view, "jc-transcript");

        view.openConversation(summary("web-abc"));
        List<PendingJavaScriptInvocation> firstFrame = pendingFrames(ui);
        view.openConversation(summary("web-def"));
        firstFrame.forEach(frame -> frame.complete(JacksonUtils.nullNode()));

        assertThat(transcript.getAttribute("aria-busy")).isEqualTo("true");
    }

    // ------------------------------------------------------- while it streams

    /**
     * The severity-1 defect this pins, and the reason the lock was removed rather than labelled.
     *
     * <p>The composer used to go read-only for the length of every answer, and a read-only Vaadin
     * text area is pixel-identical to an editable one: same fill, same placeholder, same caret,
     * same focus ring. So the largest control on the screen went on saying "Ask anything" and
     * silently dropped every character typed into it - an interface asserting one thing and doing
     * another, for the whole of every turn, with no way for the reader to tell which of the two was
     * true. Labelling the lock would have made the assertion honest; removing it makes the
     * assertion true, which is the fix that was chosen.
     *
     * <p>The field also has to stay enabled, for the reason the lock was preferred to a disable in
     * the first place: a disabled element cannot hold focus, so locking a field the reader is
     * sitting in moves the caret to the document body and their next Tab restarts at the top of the
     * page.
     */
    @Test
    @DisplayName("the composer stays editable while an answer streams")
    void keepsTheComposerEditableWhileStreaming() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");

        click(action(view, "Send message"));

        assertThat(composer(view).isReadOnly()).isFalse();
        assertThat(composer(view).isEnabled()).isTrue();
        assertThat(composer(view).getElement().getProperty("disabled", false)).isFalse();
    }

    @Test
    @DisplayName("stopping a turn puts the send action back in the button")
    void releasesTheComposerWhenTheTurnIsStopped() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        assertThat(offersStop(view)).isTrue();

        click(action(view, "Stop responding"));

        assertThat(offersStop(view)).isFalse();
        assertThat(composer(view).isReadOnly()).isFalse();
    }

    /**
     * The point of letting the reader type while an answer streams: the draft has to be worth
     * something when the answer ends.
     *
     * <p>Two failures are pinned at once, and they are the two ways this change could have been
     * got wrong. A draft cleared or reverted when the turn closes would make typing during an
     * answer pointless, which is the read-only composer again with extra steps. A send button left
     * disabled after the turn closes - it is enabled during the turn only because it is a Stop
     * control - would leave the reader holding a question they cannot send.
     */
    @Test
    @DisplayName("a draft typed while an answer streams is sendable the moment the answer ends")
    void aDraftTypedWhileStreamingSurvivesAndSends() {
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        composer(view).setValue("And what about pinning?");
        click(action(view, "Stop responding"));
        assertThat(composerValue(view)).isEqualTo("And what about pinning?");
        click(action(view, "Send message"));

        assertThat(agent.asked).containsExactly("How do they work?", "And what about pinning?");
    }

    /**
     * A deliberate keystroke that does nothing and says nothing is the same defect as the composer
     * that looked editable and was not: the reader cannot tell a refusal from a broken key.
     *
     * <p>What is refused is only the send. The line the composer already keeps for saying what is
     * going on is where the answer goes - the reader is looking at it, the busy hint it replaces
     * was already there, and it restores itself when the turn ends. It is announced through the
     * live region that was there from the first paint, so a reader who cannot see the line is told
     * the same thing.
     */
    @Test
    @DisplayName("Enter while an answer streams says why, rather than swallowing the keystroke")
    void enterWhileStreamingIsAnswered() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        composer(view).setValue("And what about pinning?");

        pressEnter(view);

        assertThat(hintText(view)).contains("nothing was sent");
        assertThat(announcement(view)).contains("nothing was sent");
    }

    /**
     * The other half of the choice, and the reason it is not a toast: an Enter with nothing to send
     * is answered with silence, because that is what the same keystroke does when no turn is
     * running. A reader who pressed it lost nothing, so there is nothing to report - and reporting
     * it anyway would put a refusal on the screen for every stray Return during a long answer.
     */
    @Test
    @DisplayName("Enter on an empty composer while streaming says nothing at all")
    void enterWithNothingToSendIsSilent() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        pressEnter(view);

        assertThat(hintText(view)).contains("JavaClaw is responding");
        assertThat(hintText(view)).doesNotContain("nothing was sent");
    }

    /**
     * Fail closed: however the stream ends, the composer ends up sendable.
     *
     * <p>A composer stuck at "Stop" is worse than the defect this change fixes - the reader has no
     * way back to sending at all, and the only control offered cancels a turn that is already over.
     * The failure path is the one that gets there, because it is the path nobody exercises by hand.
     * The draft typed while the answer was still arriving has to survive it too: the error belongs
     * to the answer, not to the question the reader is in the middle of writing.
     */
    @Test
    @DisplayName("an answer that fails hands the composer back with the draft intact")
    void aFailedAnswerHandsTheComposerBackWithTheDraftIntact() {
        ControllableAgent agent = new ControllableAgent();
        ChatView view = attachedTo(new InlineUi(), agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        composer(view).setValue("And what about pinning?");

        agent.fail("The provider hung up");

        assertThat(offersStop(view)).isFalse();
        assertThat(composerValue(view)).isEqualTo("And what about pinning?");
        assertThat(sendIsEnabled(view)).isTrue();
    }

    @Test
    @DisplayName("an answer that completes hands the composer back with the draft intact")
    void aCompletedAnswerHandsTheComposerBackWithTheDraftIntact() {
        ControllableAgent agent = new ControllableAgent();
        ChatView view = attachedTo(new InlineUi(), agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        composer(view).setValue("And what about pinning?");

        agent.complete();

        assertThat(offersStop(view)).isFalse();
        assertThat(composerValue(view)).isEqualTo("And what about pinning?");
        assertThat(sendIsEnabled(view)).isTrue();
    }

    /**
     * The keystroke was registered with {@code listenOn(this)}, which sounds like scoping and is
     * really a reachability bug: the listener only fires for a keydown whose target is inside the
     * view's own subtree, and the skip link lands the reader on {@code main.jc-main__content},
     * which is not. A reader who arrived that way, or who was anywhere else in the shell, pressed
     * Escape and watched the answer carry on - measured at 2838 further characters while the
     * announcer still said "Answering".
     *
     * <p>The event is fired on the UI's own element, which is what a keystroke with no field
     * focused reaches, and is by construction outside this view.
     */
    @Test
    @DisplayName("Escape stops the answer from anywhere on the page, not only from inside the view")
    void stopsTheAnswerWhenEscapeArrivesFromOutsideTheView() {
        ChatView view = inSession(new RecordingAgent(), new FakeTranscripts());
        UI ui = view.getUI().orElseThrow();
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        // Whether a turn is running is read off the button's own job rather than off the field,
        // which no longer changes at all while an answer streams.
        assertThat(offersStop(view)).isTrue();

        UiHarness.press(ui.getElement(), "Escape");

        assertThat(offersStop(view)).isFalse();
    }

    /**
     * The other half of unscoping it. Escape belongs to whatever is on top - a dialog, a menu, the
     * search box's own clear - and the handler now runs for every one of those, so it has to be
     * able to tell "stop the answer" from "there is no answer". A handler that threw, or that
     * cleared state it did not own, would have traded one defect for a louder one.
     */
    @Test
    @DisplayName("Escape with nothing running changes nothing")
    void escapeWithNoTurnRunningIsANoOp() {
        ChatView view = inSession(new RecordingAgent(), new FakeTranscripts());
        UI ui = view.getUI().orElseThrow();
        composer(view).setValue("A draft nobody has sent");

        UiHarness.press(ui.getElement(), "Escape");

        assertThat(offersStop(view)).isFalse();
        assertThat(composerValue(view)).isEqualTo("A draft nobody has sent");
    }

    /**
     * No implicit send queue. The keystroke is now pressed rather than assumed, because the wrong
     * fix for the refusal it meets is to remember the message and dispatch it when the turn ends -
     * which would put a question the reader never watched leave into a conversation that has moved
     * on since they typed it. That is the same dishonesty as a composer that looks editable and is
     * not, so it is asserted against here: one question asked, and the second still in the field.
     */
    @Test
    @DisplayName("Enter is refused while a turn streams rather than sending a second question")
    void refusesASecondQuestionWhileStreaming() {
        RecordingAgent agent = new RecordingAgent();
        ChatView view = attached(agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        composer(view).setValue("And again");
        pressEnter(view);
        click(action(view, "Stop responding"));

        assertThat(agent.asked).containsExactly("How do they work?");
        assertThat(composerValue(view)).isEqualTo("And again");
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

    /**
     * The same view, in a UI the caller has chosen.
     *
     * <p>Only the tests that end a turn need this, and they need it for the UI's {@code access}:
     * the agent's completion and failure callbacks arrive on a worker and are pushed back through
     * it, so a UI that does not run what it is handed leaves those two paths untested.
     *
     * @param ui          the UI to attach into, already configured by the caller
     * @param agent       the agent the view puts questions to
     * @param transcripts the store the view reads and writes
     * @return the view, attached and ready to be asked a question
     */
    private static ChatView attachedTo(UI ui, Agent agent, ConversationTranscripts transcripts) {
        ChatView view = new ChatView(agent, new SameThreadExecutor(), transcripts, new ConversationActivity());
        ui.add(view);
        return view;
    }

    /**
     * The same view, in a UI that has a session behind it.
     *
     * <p>Only the keystroke tests need this, and they need it for one reason: driving a real DOM
     * listener means letting Vaadin finish the work it defers to the client response, and the
     * deferred work includes the {@code executeJs} calls this view installs on attach. Those go
     * through the session lock, so a UI with no session throws before the listener is ever
     * attached. The session is a stub with the lock check disabled rather than a real one - the
     * tests assert about a keystroke, not about session management, and a real session needs a
     * servlet and a deployment configuration to exist at all.
     */
    private static ChatView inSession(Agent agent, ConversationTranscripts transcripts) {
        ChatView view = new ChatView(agent, new SameThreadExecutor(), transcripts, new ConversationActivity());
        UI ui = UiHarness.ui();
        ui.add(view);
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

    /**
     * Whether the composer's one button is currently offering to stop an answer.
     *
     * <p>This is how these tests ask "is a turn running". They used to ask the field, which was
     * read-only for exactly that long; it no longer changes at all while an answer streams, so the
     * question has to be put to the thing that does change.
     *
     * @param view the view under test
     * @return true while the button is a Stop control
     */
    private static boolean offersStop(ChatView view) {
        return !actions(view, "Stop responding").isEmpty();
    }

    /**
     * Whether the send button is pressable.
     *
     * @param view the view under test, which must not be streaming - while it is, the button is
     *             labelled as a Stop control and this lookup fails rather than answering about it
     * @return true when the button would send what is in the composer
     */
    private static boolean sendIsEnabled(ChatView view) {
        return action(view, "Send message").isEnabled();
    }

    /** The line under the composer, which says what is going on and why something did not happen. */
    private static String hintText(ChatView view) {
        return byClass(view, "jc-composer__hint").getTextRecursively();
    }

    /** What the off-screen live region is currently saying. */
    private static String announcement(ChatView view) {
        return byClass(view, "jc-announcer").getTextRecursively();
    }

    /**
     * Presses Enter in the composer, unmodified.
     *
     * <p>Fired as the event rather than by calling the handler: what is under test is that a plain
     * Enter reaches a listener which refuses to send and says so, and a test that called the
     * refusal directly would pass on a view where nothing was listening.
     *
     * @param view the view whose composer receives the keystroke
     */
    private static void pressEnter(ChatView view) {
        TextArea prompt = composer(view);
        ComponentUtil.fireEvent(prompt, new KeyDownEvent(prompt, "Enter"));
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

    /**
     * The value of {@code aria-busy} the next response actually carries for an element.
     *
     * <p>Read from the change the server collects for the wire rather than from the element, and
     * the difference is the whole defect this exists for: a visit that sets an attribute twice
     * sends one value, so a region muted and unmuted inside one visit is a region the browser is
     * only ever told is idle. Asserting the element would report the muting that the browser never
     * receives.
     *
     * <p>Collecting also clears what was collected, so each call reports one response and the next
     * call reports the one after it. A test therefore calls this once to discard the first render.
     *
     * @param ui      the UI whose next response to read
     * @param element the element whose attribute to look for
     * @return the value the response carries, or null when the response says nothing about it -
     *         which is itself an answer, because a response that is silent leaves the browser on
     *         whatever it had
     */
    private static String ariaBusySent(UI ui, Element element) {
        StateTree tree = (StateTree) ui.getElement().getNode().getOwner();
        tree.runExecutionsBeforeClientResponse();
        List<String> sent = new ArrayList<>();
        tree.collectChanges(change -> {
            if (change instanceof MapPutChange put
                    && change.getNode() == element.getNode()
                    && "aria-busy".equals(put.getKey())) {
                sent.add(String.valueOf(put.getValue()));
            }
        });
        return sent.isEmpty() ? null : sent.getLast();
    }

    /**
     * The confirmations the server is waiting on from the browser, oldest first.
     *
     * <p>Only the subscribed invocations: those are the ones with a handler waiting on the far
     * side, which is what a frame confirmation is and what the view's other injected scripts are
     * not.
     *
     * @param ui the UI whose queue to drain; draining empties it, so each call reports one visit
     * @return the pending confirmations, never null and possibly empty
     */
    private static List<PendingJavaScriptInvocation> pendingFrames(UI ui) {
        StateTree tree = (StateTree) ui.getElement().getNode().getOwner();
        tree.runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(PendingJavaScriptInvocation::isSubscribed)
                .toList();
    }

    /**
     * Answers the server the way a browser that has drawn the frame would.
     *
     * @param ui the UI waiting on the confirmation
     * @throws AssertionError when the server never asked for one, which is the state the defect
     *                        left it in: nothing to wait for, so nothing to release
     */
    private static void confirmPaintedFrame(UI ui) {
        List<PendingJavaScriptInvocation> waiting = pendingFrames(ui);
        if (waiting.isEmpty()) {
            throw new AssertionError("The server never asked the browser to confirm a painted frame");
        }
        waiting.forEach(frame -> frame.complete(JacksonUtils.nullNode()));
    }

    private static Element byClass(ChatView view, String className) {
        return descendants(view.getElement())
                .filter(element -> element.getClassList().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with class " + className));
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

    /**
     * An agent that starts a turn and leaves it open until the test says how it ends.
     *
     * <p>{@link RecordingAgent} answers nothing and therefore never closes a turn, which is what
     * the tests about a <em>running</em> answer want. The two paths out of a turn that nobody
     * exercises by hand - the provider completing, and the provider failing - need the listener
     * kept and called later, and that is the whole of what this adds.
     */
    private static final class ControllableAgent implements Agent {

        /** The listener of the turn currently open, or null before the first question. */
        private ResponseListener listener;

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("The streaming form is the one under test");
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            this.listener = listener;
            return "";
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }

        /**
         * Ends the open turn the way a provider that broke mid-answer would.
         *
         * @param message what went wrong, as the view would be told it
         */
        void fail(String message) {
            open().onError(message);
        }

        /** Ends the open turn the way a provider that finished normally would. */
        void complete() {
            open().onComplete();
        }

        /**
         * @return the listener of the turn in progress
         * @throws AssertionError when no question has been asked, which would otherwise surface as
         *                        a null dereference several frames away from the mistake
         */
        private ResponseListener open() {
            if (listener == null) {
                throw new AssertionError("No turn is open: nothing has been asked yet");
            }
            return listener;
        }
    }

    /**
     * A UI that runs what a worker hands it, immediately and on the calling thread.
     *
     * <p>{@code UI.access} normally goes through the session: it locks, runs, unlocks and pushes.
     * There is no session here and nothing to lock against - these tests run the whole exchange on
     * one thread - and standing up a real one needs a servlet and a deployment configuration.
     * Overriding the one method is what makes a completion or a failure observable at all; without
     * it those callbacks are queued against a session that does not exist and the turn never ends.
     */
    private static final class InlineUi extends UI {

        @Override
        public Future<Void> access(Command command) {
            command.execute();
            return CompletableFuture.completedFuture(null);
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

        /**
         * Reads whatever the test wants to know at the moment the store is asked for a transcript.
         *
         * <p>The quiet window a load opens is only observable from inside it: by the time
         * {@code openConversation} returns, the region has been muted, rewritten and unmuted again.
         * The store read is the one point the test can stand at while the window is open.
         */
        private Supplier<String> watched;

        /** What {@link #watched} reported the last time this store was read. */
        private String busyWhileReading;

        void watch(Supplier<String> observation) {
            watched = observation;
        }

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
            if (watched != null) {
                busyWhileReading = watched.get();
            }
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
