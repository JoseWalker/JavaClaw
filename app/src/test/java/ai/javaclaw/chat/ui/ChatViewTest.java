package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.change.MapPutChange;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * The reader's place, end to end, through the wiring that actually runs in the browser.
     *
     * <p>An audit asked one question in a new chat, watched the answer land, and read
     * {@code location.href} still on {@code /ui/} forty-five seconds later; a reload came back to an
     * empty transcript titled "New chat". The thread had been written - the column had grown a row -
     * so the only thing lost was where the reader was standing.
     *
     * <p>The gap is that the shell used to learn a chat had become a conversation from the
     * conversation list noticing, and the list reads a different store from the one this view writes
     * to. So the directory here is one that never learns anything, which is the state the running
     * application is in for as long as its own store takes to catch up - and the address still has
     * to appear, because the turn is on disk either way.
     *
     * <p>Asserted on what the browser is sent rather than on a call being made: an address that
     * never leaves the server is not an address.
     *
     * <p>Fails on the original, which has nothing to say to the shell when a turn is written and
     * leaves the URL on the root.
     */
    @Test
    @DisplayName("asking the first question of a new chat puts that chat in the address bar")
    void addressesTheChatTheReaderIsTypingIn() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new EmptyDirectory(), new MockEnvironment());
        ui.add(shell);
        ChatView view = new ChatView(new RecordingAgent(), new SameThreadExecutor(),
                new FakeTranscripts(), new ConversationActivity());
        shell.showRouterLayoutContent(view);
        String conversation = view.conversationId();
        composer(view).setValue("Say the single word: pineapple");
        addressesSentTo(ui);

        click(action(view, "Send message"));

        assertThat(addressesSentTo(ui)).contains("c/" + conversation);
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

    /** The upstream string a blind audit measured on screen, kept verbatim so this is that case. */
    private static final String UPSTREAM_ERROR =
            "429 Too Many Requests from POST http://localhost:11434/api/chat";

    /**
     * What a failed turn is allowed to say.
     *
     * <p>The provider's own sentence went into the bubble unaltered, so the transcript printed the
     * inference endpoint - scheme, host, port and path - to whoever was watching, and would print
     * whatever else a provider chose to put in a message: an API path, a file path, a key prefix, a
     * model no one is meant to know is configured. None of that is the reader's business and none
     * of it helps them; what helps them is being told the turn failed and that asking again is
     * worth doing.
     *
     * <p>Fails on the original at the first assertion: the measured turn read exactly "JavaClaw"
     * then the upstream string, 72 characters, across 2284 samples.
     */
    @Test
    @DisplayName("a failed turn says what happened without printing the endpoint it happened at")
    void aFailedAnswerNeverPrintsTheUpstreamEndpoint() {
        ControllableAgent agent = new ControllableAgent();
        ChatView view = attachedTo(new InlineUi(), agent, new FakeTranscripts());
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        agent.fail(UPSTREAM_ERROR);

        String shown = transcriptText(view);
        assertThat(shown).doesNotContain("http://", "localhost", "11434", "/api/chat", "POST");
        // It still has to be a failure the reader can act on rather than a blank turn.
        assertThat(shown).contains("Ask again");
        assertThat(failedTurns(view)).hasSize(1);
    }

    /**
     * The other half: a turn that failed has to still be there when the reader comes back.
     *
     * <p>Nothing was written for a failed turn, so a reload replaced the exchange with the question
     * alone - a question that appears never to have been asked, sitting above the next one, with
     * nothing in the transcript to say the model was ever reached. An audit measured the turn count
     * going from two to one across a reload with no trace of the failure left anywhere.
     *
     * <p>Fails on the original at the turn count: {@code fileAnswer} was skipped entirely once the
     * failure flag was set, so the store held one turn.
     */
    @Test
    @DisplayName("a failed turn is still in the transcript after the conversation is reopened")
    void aFailedAnswerSurvivesReopeningTheConversation() {
        ControllableAgent agent = new ControllableAgent();
        FakeTranscripts store = new FakeTranscripts();
        ChatView view = attachedTo(new InlineUi(), agent, store);
        view.openConversation(summary("web-abc"));
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        agent.fail(UPSTREAM_ERROR);

        ChatView reopened = attachedTo(new InlineUi(), new SilentAgent(), store);
        reopened.openConversation(summary("web-abc"));

        assertThat(roles(reopened)).containsExactly("user", "assistant");
        assertThat(failedTurns(reopened)).as("the reopened turn is drawn as the failure it was")
                .hasSize(1);
        assertThat(transcriptText(reopened)).contains("Ask again")
                .doesNotContain("localhost", "11434");
        // And the reader can do the one thing that helps, on the turn that failed.
        assertThat(actions(reopened, "Answer again")).isNotEmpty();
    }

    /**
     * A failed turn is this application's own sentence, and the model must never be told it said
     * it. Regenerating rewinds the conversation to that turn, and the rewind is what would carry a
     * fabricated assistant message into the model's history if the two stores disagreed about what
     * a failure is.
     */
    @Test
    @DisplayName("asking again after a failure leaves neither the failure nor the question behind")
    void regeneratingAFailedAnswerRewindsIt() {
        ControllableAgent agent = new ControllableAgent();
        FakeTranscripts store = new FakeTranscripts();
        ChatView view = attachedTo(new InlineUi(), agent, store);
        view.openConversation(summary("web-abc"));
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        agent.fail(UPSTREAM_ERROR);

        click(action(view, "Answer again"));

        assertThat(agent.asked).containsExactly("How do they work?", "How do they work?");
        assertThat(store.textsOf("web-abc")).containsExactly("How do they work?");
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

    /**
     * The other half of refusing a send, and the half a sighted reader actually meets.
     *
     * <p>An audit typed forty-nine characters mid-stream, pressed Enter, and got the refusal in a
     * 1x1 off-screen region - plus a stray blank line in their own draft, because the keystroke fell
     * through to the textarea's default and inserted a newline. So the entire visible consequence of
     * pressing Enter was that the reader's message was silently edited. Nothing on screen said the
     * send had been refused, and the one thing that did change was the one thing that should not
     * have.
     *
     * <p>Swallowing the keystroke is not something the server can do after the fact: the default
     * action happens in the browser, during the dispatch, and the only way to stop it is to have
     * told the browser to. So the claim is made against what actually crosses to the browser - the
     * filter that decides whether this keystroke is the composer's, and the expression evaluated
     * with it. That pairing is the contract; asserting the field's value on the server would assert
     * a value the browser had already changed underneath it.
     *
     * <p>Fails on the original, which registered a component-level key listener: the expressions it
     * published were a bare key filter, with nothing anywhere asking the browser to prevent
     * anything.
     */
    @Test
    @DisplayName("Enter is swallowed in the browser, so a refused send cannot edit the draft")
    void enterDoesNotFallThroughIntoTheDraft() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());

        assertThat(keydownExpressions(view))
                .as("the composer asks the browser to swallow the Enter it answers")
                .anyMatch(expression -> expression.contains("preventDefault"));
    }

    /**
     * The keystroke that has to keep its default, and the reason the swallow above is filtered
     * rather than unconditional: Shift + Enter is how a message spans several lines, so the browser
     * has to go on inserting that newline.
     *
     * <p>Asserted on the filter itself rather than by pressing the key, because "the server was not
     * told" and "the browser did nothing" are the same observation here and only the first is
     * reachable from a component tree. The filter is what the browser evaluates before either
     * happens.
     */
    @Test
    @DisplayName("Shift + Enter is not the composer's keystroke, so it still opens a new line")
    void shiftEnterIsLeftToTheTextarea() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());

        assertThat(enterFilter(view))
                .as("the composer's Enter filter stands aside for Shift, and for an IME composing")
                .contains("!event.shiftKey")
                .contains("!event.isComposing");
    }

    /**
     * The severity-1 defect this pins: a reload mid-answer destroyed the answer.
     *
     * <p>An audit measured 303 characters of an assistant turn on screen, pressed reload, and came
     * back to the question with no answer under it, no error marker and nothing to retry - twice, on
     * two runs. The answer was only ever written to the store from the completion and failure
     * callbacks, so a page that went away before either of them ran took the whole in-flight turn
     * with it. What the reader was left with is worse than an error: a conversation that appears to
     * have been ignored, with no sign that anything was ever said.
     *
     * <p>The flush is the same one the Stop control performs, for the same reason - half an answer
     * the reader was reading is a turn, and dropping it leaves the next question following a
     * question that was never answered.
     *
     * <p>Fails on the original, which had no detach listener at all: the store ends up holding the
     * question alone.
     */
    @Test
    @DisplayName("a page that goes away mid-answer keeps what had already arrived")
    void keepsAPartialAnswerWhenThePageGoesAway() {
        ControllableAgent agent = new ControllableAgent();
        FakeTranscripts transcripts = new FakeTranscripts();
        UI ui = new InlineUi();
        ChatView view = attachedTo(ui, agent, transcripts);
        String conversation = view.conversationId();
        composer(view).setValue("How do virtual threads work?");
        click(action(view, "Send message"));
        agent.emit("They unmount when they block");

        // A reload, a closed tab, a navigation away: the component tree goes, the store stays.
        ui.remove(view);

        assertThat(transcripts.turns(conversation)).extracting(TranscriptTurn::text)
                .containsExactly("How do virtual threads work?", "They unmount when they block");
    }

    /**
     * The same severity-1 defect, at the point it actually happens on a reload.
     *
     * <p>Detaching the view is not what a reload does to the UI it leaves behind. Vaadin keeps that
     * UI registered against the session until a heartbeat timeout, which is minutes away, so nothing
     * is detached at the moment the reader presses reload - and every path an answer had into the
     * transcript went through {@code ui.access}, whose commands run only if there is still a browser
     * on the other end to run them. The agent finished the answer in full, on its own thread, and
     * the whole of it was dropped into a queue nothing would ever drain.
     *
     * <p>This is that UI: one that accepts commands and never runs them, which is exactly what an
     * orphaned UI is. The claim is that the transcript ends up holding the answer anyway, because
     * the agent produced it - and it is the claim the detach test above cannot make, since a reload
     * produces no detach to hang anything on.
     *
     * <p>Fails on the original, where the store is left holding the question alone.
     */
    @Test
    @DisplayName("an answer completed for a browser that had gone is written down all the same")
    void recordsAnAnswerNoBrowserWasThereToReceive() {
        FakeTranscripts transcripts = new FakeTranscripts();
        ChatView view = attachedTo(new DeafUi(),
                new BlockingAgent("They unmount ", "when they block"), transcripts);
        String conversation = view.conversationId();
        composer(view).setValue("How do virtual threads work?");

        click(action(view, "Send message"));

        assertThat(transcripts.turns(conversation)).extracting(TranscriptTurn::text)
                .containsExactly("How do virtual threads work?", "They unmount when they block");
    }

    /**
     * The other side of filing from two threads: the answer must be written down once.
     *
     * <p>A live browser reaches the end of a turn twice - the completion the worker saw and the same
     * completion arriving on the UI thread - and both are now able to write. Two copies of one
     * answer in the transcript would be a worse defect than the one being fixed, because it survives
     * the reload rather than being cured by it.
     */
    @Test
    @DisplayName("an answer both threads reach the end of is recorded once, not twice")
    void recordsACompletedAnswerExactlyOnce() {
        FakeTranscripts transcripts = new FakeTranscripts();
        ChatView view = attachedTo(new InlineUi(),
                new BlockingAgent("They unmount ", "when they block"), transcripts);
        String conversation = view.conversationId();
        composer(view).setValue("How do virtual threads work?");

        click(action(view, "Send message"));

        assertThat(transcripts.turns(conversation)).extracting(TranscriptTurn::text)
                .containsExactly("How do virtual threads work?", "They unmount when they block");
        // And the turn on screen still knows what the store called it, or the reader cannot ask it
        // again: the thread that wrote the answer down is not always the one holding the component.
        assertThat(actions(view, "Answer again")).isNotEmpty();
    }

    /**
     * That a turn which arrived with nothing is not recorded as an answer.
     *
     * <p>The flush above must not become a way of writing empty assistant turns into transcripts:
     * a reader who reloads during the wait before the first token would otherwise find a reply
     * attributed to the assistant that the assistant never made.
     */
    @Test
    @DisplayName("a page that goes away before the first token records no answer at all")
    void recordsNothingWhenNothingHadArrived() {
        ControllableAgent agent = new ControllableAgent();
        FakeTranscripts transcripts = new FakeTranscripts();
        UI ui = new InlineUi();
        ChatView view = attachedTo(ui, agent, transcripts);
        String conversation = view.conversationId();
        composer(view).setValue("How do virtual threads work?");
        click(action(view, "Send message"));

        ui.remove(view);

        assertThat(transcripts.turns(conversation)).extracting(TranscriptTurn::text)
                .containsExactly("How do virtual threads work?");
    }

    // ------------------------------------------------------- while the connection is gone

    /**
     * Offline was not a designed state anywhere in this application.
     *
     * <p>An audit went offline and pressed Enter: twenty samples over fourteen seconds found no turn
     * added, the composer still holding the text, the send control still painted as it is when there
     * is nothing to send, and not one word from the application about any of it. The only signal was
     * Vaadin's own five-pixel striped band, whose text is in the DOM and is not painted readably.
     *
     * <p>The composer's hint line is where a reader looking at the keystroke that did nothing is
     * looking, so that is where the sentence goes. It is server-rendered and hidden until the
     * stylesheet reveals it, because the one thing that is certainly unavailable while the
     * connection is down is the server.
     *
     * <p>Fails on the original: there was no such element, and nothing in the sheet that mentioned
     * being offline.
     */
    @Test
    @DisplayName("the composer carries a line for the state where nothing can be sent")
    void theComposerSaysWhatOfflineMeans() {
        ChatView view = attached(new RecordingAgent(), new FakeTranscripts());

        String offline = byClass(view, "jc-composer__offline").getTextRecursively();
        assertThat(offline).contains("Offline");
        // No promise of a queue. The message is not sent later and must not read as though it were.
        assertThat(offline).doesNotContain("will be sent");
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

    // ------------------------------------------------------------------ the unsent draft

    /**
     * The half-written question, which used to be thrown away by the browser's Reload button.
     *
     * <p>A blind review typed "an unsent draft a reader would expect to survive a refresh" into the
     * composer, reloaded, and came back to an empty field - twice, on two different threads. The
     * product this UI is measured against restored the text verbatim. Nothing about the draft was
     * ever written down outside the component tree, and a reload destroys the whole of that tree, so
     * there was nothing for the second page load to read.
     *
     * <p>Fails on the original for exactly that reason: the second view is built from nothing but
     * the session, and the original put nothing there.
     */
    @Test
    @DisplayName("an unsent draft is still in the composer after a reload")
    void keepsAnUnsentDraftAcrossAReload() {
        VaadinSession session = UiHarness.session();
        ChatView before = inSession(session, new SilentAgent(), new FakeTranscripts());
        before.openConversation(summary("web-abc"));
        composer(before).setValue(DRAFT);

        // A reload is a new UI and a new view over the one session that outlives the page.
        ChatView after = inSession(session, new SilentAgent(), new FakeTranscripts());
        after.openConversation(summary("web-abc"));

        assertThat(composerValue(after)).isEqualTo(DRAFT);
    }

    /**
     * That the draft belongs to the conversation rather than to the composer.
     *
     * <p>The reload is only the loudest way to lose it. Clicking another row cleared the field just
     * as completely, so a reader who looked something up mid-question came back to nothing - and a
     * store keyed by anything less than the conversation would hand them the other thread's text,
     * which is worse than losing their own.
     *
     * <p>Fails on the original at the last assertion: {@code openConversation} cleared the composer
     * unconditionally and kept no record of what it cleared.
     */
    @Test
    @DisplayName("a draft left in one conversation is waiting there when the reader comes back")
    void keepsADraftPerConversation() {
        ChatView view = inSession(UiHarness.session(), new SilentAgent(), new FakeTranscripts());
        view.openConversation(summary("web-abc"));
        composer(view).setValue("half a question about virtual threads");

        view.openConversation(summary("web-def"));
        // The other thread's composer is its own, and empty.
        assertThat(composerValue(view)).isEmpty();
        composer(view).setValue("something else entirely");
        view.openConversation(summary("web-abc"));

        assertThat(composerValue(view)).isEqualTo("half a question about virtual threads");
    }

    /**
     * That a question which was actually asked is not offered back as though it were unsent.
     *
     * <p>The failure mode a draft store invites: keep what was in the field and every reload hands
     * the reader their last question again, primed to be sent a second time. The composer is emptied
     * by sending, and emptying it has to reach the record as well as the field.
     *
     * <p>This passes on the original - which kept nothing at all, and so could not offer anything
     * back - and is here to stop the fix for the two tests above from shipping that defect instead.
     */
    @Test
    @DisplayName("a question that was sent is not offered back as an unsent draft")
    void forgetsADraftOnceItHasBeenSent() {
        VaadinSession session = UiHarness.session();
        ChatView before = inSession(session, new RecordingAgent(), new FakeTranscripts());
        before.openConversation(summary("web-abc"));
        composer(before).setValue("What is a sealed interface?");
        click(action(before, "Send message"));

        ChatView after = inSession(session, new SilentAgent(), new FakeTranscripts());
        after.openConversation(summary("web-abc"));

        assertThat(composerValue(after)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** The draft the blind review typed, kept verbatim so this test is the reported case. */
    private static final String DRAFT = "an unsent draft a reader would expect to survive a refresh";

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
        return inSession(UiHarness.session(), agent, transcripts);
    }

    /**
     * The same view, in a UI over a session the caller already holds.
     *
     * <p>The draft tests need it: what a reload does is destroy the component tree and keep the
     * session, so staging one means building a second view over the first one's session. Sharing the
     * session is the whole of the fixture - if a value comes back, it came back through the session,
     * because nothing else crosses from one UI to the other.
     *
     * @param session     the session to attach into, shared with any other view built from it
     * @param agent       the agent the view puts questions to
     * @param transcripts the store the view reads and writes
     * @return the view, attached and ready
     */
    private static ChatView inSession(VaadinSession session, Agent agent,
                                      ConversationTranscripts transcripts) {
        ChatView view = new ChatView(agent, new SameThreadExecutor(), transcripts, new ConversationActivity());
        UiHarness.ui(session).add(view);
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

    /** The turns the transcript is presenting as failures, by the class the stylesheet tints. */
    private static List<Element> failedTurns(ChatView view) {
        return descendants(view.getElement())
                .filter(element -> element.getClassList().contains("jc-message--failed"))
                .toList();
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
     * <p>Delivered through the DOM listener the browser would deliver it to, rather than as a
     * component event: the composer's Enter is registered on the element so that the same
     * registration can also tell the browser to swallow the keystroke, and a test that fired a
     * {@code KeyDownEvent} would pass on a view whose element listener had been removed.
     *
     * @param view the view whose composer receives the keystroke
     * @throws AssertionError when nothing in the composer is listening for Enter
     */
    private static void pressEnter(ChatView view) {
        Element field = composer(view).getElement();
        ObjectNode event = JacksonUtils.createObjectNode();
        event.put(enterFilter(view), true);
        field.getNode().getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(field, "keydown", event));
    }

    /**
     * The expression the browser evaluates to decide whether an Enter is this composer's to answer.
     *
     * <p>Shortest first, for the reason {@link UiHarness#press} documents: a registration that also
     * asks the browser to swallow the key publishes a second expression alongside its filter, and
     * only the filter is what the server matches a delivered event against.
     *
     * @param view the view under test
     * @return the filter expression, never null
     * @throws AssertionError when the composer listens for no keystroke carrying Enter
     */
    private static String enterFilter(ChatView view) {
        return keydownExpressions(view).stream()
                .filter(expression -> expression.contains("'Enter'"))
                .min(Comparator.comparingInt(String::length))
                .orElseThrow(() -> new AssertionError("Nothing in the composer listens for Enter"));
    }

    /**
     * Every expression the browser is asked to evaluate for a keystroke in the composer.
     *
     * <p>This is the whole of what crosses to the client for a key press: the filter that decides
     * whether the server hears about it, and the event data expressions evaluated when it does. A
     * claim about what the browser does with the keystroke has to be made against these, because
     * nothing else about it exists on the server.
     *
     * @param view the view under test
     * @return the expressions, in no particular order
     */
    private static List<String> keydownExpressions(ChatView view) {
        return List.copyOf(composer(view).getElement().getNode()
                .getFeature(ElementListenerMap.class).getExpressions("keydown"));
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

    /**
     * The addresses the server has told the browser to show since this was last called.
     *
     * <p>Read off the pending script queue rather than off any field, for the reason the test that
     * uses it is about: an address that never leaves the server is not an address. Vaadin sends one
     * of two scripts depending on whether the client router is in play, so both shapes are
     * recognised. Draining clears the queue, so each call reports one visit.
     *
     * @param ui the UI whose queue to drain
     * @return the locations passed to a history call, in order, possibly empty
     */
    private static List<String> addressesSentTo(UI ui) {
        StateTree tree = (StateTree) ui.getElement().getNode().getOwner();
        tree.runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("pushState")
                        || invocation.getExpression().contains("replaceState")
                        || invocation.getExpression().contains("vaadin-navigate"))
                .flatMap(invocation -> invocation.getParameters().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /**
     * A conversation list that has never heard of anything, and never will.
     *
     * <p>Not a convenience: it is the state the running application is in between a turn being
     * written and the store the column reads catching up with it, which is the window the address
     * defect lived in.
     */
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

        /** Every question this agent has been put, in order, for the tests that count them. */
        private final List<String> asked = new ArrayList<>();

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("The streaming form is the one under test");
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            this.listener = listener;
            asked.add(question);
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

        /**
         * Delivers one token into the open turn, leaving it open.
         *
         * <p>Needed by the tests about a page that goes away mid-answer: what has to survive is the
         * part of the answer that had already arrived, and a turn that never received a token
         * cannot tell a flush that works from one that does nothing.
         *
         * @param token the fragment the provider has just produced
         */
        void emit(String token) {
            open().onToken(token);
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
     * An agent that answers inside the call, the way a real provider does.
     *
     * <p>{@link ControllableAgent} returns from {@code respondTo} before the answer exists, which is
     * useful for holding a turn open and wrong for anything asserting what happens when a response
     * finishes: the contract {@link ChatView} is written against is that {@code respondTo} does not
     * return until the whole response is complete.
     */
    private static final class BlockingAgent implements Agent {

        private final List<String> tokens;

        private BlockingAgent(String... tokens) {
            this.tokens = List.of(tokens);
        }

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("The streaming form is the one under test");
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            tokens.forEach(listener::onToken);
            listener.onComplete();
            return String.join("", tokens);
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }
    }

    /**
     * A UI that takes what a worker hands it and never runs any of it.
     *
     * <p>This is what a UI becomes when its browser goes away: {@code access} still accepts the
     * command, because Vaadin has no reason yet to believe the tab is gone, and the command waits
     * for a client response that is never coming. Anything a turn does only inside {@code access}
     * therefore does not happen at all - which is the whole of the defect the reload tests are
     * about.
     */
    private static final class DeafUi extends UI {

        @Override
        public Future<Void> access(Command command) {
            return CompletableFuture.completedFuture(null);
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
        public Optional<TranscriptTurn> appendFailure(String id, FailureNotice notice) {
            if (notice == null) {
                return Optional.empty();
            }
            TranscriptTurn turn = new TranscriptTurn(mint(), ConversationMessage.Role.ASSISTANT,
                    notice.sentence(), TranscriptTurn.Outcome.FAILED);
            byConversation.computeIfAbsent(id, key -> new ArrayList<>()).add(turn);
            return Optional.of(turn);
        }

        /** What this store holds for one conversation, as text, for the tests that count turns. */
        List<String> textsOf(String id) {
            return byConversation.getOrDefault(id, List.of()).stream().map(TranscriptTurn::text).toList();
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
