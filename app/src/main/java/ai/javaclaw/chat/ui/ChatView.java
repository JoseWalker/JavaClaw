package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming chat view, mounted alongside the existing htmx UI rather than replacing it.
 *
 * <p>This is the vertical slice that proves the three things the rest of the UI depends on: that
 * Vaadin boots inside this application, that a response can be streamed token by token into a
 * server-side view, and that none of it requires a frontend build.
 *
 * <p>The markup is deliberately plain: a scrolling transcript and a composer, each a {@link Div}
 * carrying a class name. All appearance lives in {@code styles/javaclaw.css}, so the design system
 * can be changed without touching Java and no style is inlined here.
 *
 * <p>The bar above the transcript is not this view's: {@link MainLayout} owns the shell, including
 * the conversation title and the theme toggle, so every routed view gets the same one.
 *
 * <h2>One view, many conversations</h2>
 *
 * <p>The view is bound to a conversation rather than being one. The shell decides which - it owns
 * the sidebar, and the sidebar is where a conversation is chosen - and hands it over through
 * {@link ConversationHost}. Everything below the binding follows from it: the transcript is redrawn
 * from the store on every switch, and the next question is asked of whichever conversation is
 * currently bound, so the title above the messages and the messages themselves cannot disagree.
 *
 * <h2>Two addresses, one view</h2>
 *
 * <p>The empty route is where a reader arrives with no conversation in mind, and
 * {@link ConversationAddress#ROUTE} is where a particular conversation lives. Both resolve to this
 * view rather than to two, because they are the same screen showing different content: the second is
 * the first with the reader's place written down. Which conversation a navigation named is read by
 * the shell, not here - see {@link MainLayout}, which owns the binding and hands it over through
 * {@link ConversationHost} exactly as it does for a click on a row.
 *
 * <h2>What survives leaving the page</h2>
 *
 * <p>The address is half of it and the unsent draft is the other half. A reload rebuilds this view
 * from nothing, so a half-written question that lived only in the composer's value went with the
 * page - and switching conversations threw it away just as completely, several times an hour rather
 * than once. Both are answered by {@link ComposerDrafts}, which keeps the text in the session for the
 * conversation it was written for; the composer is filled from it on attach and on every switch, and
 * emptied by sending, so a reload cannot hand a reader back a question they have already asked.
 *
 * <h2>Which store</h2>
 *
 * <p>{@link ConversationTranscripts}, not the chat memory the agent reads. The two are different
 * records of the same conversation and only one of them is complete: the agent's memory is a
 * twenty-message window that is written back over the file it came from, so a long conversation
 * loses its opening exchanges from disk. This view draws the transcript and lets the memory be what
 * it is. Everything the reader can do to a turn - answering it again, asking it differently - goes
 * through that store, which is also what keeps the model's window from disagreeing with what is on
 * screen.
 */
@Route(value = "", layout = MainLayout.class)
@RouteAlias(value = ConversationAddress.ROUTE, layout = MainLayout.class)
@PageTitle("JavaClaw")
public class ChatView extends Div implements ConversationHost {

    /**
     * The questions the empty state offers.
     *
     * <p>Deliberately about what this agent is - a JVM assistant with file and shell tools in reach
     * - rather than the generic three every chat product ships. They are also deliberately fixed:
     * a suggestion derived from the reader's own history would be a claim about what they usually
     * do, and this application has one conversation list and no basis for that claim.
     */
    private static final List<String> STARTERS = List.of(
            "Explain virtual threads in two sentences",
            "Walk me through this stack trace",
            "What does this project's build do?");

    private static final String BUSY_HINT = "JavaClaw is responding";

    /** Names the log region, which was announced as "log" and nothing else before this existed. */
    private static final String TRANSCRIPT_LABEL = "Conversation transcript";

    /**
     * Asks the browser to say when it has actually drawn a frame.
     *
     * <p>Two nested animation frames rather than one, and the second is the one that carries the
     * proof. A {@code requestAnimationFrame} callback runs immediately <em>before</em> the paint it
     * is scheduled against, so answering from the first would be answering before the frame exists;
     * the second only runs once the first has been painted. That is the difference between telling
     * assistive technology the region was busy and it having been busy on screen.
     *
     * <p>Returned as a promise because Flow resolves one before sending the result back, which is
     * what turns "when the browser gets round to it" into a causal answer rather than a guess at a
     * delay.
     */
    private static final String PAINTED_FRAME = """
            return new Promise(painted =>
                    requestAnimationFrame(() => requestAnimationFrame(() => painted(true))));
            """;

    /**
     * What the polite announcer says at each end of a turn.
     *
     * <p>Short on purpose. This is read out over whatever the reader is doing, so it says what
     * changed and stops; the answer itself is in the transcript, which is where a reader who wants
     * it will go looking.
     */
    private static final String ANSWERING = "Answering";

    /**
     * What the announcer says about a conversation arriving or being started.
     *
     * <p>The transcript is a log, and a log announces additions. Replacing one conversation with
     * another, or emptying the transcript for a new chat, is therefore inaudible: the reader gets
     * silence, or four thousand characters of text with nothing to say where it came from. These
     * two lines are the only thing that names the change.
     */
    private static final String OPENED = "Opened ";
    private static final String NEW_CHAT = "New chat";
    private static final String UNNAMED_CONVERSATION = "an unnamed conversation";
    private static final String ANSWER_COMPLETE = "Answer complete";
    private static final String ANSWER_STOPPED = "Answer stopped";
    private static final String ANSWER_FAILED = "Answer failed";
    private static final String SEND_LABEL = "Send message";
    private static final String STOP_LABEL = "Stop responding";
    private static final String JUMP_LABEL = "Jump to the latest message";

    /**
     * Said when a rewind was refused. Deliberately about the conversation rather than about a file:
     * what the reader needs to know is that the turns they can still see are the turns that count.
     */
    private static final String REWIND_REFUSED =
            "That message could not be re-asked, so the conversation is unchanged.";

    /**
     * Said when Enter is pressed on a draft while an answer is still arriving.
     *
     * <p>Three claims, and the sentence is wrong without any of them. That nothing was sent, so the
     * reader is not left wondering whether a question is in flight. That the draft is kept, because
     * the fear a refusal creates is that the text is gone. And that sending it is theirs to do when
     * the answer ends - which rules out the reading this application must not invite, that the
     * message is queued and will leave on its own into a conversation that has moved on since.
     */
    private static final String STILL_RESPONDING =
            "JavaClaw is still responding, so nothing was sent. Your message is kept - send it when "
                    + "the answer ends.";

    /**
     * Said under the composer while the browser has no connection.
     *
     * <p>Deliberately not "your message will be sent when the connection returns". Nothing here
     * queues anything, for the same reason {@link #STILL_RESPONDING} refuses to: a message that
     * leaves on its own, at a moment the reader did not choose, is the dishonesty this composer is
     * built to avoid. What is true is that the draft is safe and that sending it stays theirs to do.
     */
    private static final String OFFLINE_HINT =
            "Offline - JavaClaw cannot be reached. Your message is kept; send it when the connection "
                    + "returns.";

    /**
     * When a keystroke in the composer is this view's to answer rather than the textarea's.
     *
     * <p>Three conditions and the field is broken without any of them. Enter, because that is the
     * send key. Not Shift, because Shift + Enter is how a message spans several lines and that
     * newline has to reach the textarea. Not while an input method is composing, because Enter is
     * how a candidate is committed in every IME - swallowing it there would make the composer unable
     * to accept text in most of the world's scripts.
     */
    private static final String COMPOSER_ENTER =
            "event.key === 'Enter' && !event.shiftKey && !event.isComposing";

    /**
     * Stops the browser doing anything of its own with the keystroke this view has just answered.
     *
     * <p>This is the half of a refusal the server cannot perform. The default action of Enter in a
     * textarea is to insert a newline, and it happens in the browser during the dispatch - so a
     * refusal decided on the server arrives after the draft has already been edited. An audit
     * measured exactly that: Enter mid-stream sent nothing, said so in a 1x1 off-screen region, and
     * left a stray blank line in the reader's own text. The only visible consequence of the
     * keystroke was the one thing that should not have happened.
     *
     * <p>The condition is repeated inside the expression rather than left to the registration's
     * filter, so the guard travels with the call it guards. Whether Flow evaluates event data before
     * or after the filter is then not something this file depends on - and Shift + Enter keeps its
     * newline either way.
     */
    private static final String SWALLOW_COMPOSER_ENTER =
            "(" + COMPOSER_ENTER + ") && event.preventDefault()";

    private final transient Agent agent;
    private final transient ExecutorService responseExecutor;
    private final transient ConversationTranscripts transcripts;
    private final transient ConversationActivity activity;
    private final transient MessageTurn.Actions turnActions = new TurnControls();

    private final Div transcript = new Div();
    private final Div transcriptInner = new Div();
    private final NativeButton jump = new NativeButton();
    private final Div emptyState = createEmptyState();
    private final Div composer = new Div();
    private final Div composerField = new Div();
    private final Span composerHint = new Span();

    /**
     * The line the composer shows instead of its hint while the browser has no connection.
     *
     * <p>A separate element rather than a different value in {@link #composerHint}, because the two
     * are decided by different parties: the hint is whatever the server last had cause to say, and
     * this is true exactly when the server cannot be asked. Writing one over the other would make
     * the visible line depend on a round trip that, in this state, is the thing that does not
     * happen.
     */
    private final Span composerOffline = new Span();

    /**
     * The one live region that is not the transcript.
     *
     * <p>It exists because the transcript cannot say the things that are not text in it: that a turn
     * has begun, that it was stopped, that a rewind was refused. Those used to be said by a shape
     * changing inside a button and by a line under the composer that was made a live region at the
     * moment it was written to - which is the one moment it cannot work, because assistive
     * technology only reports changes to regions that already existed when the change happened.
     *
     * <p>Present from the first paint and never removed, therefore, and off-screen: it duplicates
     * nothing a sighted reader is not already being shown.
     */
    private final Span announcer = new Span();
    private final TextArea prompt = new TextArea();
    private final Button send = new Button();
    private final Span sendGlyph = new Span();

    /**
     * The conversation this view is currently showing and asking questions of.
     *
     * <p>Starts on a chat of its own rather than on the shared one. A view that defaulted into an
     * existing conversation would append the reader's first question to a thread they never chose to
     * continue, and it would do so before the shell has had a chance to say otherwise. The shell
     * replaces this the moment it hosts the view; a view outside one simply starts a new chat.
     */
    private String conversationId = ConversationIds.newConversationId();

    /** True from the moment a question is sent until its answer completes, fails or is stopped. */
    private boolean streaming;

    /**
     * Whether the shell has been told what this conversation is about. The first question is the
     * conversation's name everywhere else in this UI; asking a second one does not rename it.
     */
    private boolean titleAnnounced;

    /**
     * Cancellation flag for the turn currently streaming, shared with the worker thread. Set on the
     * UI thread when the reader stops a generation; every pending update checks it before touching
     * the transcript, so a token that was already in flight cannot reopen a closed turn.
     */
    private transient AtomicBoolean activeTurn;

    /**
     * Guards the one writing-down of the streaming turn's answer.
     *
     * <p>Three parties race for it - the completion as the UI thread sees it, the same completion as
     * the worker that produced it sees it, and the reader stopping or leaving - and the flag is what
     * makes the transcript hold one copy of the answer rather than three. See {@link #fileAnswer}.
     */
    private transient AtomicBoolean activeFiled;

    /**
     * Where the store's handle on the streaming turn's answer is left, for whoever files second.
     *
     * <p>The worker can write the answer down but cannot hand the turn on screen its identity, which
     * is what makes an answer re-askable. So it leaves the record here and the UI thread picks it up
     * when it arrives - or does not, on a page that has gone away, where there is no turn on screen
     * for an identity to mean anything to.
     */
    private transient AtomicReference<TranscriptTurn> activeRecord;

    private transient Future<?> activeResponse;
    private transient MessageTurn activeAnswer;

    /**
     * The conversation the streaming turn was asked of, captured when it was asked.
     *
     * <p>{@link #conversationId} moves the moment the reader picks another row, and a turn that is
     * being closed down has to be filed under the conversation it belongs to rather than the one
     * they moved to.
     */
    private transient String activeAskedOf;

    private boolean scrollPinInstalled;

    /** Both scripts survive a re-attach, so neither is installed twice on the same element. */
    private boolean copyInstalled;

    /**
     * Numbers the loads, so a quiet window can only be closed by the load that opened it.
     *
     * <p>Two rows clicked in quick succession put two frame confirmations in flight, and they come
     * back in whatever order the browser answers in. Without a number to check against, the older
     * one would unmute the region in the middle of the newer one's rebuild - the defect this
     * mechanism exists to prevent, reintroduced by its own fix.
     *
     * <p>Never reset and never compared for order, only for equality with the load that is current,
     * so wrapping after nine quintillion conversation switches would still be harmless.
     */
    private long transcriptLoad;

    /**
     * True while a load has muted the transcript and is still waiting on the browser to confirm it
     * has drawn a frame.
     *
     * <p>Kept so {@link #onAttach} can ask again. A view detached mid-load would otherwise come
     * back muted with nothing left in flight to release it, and a transcript that never speaks
     * again is worse than one that speaks too much.
     */
    private boolean transcriptLoadPending;

    public ChatView(Agent agent,
                    ExecutorService responseExecutor,
                    ConversationTranscripts transcripts,
                    ConversationActivity activity) {
        this.agent = agent;
        this.responseExecutor = responseExecutor;
        this.transcripts = transcripts;
        this.activity = activity;

        addClassName("jc-app");
        setSizeFull();
        add(createTranscript(), createComposer());
        setStreaming(false);
        showStoredTurns();
    }

    // ------------------------------------------------------------------ layout

    private Component createTranscript() {
        transcriptInner.addClassNames("jc-transcript__inner", "jc-measure");
        transcriptInner.add(emptyState);

        transcript.addClassName("jc-transcript");
        // Announced as a log so a screen reader reads streamed tokens without stealing focus.
        transcript.getElement().setAttribute("role", "log");
        // Written out although role="log" already implies it, and deliberately kept when a review
        // asked for its removal. Removing it is not a fix for anything: ARIA gives role="log" an
        // implicit aria-live of "polite", so a conforming screen reader behaves identically with the
        // attribute absent, and the only implementations the line can still help are the ones that
        // do not resolve implicit live values from the role. What actually governs how much of a
        // streamed answer is read out is aria-busy - see markTranscriptBusy, which mutes the region
        // for the whole of a turn and for the whole of a load - and how much churn the answer makes
        // on its way in, which is MessageTurn's showTail.
        transcript.getElement().setAttribute("aria-live", "polite");
        // A live region with no name is a region nobody can choose to jump to, because there is
        // nothing in the landmark list to choose.
        transcript.getElement().setAttribute("aria-label", TRANSCRIPT_LABEL);
        // A tab stop, because this is the element that scrolls. The skip link lands on the content
        // landmark, and from there none of PageDown, ArrowDown, End or Space moved a container with
        // fourteen hundred pixels of overflow in it - the keys were going to a document that does
        // not scroll while the conversation sat in a box that does. A scrollable region has to be
        // reachable by keyboard for the same reason a scrollbar has to be reachable by pointer.
        // It is safe as a tab stop precisely because it is already a named region rather than an
        // anonymous div: a reader who lands here is told what they have landed on.
        transcript.getElement().setAttribute("tabindex", "0");
        // The transcript starts at the bottom and stays there until a gesture says otherwise. The
        // attribute is what the stylesheet reads to show the way back; the client owns its value.
        transcript.getElement().setAttribute("data-pinned", "true");
        transcript.add(transcriptInner, createJumpControl());
        return transcript;
    }

    /**
     * The way back to the newest message, for a reader who has scrolled away from it.
     *
     * <p>Auto-scroll and scrollback are in direct conflict: following the answer is what a reader
     * wants nine times out of ten, and the tenth time they have deliberately scrolled up to read
     * something and being dragged back is intolerable. Unpinning solves the tenth case and creates
     * a new one - the answer keeps growing somewhere below the fold with nothing to say so. This
     * control is that something, and it is the only thing on the page that appears because of where
     * the reader is rather than because of what the agent said.
     *
     * <p>It is drawn once and shown by the stylesheet, so appearing and disappearing costs no
     * server round trip on a container that is being scrolled.
     */
    private Component createJumpControl() {
        jump.addClassName("jc-jump__button");
        jump.getElement().setAttribute("type", "button");
        jump.getElement().setAttribute("aria-label", JUMP_LABEL);
        jump.getElement().setAttribute("title", JUMP_LABEL);
        Span glyph = Icons.glyph(Icons.ARROW_DOWN);
        glyph.addClassName("jc-jump__icon");
        jump.add(glyph);

        Div anchor = new Div(jump);
        anchor.addClassName("jc-jump");
        return anchor;
    }

    /**
     * What fills the screen before there is a conversation on it.
     *
     * <p>The composer is anchored at the foot of the shell and stays there when the first message
     * lands, which is the right trade - a primary control that relocates the moment it is used is a
     * control the reader has to find twice - but it left a first-fixation-to-composer distance of
     * 361px on a desktop and 471px on a tablet, over a void doing no work at all. The starters are
     * what that space is for. They are suggestions, not data: nothing here claims the agent has
     * done any of it, which is the difference between this and the invented conversation list the
     * sidebar used to carry.
     */
    private Div createEmptyState() {
        Span mark = brandMark("jc-mark--l");

        // A heading, because it is one. It is the only thing on an empty screen that says what the
        // screen is for, and as a span it was invisible to a reader navigating by heading - who
        // would have been told the page had exactly one, two hundred pixels above, naming a
        // conversation that has nothing in it.
        H2 title = new H2("Ask JavaClaw anything");
        title.addClassName("jc-empty__title");

        Span hint = new Span("Answers stream in token by token, straight from the agent.");
        hint.addClassName("jc-empty__hint");

        Div empty = new Div(mark, title, hint, createStarters());
        empty.addClassName("jc-empty");
        return empty;
    }

    /**
     * Three ways in, at the width of the question rather than the width of the column.
     *
     * <p>Three rather than six: a grid of suggestions is a menu to be read, and a reader who has to
     * read a menu before typing has been given a second task. Each one is a whole question, so the
     * first thing sent is something the agent can answer rather than a fragment to be clarified.
     */
    private Component createStarters() {
        Div starters = new Div();
        starters.addClassName("jc-starters");
        // Named as a group rather than declared a list. The list role was worse than nothing: its
        // items were buttons, and role="listitem" does not describe a button, it replaces it - so
        // all three suggestions were announced as lines of text the reader was never told they
        // could press. A named group says what they are and leaves them pressable.
        starters.getElement().setAttribute("role", "group");
        starters.getElement().setAttribute("aria-label", "Suggested questions");
        STARTERS.forEach(text -> starters.add(starter(text)));
        return starters;
    }

    /**
     * One starter.
     *
     * <p>It fills the composer and puts the caret in it; it does not send. The reader has not agreed
     * to the question yet, a click that silently spends a model call is a click nobody can take
     * back, and half the value of a suggestion is that it can be edited before it is asked.
     */
    private Component starter(String text) {
        NativeButton button = new NativeButton(text, event -> {
            prompt.setValue(text);
            prompt.focus();
        });
        button.addClassName("jc-starter");
        return button;
    }

    /**
     * The one brand mark, at one of its two sizes. Fill, ink and letterform live in the stylesheet
     * so the logo cannot end up rendered two different ways on the same screen.
     */
    private static Span brandMark(String sizeModifier) {
        Span mark = new Span("JC");
        mark.addClassNames("jc-mark", sizeModifier);
        mark.getElement().setAttribute("aria-hidden", "true");
        return mark;
    }

    private Component createComposer() {
        prompt.setPlaceholder("Ask anything");
        prompt.setAriaLabel("Message");
        // Rests at one line and grows with the draft. Vaadin's default of two rows makes an empty
        // composer look like it is already holding text; the stylesheet caps the growth at
        // min(40vh, 12rem) so a long draft cannot eat the transcript on a phone.
        prompt.setMinRows(1);
        prompt.setMaxRows(8);
        // Without this the draft only reaches the server when the field loses focus, so Enter would
        // submit whatever was in it one blur ago - in practice, nothing at all. It is also what
        // keeps the send button's enabled state in step with the field.
        prompt.setValueChangeMode(ValueChangeMode.EAGER);
        prompt.addValueChangeListener(event -> {
            updateSendEnabled();
            // Filed on every change rather than on blur or on a timer, because the events this has
            // to survive - a reload, a crashed tab, a click on another row - give no warning and
            // leave nothing to flush. The value is already on the server for the send button's sake,
            // so this costs one map write on top of an exchange that was happening anyway.
            rememberDraft();
        });
        // Registered on the element rather than as a key listener on the component, and the
        // difference is the whole of what a reader gets out of it: a DOM registration can carry an
        // expression the browser evaluates, which is the only place the keystroke's default can be
        // stopped. A component-level listener hears the key and cannot prevent anything, so the
        // newline had already been inserted into the draft by the time the refusal was decided.
        prompt.getElement().addEventListener("keydown", event -> onComposerEnter())
                .setFilter(COMPOSER_ENTER)
                .addEventData(SWALLOW_COMPOSER_ENTER);

        send.addClassName("jc-send");
        send.setDisableOnClick(false);
        sendGlyph.getElement().setAttribute("aria-hidden", "true");
        send.setIcon(sendGlyph);
        // One button, two jobs: send what is in the field, or stop what is already running.
        send.addClickListener(event -> {
            if (streaming) {
                stopStreaming();
            } else {
                submit();
            }
        });

        composerField.addClassName("jc-composer__field");
        composerField.add(prompt, send);

        composerHint.addClassName("jc-composer__hint");

        // Drawn once and shown by the stylesheet, because the state it describes is the one state in
        // which the server cannot draw anything: by the time it is true, nothing this side of the
        // browser can be reached. It replaces the hint rather than joining it - see the sheet - so
        // the line that normally advertises Enter stops advertising a key that will not work.
        composerOffline.setText(OFFLINE_HINT);
        composerOffline.addClassName("jc-composer__offline");

        announcer.addClassNames("jc-sr-only", "jc-announcer");
        announcer.getElement().setAttribute("role", "status");
        announcer.getElement().setAttribute("aria-live", "polite");
        // The whole sentence every time, not the words that changed: these are short statements
        // that only mean anything read together.
        announcer.getElement().setAttribute("aria-atomic", "true");

        Div inner = new Div(composerField, composerHint, composerOffline, announcer);
        inner.addClassNames("jc-composer__inner", "jc-measure");

        composer.addClassName("jc-composer");
        composer.add(inner);
        return composer;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        installTranscriptScrollPin();
        installTranscriptCopy();
        // A load that muted the transcript while there was no browser to hear the question has no
        // answer coming. Asking again on attach is what stops a re-attached view from arriving
        // muted and staying that way for the rest of the session.
        if (transcriptLoadPending) {
            confirmPaintedFrame(transcriptLoad);
        }
        // The other half of surviving a reload. A reload builds a new view and a new UI over the one
        // session that outlived the page, and the shell may bind this view to its conversation either
        // side of the attach - openConversation is documented as being callable before there is a UI.
        // Asking here as well is what makes the composer right whichever order they run in.
        restoreDraft();
        // The composer belongs to this view, so the keystroke that reaches it is registered here
        // rather than in the shell, which has no business knowing there is a field to reach.
        Shortcut.FOCUS_COMPOSER.register(this, prompt::focus);
        // Escape stops the answer from wherever the reader is standing. It used to be scoped with
        // listenOn(this), which reads like a precaution and is really a reachability bug: the
        // keystroke only fired for a target inside this view's subtree, and the skip link lands
        // the reader on main.jc-main__content, which is not. The keyboard-primary path was
        // therefore the one path on which the stop key did nothing - measured at 2838 further
        // characters streaming in after Escape while the announcer still said "Answering".
        //
        // What the scoping was buying is bought properly below instead: the handler declines when
        // no turn is running and when an overlay is on top, so Escape-to-close-a-dialog cannot
        // also kill a generation behind it. Propagation and the browser default are left alone for
        // the same reason the shell leaves them alone on its own Escape - an overlay that sees
        // either one stops closing.
        Shortcut.ESCAPE.register(this, this::stopFromKeystroke).forEach(registration -> {
            registration.setEventPropagationAllowed(true);
            registration.setBrowserDefaultAllowed(true);
        });
    }

    /**
     * The page is going away with a turn still running: keep what arrived.
     *
     * <p>This is the whole of the fix for the worst thing this view did. An answer was written to
     * the store only from the completion and the failure callbacks, so a reload, a closed tab or a
     * navigation part-way through one dropped the in-flight turn entirely - a blind review measured
     * 303 characters of an answer on screen, reloaded, and came back to the question sitting alone
     * with no answer under it, no marker saying anything had gone wrong and nothing to retry. A
     * conversation that appears to have been ignored is worse than one that reports an error,
     * because there is nothing in it to act on.
     *
     * <p>What runs is the flush the Stop control already performs, and deliberately not a second
     * implementation of it: the reader who stopped an answer and the reader whose page went away are
     * both left holding half a turn, and half a turn is a turn. The parts of stopping that speak to
     * a reader - the announcement, the caret going back to the composer - are not run here, because
     * there is no longer anyone on the other end of them.
     *
     * <p>Detaching for any other reason is treated the same way, which is the conservative
     * direction: the cost of closing a turn whose view comes back is one answer the reader has to
     * ask for again, and the cost of not closing it is an answer streaming into a component tree
     * nobody can see.
     *
     * <p>Must run on the Vaadin UI thread; Vaadin only calls it there.
     *
     * @param detachEvent the detach being handled
     */
    @Override
    protected void onDetach(DetachEvent detachEvent) {
        closeActiveTurn();
        super.onDetach(detachEvent);
    }

    /**
     * Escape, as a page-wide keystroke rather than as this view's private one.
     *
     * <p>Two refusals, and the shortcut is wrong without either. A keystroke with no generation
     * running must be a no-op, because Escape now reaches this handler from every field and every
     * corner of the shell - a handler that acted anyway would be clearing state it does not own.
     * And a keystroke while something is open on top belongs to the thing on top: closing a dialog
     * is what the reader asked for, and taking their answer down with it is not.
     *
     * <p>Overlay-open is asked of the component tree rather than inferred from where the event was
     * delivered. The previous implementation inferred it, and paid for the inference with the
     * whole keyboard-primary path.
     *
     * <p>Must run on the Vaadin UI thread; it is a shortcut command and Vaadin only calls it there.
     */
    private void stopFromKeystroke() {
        if (!streaming || anOverlayIsOpen()) {
            return;
        }
        stopStreaming();
    }

    /**
     * Whether a dialog or a menu is currently open over the page.
     *
     * <p>Both are teleported out of this view's subtree when they open, so neither can be found by
     * walking down from here; they are children of the UI. Anything this walk does not recognise
     * is treated as "no overlay", which is the safe direction: the cost of missing one is that a
     * generation the reader had probably forgotten about is stopped, and the cost of inventing one
     * is a stop key that does nothing.
     *
     * @return true when something modal or menu-like is on top of the page
     */
    private boolean anOverlayIsOpen() {
        return getUI().map(ui -> ui.getChildren().anyMatch(ChatView::isOpenOverlay)).orElse(false);
    }

    private static boolean isOpenOverlay(Component candidate) {
        if (candidate instanceof Dialog dialog) {
            return dialog.isOpened();
        }
        return candidate instanceof Popover popover && popover.isOpened();
    }

    /**
     * Keeps the newest content in view while it arrives, and stops the moment the reader says so.
     *
     * <p>Two things push the last message out of sight and neither of them is a scroll. A draft
     * that wraps onto more lines makes the composer taller and the transcript shorter in the same
     * frame; an answer streaming in makes the transcript's content taller several times a second.
     * Both are observed here, and both re-pin the transcript only when it was already at the
     * bottom.
     *
     * <p>Whether it is at the bottom is a state this script owns, not something recomputed per
     * frame, and only a gesture may clear it: the transcript scrolls itself after every turn, and
     * the frames of that are not a reader deciding to look away. Once cleared, nothing on the
     * server side moves the transcript again - the pinning stops, the jump control appears, and the
     * reader stays exactly where they put themselves for as long as they want to.
     */
    private void installTranscriptScrollPin() {
        if (scrollPinInstalled) {
            return;
        }
        scrollPinInstalled = true;
        transcript.getElement().executeJs("""
                const transcript = this;
                const atBottom = () =>
                    transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 8;
                let pinned = true;
                let byHand = false;
                const mark = () => transcript.setAttribute('data-pinned', pinned ? 'true' : 'false');
                // Only a gesture may unpin. The transcript scrolls itself smoothly after every turn,
                // and the frames of that animation are not a reader deciding to look away.
                for (const gesture of ['wheel', 'touchmove', 'keydown']) {
                    transcript.addEventListener(gesture, () => byHand = true, { passive: true });
                }
                transcript.addEventListener('scroll', () => {
                    if (atBottom()) {
                        pinned = true;
                        byHand = false;
                    } else if (byHand) {
                        pinned = false;
                    }
                    mark();
                });
                const observer = new ResizeObserver(() => {
                    if (pinned) {
                        transcript.scrollTo({ top: transcript.scrollHeight, behavior: 'instant' });
                    }
                });
                // $0 is the composer growing under the transcript, $1 the answer growing inside it.
                observer.observe($0);
                observer.observe($1);
                $2.addEventListener('click', () => {
                    pinned = true;
                    byHand = false;
                    mark();
                    transcript.scrollTo({ top: transcript.scrollHeight, behavior: 'smooth' });
                });
                mark();
                """, composer.getElement(), transcriptInner.getElement(), jump.getElement());
    }

    /**
     * Installs the one listener that answers every copy control in the conversation.
     *
     * <p>Copying is the one action on this page that needs nothing from the server: the text is
     * already in the browser, and a round trip to fetch what is on screen would be latency spent to
     * learn something the page already knows. It is delegated from the transcript rather than bound
     * per control because the alternative scales with the conversation - a script per message and
     * per code block, reinstalled on every redraw - and because a code block's control is markup
     * built by {@link Markdown}, which has no component to hang a listener on.
     *
     * <p>Deny by default, twice over. The listener acts only on an element that marks itself with
     * {@code data-jc-copy}, and only for the two sources named below; anything else - including an
     * element that arrived with the attribute but nothing to copy - is ignored rather than guessed
     * at. The confirmation is set inside the promise's success branch, so a browser that refuses
     * the clipboard leaves the control saying what it still means rather than claiming a copy that
     * did not happen.
     */
    private void installTranscriptCopy() {
        if (copyInstalled) {
            return;
        }
        copyInstalled = true;
        transcript.getElement().executeJs("""
                const transcript = this;
                const codeOf = (trigger) => {
                    const block = trigger.closest('.jc-code');
                    if (!block) {
                        return null;
                    }
                    // Lines are elements rather than newlines, so the snippet is rebuilt by joining
                    // them. The line numbers live in generated content and are not part of this.
                    const lines = [...block.querySelectorAll('.jc-code__line')];
                    if (lines.length) {
                        return lines.map(line => line.textContent).join('\\n');
                    }
                    const code = block.querySelector('code');
                    return code ? code.textContent : null;
                };
                transcript.addEventListener('click', (event) => {
                    const trigger = event.target.closest('[data-jc-copy]');
                    if (!trigger || !transcript.contains(trigger)) {
                        return;
                    }
                    const source = trigger.getAttribute('data-jc-copy');
                    let text = null;
                    if (source === 'code') {
                        text = codeOf(trigger);
                    } else if (source === 'message' && typeof trigger.jcCopySource === 'string') {
                        text = trigger.jcCopySource;
                    }
                    if (text === null || !navigator.clipboard || !window.isSecureContext) {
                        return;
                    }
                    navigator.clipboard.writeText(text).then(() => {
                        trigger.setAttribute('data-copied', 'true');
                        setTimeout(() => trigger.removeAttribute('data-copied'), 1600);
                    }, () => {});
                });
                """);
    }

    // ---------------------------------------------------------------- conversation

    /**
     * Shows the conversation the sidebar just handed over.
     *
     * <p>Three things have to happen together, and in this order. A turn still streaming belongs to
     * the conversation being left, so it is stopped first - otherwise its remaining tokens would
     * land in a transcript they were not written for. The binding is then moved, which is what makes
     * the next question go to the right place. Only then is the transcript redrawn, wholesale rather
     * than diffed: two conversations share no turns, so there is nothing to keep.
     *
     * <p>An id that is not of the expected shape is refused outright and the view stays where it
     * was. A reader looking at the conversation they had a moment ago is a recoverable
     * disappointment; a blank transcript under someone else's title is not.
     */
    @Override
    public void openConversation(ConversationSummary conversation) {
        if (conversation == null || !ConversationIds.isWellFormed(conversation.id())) {
            return;
        }
        if (conversation.id().equals(conversationId)) {
            return;
        }
        stopStreaming();
        // Filed while the view is still bound to the conversation being left, so the draft goes to
        // the thread it was written for rather than to the one arriving.
        rememberDraft();
        conversationId = conversation.id();
        // Not a clear. Emptying the composer here is what used to destroy an unsent question every
        // time a reader looked something up in another thread, and the empty field it left said
        // nothing about what had been in it. What belongs in the composer is whatever this
        // conversation was last left holding, which for a conversation never typed into is nothing.
        restoreDraft();
        showStoredTurns();
        // Said because the transcript cannot say it. A log region announces what is added to it, so
        // replacing a conversation with another one is - to anything listening rather than looking
        // - a silent event followed by four thousand characters of unexplained text. This names the
        // thread that just arrived, once, and stops.
        announce(OPENED + displayName(conversation));
    }

    /**
     * The reader has just asked for a new chat: put them in it.
     *
     * <p>Only ever reached from the explicit act, never from navigation or from a row being
     * clicked. Choosing a conversation must leave the caret in the list the reader is browsing;
     * asking for a new chat must not leave it on the document body in silence, which is what
     * emptying a log region does when nothing else happens.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    @Override
    public void enterNewConversation() {
        announce(NEW_CHAT);
        prompt.focus();
    }

    /**
     * What to call a conversation out loud.
     *
     * @param conversation the conversation being opened
     * @return its title, or a stable phrase when it has none - an announcement that trails off
     *         after "Opened" is worse than one that admits the thread is not named yet
     */
    private static String displayName(ConversationSummary conversation) {
        String title = conversation.title();
        return title == null || title.isBlank() ? UNNAMED_CONVERSATION : title.strip();
    }

    /**
     * Files whatever is in the composer under the conversation it was written for.
     *
     * <p>Reached from the field's own value-change listener and once more just before the binding
     * moves, which is the only moment the two can disagree: the listener fires while the view is
     * still on the old conversation, and a switch has to file the draft before it changes what
     * {@link #conversationId} means.
     *
     * <p>Does nothing outside a UI. A view that is not attached has no session to write to, and the
     * only thing it could do instead is invent somewhere to put a reader's text.
     *
     * <p>Must run on the Vaadin UI thread; the session lock is held there and nowhere else.
     */
    private void rememberDraft() {
        getUI().map(UI::getSession)
                .ifPresent(session -> ComposerDrafts.remember(session, conversationId, prompt.getValue()));
    }

    /**
     * Puts back whatever the bound conversation was last left holding.
     *
     * <p>Always assigns, including the empty string, because this replaces the unconditional clear
     * that used to run on every switch: a conversation with no draft has to empty the composer, or
     * the previous thread's text would be sitting in it under the new thread's title, where it can be
     * sent by accident.
     *
     * <p>Assigning fires the field's own listener, which files the value again under the conversation
     * now bound. That is a write of the value that was just read and is deliberately not guarded
     * against - the alternative is a re-entrancy flag whose only job is to save one map write.
     *
     * <p>Outside a UI there is no session to read, and the composer is emptied rather than left as it
     * was. {@link #onAttach} asks again the moment there is one, which is the order navigation
     * actually runs in.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void restoreDraft() {
        prompt.setValue(getUI().map(UI::getSession)
                .map(session -> ComposerDrafts.recall(session, conversationId))
                .orElse(""));
    }

    /** The conversation currently bound, for the shell that chose it. */
    String conversationId() {
        return conversationId;
    }

    /**
     * Redraws the transcript from what the store holds for the bound conversation.
     *
     * <p>The region is muted before the store is read rather than after. Reading is part of the
     * load, and a quiet window that opens only once the turns are in hand is a quiet window that
     * has already missed the moment it exists for on any store that answers slowly.
     */
    private void showStoredTurns() {
        markTranscriptBusy(true);
        paintTurns(transcripts.turns(conversationId));
        // Deliberately not unmuted here. See releaseWhenPainted: the muting and the release have to
        // reach the browser in two different responses, or the browser is told neither.
        releaseWhenPainted();
    }

    /**
     * Lets the transcript speak again, but only once the browser has drawn it muted.
     *
     * <p>This is the whole of the fix for a defect that looked like a missing call and was not one.
     * The region was muted and released around the rebuild, correctly, and a live audit still read
     * {@code aria-busy="false"} in the DOM for the entire load. The reason is that both calls
     * happened inside one server visit, and a visit carries one value per attribute - the last one
     * written. Setting it to true and back to false in the same visit does not send true and then
     * false; it sends nothing at all, because the attribute ends the visit on the value it started
     * on. So the response that pushed four thousand characters into a polite log region told the
     * browser nothing about them, and a screen reader read a whole conversation out because the
     * reader glanced at it.
     *
     * <p>Muting is therefore left standing when the load's response goes out, and the release is
     * put in the next one. The browser is asked to say when it has drawn a frame - two nested
     * animation frames, because the first callback runs <em>before</em> the next paint and only the
     * second is proof that one happened - and the release rides back on the visit that answer
     * arrives in. The transcript is on screen the moment the load lands, so this costs the reader
     * no latency at all; what it costs is one extra round trip that carries a single attribute.
     *
     * <p>An error answer releases too. A failed round trip should cost the reader the quiet window,
     * not the region: a transcript left muted for the rest of the session would silence every
     * answer after it, which is a worse failure than the one being prevented.
     *
     * <p>With no UI there is no browser to draw anything and no assistive technology to protect, so
     * the release happens immediately. That is the path the constructor takes, and the path a test
     * that never attaches the view takes.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void releaseWhenPainted() {
        long load = ++transcriptLoad;
        transcriptLoadPending = true;
        if (getUI().isEmpty()) {
            releaseAfter(load);
            return;
        }
        confirmPaintedFrame(load);
    }

    /**
     * Asks the browser to report back once it has painted, and releases the region when it does.
     *
     * <p>Split out from {@link #releaseWhenPainted()} because {@link #onAttach} has to be able to
     * ask again: a view detached while a load was still waiting would otherwise come back muted
     * with nothing left to release it, and a permanently silent transcript is exactly the outcome
     * this whole mechanism exists to avoid.
     *
     * <p>Must run on the Vaadin UI thread, and the view must be attached.
     *
     * @param load the load this confirmation belongs to
     */
    private void confirmPaintedFrame(long load) {
        transcript.getElement().executeJs(PAINTED_FRAME)
                .then(painted -> releaseAfter(load), failed -> releaseAfter(load));
    }

    /**
     * Closes the quiet window a load opened, if that load still owns it.
     *
     * <p>The guard is not defensive decoration. A reader who clicks two rows in quick succession
     * has two windows in flight, and the frame the first one was waiting for arrives while the
     * second is still loading - releasing on it would unmute the region in the middle of the
     * second rebuild, which is the original defect reintroduced by its own fix. Only the newest
     * load can close the window; an older one returns having done nothing, because the newer one
     * will close it when its own frame lands.
     *
     * <p>Released to whatever the streaming state says rather than flat to false, for the same
     * reason {@link #showTurns} does: a rewind redraws the transcript in the middle of a turn, and
     * clearing it flat would unmute the region for the rest of the answer.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param load the load that opened the window, as numbered when it opened it
     */
    private void releaseAfter(long load) {
        if (load != transcriptLoad) {
            return;
        }
        transcriptLoadPending = false;
        markTranscriptBusy(streaming);
    }

    /**
     * Paints a whole transcript, replacing whatever was on screen.
     *
     * <p>Every turn arrives closed, so an assistant answer read back from the store is rendered as
     * the markup it describes rather than as the asterisks it was stored as - the same treatment a
     * live answer gets the moment it completes, so a reloaded conversation is indistinguishable
     * from the one that was just streamed. Each turn is also given the store's handle on it, which
     * is what makes an answer read back from disk as re-askable as one that just arrived.
     *
     * @param stored the turns to draw, oldest first
     */
    private void showTurns(List<TranscriptTurn> stored) {
        // Busy for the whole rebuild, for the same reason the streaming path is busy for the whole
        // turn. Clicking a row pushed 4456 characters through a polite log region, one insertion of
        // them 4203 characters long, with aria-busy false throughout - a screen reader reading an
        // entire conversation aloud because the reader glanced at it. The mechanism that already
        // suppressed sixteen thousand streaming mutations is the same one; it was simply never
        // applied to the other way a transcript changes.
        markTranscriptBusy(true);
        paintTurns(stored);
        // Cleared to whatever the streaming state says rather than to false: a rewind redraws the
        // transcript in the middle of a turn, and clearing it flat would unmute the region for the
        // rest of the answer.
        markTranscriptBusy(streaming);
    }

    /**
     * Draws the turns, and says nothing about the region while doing it.
     *
     * <p>Split from {@link #showTurns} because its two callers disagree about one thing only, and
     * it is the thing that matters here: when the region may speak again. A rewind is followed
     * immediately by a question, so it releases within the same response and the streaming state
     * takes over. A load has nothing following it, so it must hold the muting open until the
     * browser has drawn a frame with it - see {@link #releaseWhenPainted()}. Keeping the drawing in
     * one place is what stops those two policies from becoming two rebuilds that drift apart.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param stored the turns to draw, oldest first; an empty list draws the empty state
     */
    private void paintTurns(List<TranscriptTurn> stored) {
        transcriptInner.removeAll();
        transcriptInner.add(emptyState);
        emptyState.setVisible(stored.isEmpty());
        // A conversation that already has turns was named by its first question long ago; saying so
        // again would rename it in the bar the moment the reader asks something new.
        titleAnnounced = !stored.isEmpty();

        for (TranscriptTurn turn : stored) {
            MessageTurn message = new MessageTurn(turn.role(), turn.text(), turnActions);
            transcriptInner.add(message);
            message.identify(turn.id());
            if (turn.failed()) {
                // Drawn as the failure it was rather than as an answer: the text is this
                // application's notice, and presenting it as something the model said would be a
                // reply the assistant never gave. The reader gets the same tint, the same border
                // and the same "answer again" they were given live.
                message.fail(FailureNotice.of(turn.text()));
            } else {
                message.markComplete();
            }
        }
        scrollTranscriptToEnd();
    }

    /**
     * Mutes or unmutes the transcript as a live region.
     *
     * <p>One method for the two things that change a transcript wholesale - an answer streaming in
     * and a conversation being loaded - because they are the same problem: a polite log region
     * announces every addition, and both of these make hundreds or thousands of them. Held here so
     * neither path can be given the treatment and the other forgotten, which is exactly what had
     * happened.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param busy true while the region is being rewritten and must not be read out
     */
    private void markTranscriptBusy(boolean busy) {
        transcript.getElement().setAttribute("aria-busy", String.valueOf(busy));
    }

    // ----------------------------------------------------------------- behaviour

    /**
     * Sends what is in the composer, if anything is.
     *
     * <p>The streaming guard is a backstop rather than the behaviour a reader meets: every caller
     * that can be reached while a turn is running answers the keystroke itself, in
     * {@link #refuseSendWhileStreaming}. It stays because a second question dispatched into a
     * running turn would overwrite the fields tracking that turn, and this method must refuse that
     * whatever a future caller believes about the state it is in.
     *
     * <p>Must run on the Vaadin UI thread; it reads and writes attached components.
     */
    private void submit() {
        if (streaming) {
            return;
        }
        String question = prompt.getValue();
        if (question == null || question.isBlank()) {
            return;
        }
        prompt.clear();
        ask(question);
    }

    /**
     * Answers the composer's Enter, whichever of its two meanings it currently has.
     *
     * <p>The browser has already been told to keep its own hands off this keystroke - see
     * {@link #SWALLOW_COMPOSER_ENTER} - so both branches are free to do nothing visible without the
     * draft changing underneath them. Shift + Enter and an Enter committing an IME candidate never
     * reach here at all: they are filtered out in the browser, where the newline they need is
     * inserted.
     *
     * <p>Must run on the Vaadin UI thread; it is a DOM event listener and Vaadin only calls it
     * there.
     */
    private void onComposerEnter() {
        if (streaming) {
            refuseSendWhileStreaming();
            return;
        }
        submit();
    }

    /**
     * Answers an Enter pressed on a draft while an answer is still arriving.
     *
     * <p>Nothing is sent and nothing is queued. A queue was rejected outright: it would dispatch a
     * message the reader never watched leave, at a moment they did not choose, into a conversation
     * that has moved on since they wrote it - the same class of dishonesty as a composer that looks
     * editable and is not. So the draft stays where it is and becomes sendable the instant the turn
     * ends, which is a promise the composer can keep without doing anything.
     *
     * <p>It is answered rather than ignored, and the choice is between two small lies. Silence
     * makes a deliberate keystroke indistinguishable from a broken key, which is exactly the defect
     * the read-only composer had. A toast or a dialog would interrupt a reader over something they
     * can see for themselves, and would have to be dismissed. The middle is the line the composer
     * already keeps for saying what is going on: it is where the reader is looking, it replaces a
     * hint that was already there, and {@link #setStreaming} restores that hint when the turn ends,
     * so the refusal cannot outlive the reason for it.
     *
     * <p>An empty composer is answered with silence, because Enter on an empty composer sends
     * nothing while idle either and a reader who pressed it has lost nothing to be told about.
     *
     * <p>Repeats are quiet by construction. A held Enter writes the same sentence into the same
     * line, so neither the hint nor the live region's element changes, and assistive technology is
     * told once rather than once per repeat.
     *
     * <p>Must run on the Vaadin UI thread; it is a key listener and Vaadin only calls it there.
     */
    private void refuseSendWhileStreaming() {
        String draft = prompt.getValue();
        if (draft == null || draft.isBlank()) {
            return;
        }
        showNotice(STILL_RESPONDING);
    }

    /**
     * Puts a question to the bound conversation and streams the answer into a new turn.
     *
     * <p>Reached from three places that are the same act with different provenance: the composer,
     * a regenerated answer, and an edited question. Keeping them on one path is what guarantees
     * that a re-asked question is recorded exactly once - the rewind happens before this is called,
     * so there is never a branch here that has to decide whether the question is already in the
     * transcript.
     *
     * @param question the message to send, already known to be non-blank
     */
    private void ask(String question) {
        emptyState.setVisible(false);
        announceTitle(question);
        // Recorded before the answer rather than after it: this is when the reader used the
        // conversation, and it is what floats the row they are in to the top of the sidebar.
        activity.record(conversationId);

        String askedOf = conversationId;
        MessageTurn asked = appendMessage(ConversationMessage.Role.USER, question);
        transcripts.append(askedOf, ConversationMessage.Role.USER, question)
                .ifPresent(recorded -> {
                    asked.identify(recorded.id());
                    // The conversation exists on disk from this line onwards, which is the moment it
                    // becomes somewhere a reload can come back to. Said here rather than when the
                    // answer completes because the question alone is already worth returning to, and
                    // said at all because the shell's other source for this - the conversation
                    // column - reads a different store that catches up on its own schedule.
                    announceStored(askedOf);
                });
        asked.markComplete();

        MessageTurn answer = appendMessage(ConversationMessage.Role.ASSISTANT, "");

        AtomicBoolean turn = new AtomicBoolean();
        AtomicBoolean filed = new AtomicBoolean();
        AtomicReference<TranscriptTurn> record = new AtomicReference<>();
        activeTurn = turn;
        activeFiled = filed;
        activeRecord = record;
        activeAnswer = answer;
        activeAskedOf = askedOf;
        setStreaming(true);
        // Said once, at the start. A reader who cannot see the caret pulsing at the end of an empty
        // answer had nothing at all to tell them the question had gone anywhere.
        announce(ANSWERING);

        // The UI reference must be captured on the UI thread; UI.getCurrent() is null on the worker.
        UI ui = getUI().orElseThrow(() -> new IllegalStateException("View is not attached to a UI"));
        // The conversation is captured here, on the UI thread, rather than read on the worker: a
        // reader who switches rows mid-answer must not have that answer filed under the row they
        // moved to.
        activeResponse = responseExecutor.submit(
                () -> streamResponse(ui, askedOf, question, answer, turn, filed, record));
    }

    /**
     * Takes the conversation back to a turn and asks a question in its place.
     *
     * <p>The one operation behind both regenerate and edit, and the reason neither of them can
     * duplicate the conversation: the turn being replaced, and everything that followed it, is
     * dropped from the transcript and from the model's context before the question is put again. A
     * regenerate that skipped this would send the same question twice into a history that already
     * contains it, and the model would be answering a reader who appeared to have asked twice.
     *
     * <p>A rewind the store refuses stops here. Asking anyway would append the question to the
     * conversation the reader was trying to leave, which is the one outcome worse than the answer
     * they already had.
     *
     * @param turnId   the turn to drop, along with everything after it
     * @param question what to ask in its place
     */
    private void rewindAndAsk(String turnId, String question) {
        if (streaming || turnId == null || question == null || question.isBlank()) {
            return;
        }
        Optional<List<TranscriptTurn>> rewound = transcripts.rewindTo(conversationId, turnId);
        if (rewound.isEmpty()) {
            showNotice(REWIND_REFUSED);
            return;
        }
        showTurns(rewound.get());
        ask(question);
    }

    /**
     * Finds the question an answer was given to.
     *
     * <p>Read off the transcript on screen rather than tracked as a field, because the transcript
     * is redrawn wholesale by every operation that could invalidate such a field. Regenerating
     * rewinds to the question rather than to the answer for a reason that is easy to get wrong: the
     * question has to leave the history too, or asking it again would put a second copy of it in.
     *
     * @param answer the assistant turn the reader wants answered again
     * @return the user turn immediately before it, or null when there is none on screen
     */
    private MessageTurn questionBehind(MessageTurn answer) {
        MessageTurn question = null;
        for (Component child : transcriptInner.getChildren().toList()) {
            if (child == answer) {
                return question;
            }
            if (child instanceof MessageTurn turn && turn.role() == ConversationMessage.Role.USER) {
                question = turn;
            }
        }
        return null;
    }

    /**
     * Tells the shell what this conversation is, the moment there is something to tell it.
     *
     * <p>Both places designed to carry the reader's position - the bar above the transcript and the
     * marked row in the sidebar - used to read "New chat" through an entire exchange, because
     * nothing ever told them a question had been asked. The shell decides what to do with it; a view
     * that is not inside one carries on without a title, which is what the tests instantiate.
     *
     * @param question the message just sent
     */
    private void announceTitle(String question) {
        if (titleAnnounced) {
            return;
        }
        titleAnnounced = true;
        MainLayout shell = findAncestor(MainLayout.class);
        if (shell != null) {
            shell.activeConversationTitled(question);
        }
    }

    /**
     * Tells the shell the conversation has changed on disk, once a turn is finished with.
     *
     * <p>A chat started a minute ago only becomes a stored conversation when its first exchange is
     * written, and until the sidebar reads the store again it is still showing the placeholder row
     * it invented. Announcing it here is what turns that row into the real one, and what moves the
     * conversation to the top of the list where it was just used.
     */
    private void announceUpdate() {
        MainLayout shell = findAncestor(MainLayout.class);
        if (shell != null) {
            shell.activeConversationUpdated();
        }
    }

    /**
     * Tells the shell a conversation now has a turn on disk, so it can be addressed.
     *
     * <p>Distinct from {@link #announceUpdate} on purpose, and the difference is which store is
     * being talked about. That one says the agent's own record has moved and the column should read
     * it again; this one says <em>this</em> store has a turn in it, which is what makes the
     * conversation a place rather than an intention. They were the same announcement once, and the
     * cost was measured: the column reads the agent's store, that store catches up when it likes,
     * and the address of the conversation the reader was in the middle of never appeared at all.
     *
     * <p>Named with the conversation rather than left implicit, because a turn is filed under the
     * conversation it was asked of and the reader may have moved on since - the shell has to be able
     * to tell that this is not about where they are now.
     *
     * <p>Must run on the Vaadin UI thread; the shell writes to the address bar from it.
     *
     * @param askedOf the conversation the turn was written for
     */
    private void announceStored(String askedOf) {
        MainLayout shell = findAncestor(MainLayout.class);
        if (shell != null) {
            shell.activeConversationStored(askedOf);
        }
    }

    /**
     * Runs the agent off the UI thread and pushes each token back through {@code ui.access}, which
     * serialises the update on the Vaadin session lock. Blocking here rather than on the UI thread
     * matters: {@code respondTo} does not return until the whole response is complete, and holding
     * the session lock for that long would freeze the browser for the entire turn.
     *
     * <h2>Why the answer is filed here as well as on the UI thread</h2>
     *
     * <p>Because the UI thread is not guaranteed to arrive. Everything an answer used to do on its
     * way into the transcript went through {@code ui.access}, including being written down, and a
     * command handed to {@code ui.access} runs only if there is still a browser attached to that UI
     * to run it for. A reload does not detach the old UI - Vaadin keeps it until a heartbeat
     * timeout, which is minutes away - so the completion command sat in a queue nothing would ever
     * drain, and the answer the agent had already produced in full was never recorded. A blind
     * review measured 303 characters on screen, reloaded, and came back to the question sitting
     * alone with no answer, no error and nothing to retry.
     *
     * <p>So the tokens are accumulated here, on the thread that receives them, and the finished
     * answer is filed from here too. The UI thread still files it - it is normally first, and it is
     * the only side that can hand the turn on screen its identity - and whichever gets there first
     * wins the once-guard; the other adopts what it finds. That is what makes the record a
     * consequence of the agent having answered rather than of a browser having been there to watch.
     *
     * @param ui       the UI the transcript updates are pushed through
     * @param askedOf  the conversation this answer belongs to, captured on the UI thread
     * @param question what to ask
     * @param answer   the turn on screen the tokens are drawn into
     * @param turn     this turn's cancellation flag
     * @param filed    the once-guard for writing this answer down
     * @param record   where the store's handle on the answer is left for the UI thread
     */
    private void streamResponse(UI ui, String askedOf, String question, MessageTurn answer,
                                AtomicBoolean turn, AtomicBoolean filed,
                                AtomicReference<TranscriptTurn> record) {
        // What the agent actually produced, kept on the side the tokens arrive on. The turn on
        // screen is not a substitute: it is only written to inside ui.access, so on a page that has
        // gone away it holds nothing at all.
        StringBuilder arrived = new StringBuilder();
        AtomicBoolean broke = new AtomicBoolean();
        try {
            agent.respondTo(askedOf, question, ResponseListener.of(
                    token -> {
                        if (token != null) {
                            arrived.append(token);
                        }
                        apply(ui, turn, () -> answer.append(token));
                    },
                    () -> apply(ui, turn, () -> {
                        answer.markComplete();
                        fileAnswer(filed, record, askedOf, answer.text())
                                .ifPresent(written -> answer.identify(written.id()));
                        announce(ANSWER_COMPLETE);
                        finishTurn(turn);
                    }),
                    message -> {
                        // Read below, on this thread, to decide whether there is an answer to file.
                        // A failed turn's fragments are not recorded: the bubble is replaced by this
                        // application's own sentence, and half a model's answer filed under it would
                        // come back from the store presented as part of the apology.
                        broke.set(true);
                        // Classified here, on the thread the provider's message arrives on, and it
                        // is the last place that message exists: nothing downstream takes a string.
                        FailureNotice notice = FailureNotice.of(message);
                        // Filed from this thread for the same reason a completed answer is - a
                        // browser that has gone away must not be what decides whether the turn is
                        // remembered. Without it a reload replaced the exchange with the question
                        // alone, which reads as a question that was never asked.
                        Optional<TranscriptTurn> written = fileFailure(filed, record, askedOf, notice);
                        apply(ui, turn, () -> {
                            answer.fail(notice);
                            written.ifPresent(failure -> answer.identify(failure.id()));
                            // What went wrong is in the turn, and the transcript stops being busy a
                            // line later, so the reader is told that and then read it.
                            announce(ANSWER_FAILED);
                            finishTurn(turn);
                        });
                    }
            ));
            // The response is complete. Whether anyone saw it is a different question, and not one
            // the transcript's contents are allowed to depend on. A turn the reader stopped is left
            // alone: they have already been given the half they were reading.
            if (!broke.get() && !turn.get()) {
                fileAnswer(filed, record, askedOf, arrived.toString());
            }
        } catch (RuntimeException e) {
            // The listener's error path is not guaranteed to run for a failure thrown outside the
            // stream, so the composer is released here too. Without this the view would stay locked
            // and the user would have no way to retry.
            apply(ui, turn, () -> {
                answer.markComplete();
                finishTurn(turn);
            });
            throw e;
        }
    }

    /**
     * Applies a transcript update on the UI thread unless the turn it belongs to was stopped.
     *
     * <p>Checked twice on purpose: once on the worker to avoid queuing work the reader has already
     * cancelled, and once inside the lock, because the stop can land between the two.
     */
    private void apply(UI ui, AtomicBoolean turn, Runnable update) {
        if (turn.get()) {
            return;
        }
        ui.access(() -> {
            if (!turn.get()) {
                update.run();
            }
        });
    }

    /**
     * Releases the composer once a turn is over. Ignores a turn that is no longer the active one,
     * so a late completion cannot re-enable the composer in the middle of the next question.
     */
    private void finishTurn(AtomicBoolean turn) {
        if (activeTurn != turn) {
            return;
        }
        clearTurn();
        // Deliberately no scroll. A reader who scrolled up during the answer is still reading it,
        // and the end of the turn is the worst possible moment to take that away; if they never
        // scrolled, the transcript is pinned and has been following the answer all along.
        prompt.focus();
        announceUpdate();
    }

    /**
     * Stops the turn that is streaming, and tells the reader it has stopped.
     *
     * <p>{@link Agent} exposes no cancellation, so what is cancelled here is this view's half of the
     * exchange: the flag closes the transcript to further tokens, the composer comes back under the
     * reader's control, and the worker is interrupted in case the provider honours it. The response
     * may still complete server-side; it simply no longer has anywhere to go.
     *
     * <p>Everything above that sentence is {@link #closeActiveTurn}, which {@link #onDetach} runs
     * too. What is only here is what is said to somebody who is still looking: the announcement and
     * the caret going back to the composer are meaningless to a page that has already gone.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void stopStreaming() {
        if (!closeActiveTurn()) {
            return;
        }
        announce(ANSWER_STOPPED);
        prompt.focus();
    }

    /**
     * Closes the turn that is streaming and files whatever it had produced.
     *
     * <p>What did arrive is kept, whichever way the turn ended. Half an answer is marked as one on
     * screen and recorded as one too: a reader who stopped a generation was reading it, and a
     * transcript that dropped it would leave the next question following a turn that appears never
     * to have been answered. {@link #fileAnswer} declines an answer that arrived empty, so a turn
     * cut short before its first token leaves no reply attributed to an assistant that never spoke.
     *
     * <p>The flag is claimed with a compare-and-set, which is what makes this safe to call from two
     * places that can race: the reader pressing Stop and the page being torn down are the same
     * instant often enough to matter, and the second caller has to find the turn already closed
     * rather than record the answer twice.
     *
     * <p>Must run on the Vaadin UI thread; it writes to components and to the transcript store.
     *
     * @return true when this call is the one that closed the turn, false when there was none
     *         running or another caller had already closed it
     */
    private boolean closeActiveTurn() {
        AtomicBoolean turn = activeTurn;
        if (turn == null || !turn.compareAndSet(false, true)) {
            return false;
        }
        if (activeResponse != null) {
            activeResponse.cancel(true);
        }
        if (activeAnswer != null) {
            activeAnswer.stop();
            MessageTurn answer = activeAnswer;
            fileAnswer(activeFiled, activeRecord, activeAskedOf, answer.text())
                    .ifPresent(written -> answer.identify(written.id()));
        }
        clearTurn();
        return true;
    }

    /**
     * Writes a finished answer down, once, whichever thread gets to it first.
     *
     * <p>Three parties can reach the end of one turn and only one of them may write it: the agent's
     * completion arriving on the UI thread, the same completion seen by the worker that produced it,
     * and the reader stopping the generation or leaving the page. Which one wins does not matter -
     * they are filing the same text under the same conversation - but that exactly one wins does,
     * because the loser filing too would put the answer in the transcript twice.
     *
     * <p>Filed under the conversation the question was asked of rather than the one currently bound:
     * a reader who switches rows mid-answer must not have that answer land in the row they moved to.
     *
     * <p>Nothing at all is filed for an answer that arrived empty, and the guard is deliberately not
     * spent on one either. An empty turn in the transcript would be a reply the reader could later
     * see attributed to the assistant, and the assistant did not say it - and claiming the guard for
     * it would stop the text that arrives a moment later from ever being written.
     *
     * <p>Safe off the UI thread: it touches the transcript store, which serialises its own writes,
     * and two atomics. It touches no component, which is why handing the turn on screen its identity
     * is left to the caller.
     *
     * @param once    the turn's once-guard, or null when there is no turn
     * @param into    where to leave the store's handle for whoever files second
     * @param askedOf the conversation this answer belongs to
     * @param text    the answer as it stands, possibly blank
     * @return the store's record of the answer - the one just written, or the one another thread
     *         wrote - or empty when nothing has been written for this turn
     */
    private Optional<TranscriptTurn> fileAnswer(AtomicBoolean once, AtomicReference<TranscriptTurn> into,
                                                String askedOf, String text) {
        if (once == null || into == null) {
            return Optional.empty();
        }
        if (text == null || text.isBlank() || !once.compareAndSet(false, true)) {
            return Optional.ofNullable(into.get());
        }
        Optional<TranscriptTurn> written =
                transcripts.append(askedOf, ConversationMessage.Role.ASSISTANT, text);
        written.ifPresent(into::set);
        return written;
    }

    /**
     * Writes a failed turn down, under the same once-guard a finished answer is written under.
     *
     * <p>The guard is shared with {@link #fileAnswer} on purpose. A turn that produced fragments
     * and then broke has one of two things worth recording and not both: the fragments belong to an
     * answer that no longer exists on screen, because the bubble has been replaced by this
     * application's own sentence, and filing them would put half a model's paragraph in the store
     * presented as part of the apology. Whichever party reaches the end of the turn first therefore
     * wins, exactly as it does for a completed answer.
     *
     * <p>Nothing here can leak: the store is handed a {@link FailureNotice}, and a notice's
     * sentence is one of this application's own.
     *
     * <p>Safe off the UI thread; it touches the transcript store and two atomics, and no component.
     *
     * @param once    the turn's once-guard, or null when there is no turn
     * @param into    where to leave the store's handle for whoever files second
     * @param askedOf the conversation this turn belongs to
     * @param notice  what the reader was told
     * @return the store's record of the failure, or empty when nothing was written for this turn
     */
    private Optional<TranscriptTurn> fileFailure(AtomicBoolean once, AtomicReference<TranscriptTurn> into,
                                                 String askedOf, FailureNotice notice) {
        if (once == null || into == null || !once.compareAndSet(false, true)) {
            return Optional.ofNullable(into == null ? null : into.get());
        }
        Optional<TranscriptTurn> written = transcripts.appendFailure(askedOf, notice);
        written.ifPresent(into::set);
        return written;
    }

    /**
     * Puts the view back to having no turn, which is a single fact spread over six fields.
     *
     * <p>They are cleared together because they are read together and guarded by only one of them.
     * {@link #closeActiveTurn} decides whether a turn exists from {@code activeTurn} alone and then
     * treats {@code activeFiled}, {@code activeRecord}, {@code activeAskedOf} and
     * {@code activeAnswer} as that turn's state; {@link #finishTurn} compares the turn it was given
     * against {@code activeTurn} for the same reason. Clearing a subset would leave the rest
     * describing a turn that has ended, and the next caller to reach them would file a finished
     * answer against a record slot that already holds it.
     *
     * <p>{@code activeFiled} and {@code activeRecord} are the pair added last and the reason is
     * worth stating, because null is not merely tidiness for those two: {@link #fileAnswer} and
     * {@link #fileFailure} take null in either as "there is no turn" and decline to write. So the
     * cleared state is what makes a late arrival - a worker completing after the reader stopped the
     * generation, or after the page went away - fail closed instead of writing a second copy of an
     * answer into the transcript. Leaving them set would hand a stale once-guard to a caller that had
     * every right to trust it.
     *
     * <p>They also hold a {@link TranscriptTurn} and a component. This view is bound to the session
     * and outlives every turn drawn in it, so a finished turn left in these fields is a finished turn
     * retained for as long as the tab is open.
     *
     * <p>{@code setStreaming(false)} is part of the same fact rather than a separate step: the
     * composer's primary action is Stop for exactly as long as there is a turn, and a view with no
     * turn still offering to stop one is the same inconsistency in the half the reader can see.
     *
     * <p>Must run on the Vaadin UI thread; it writes to components through {@code setStreaming}.
     */
    private void clearTurn() {
        activeTurn = null;
        activeFiled = null;
        activeRecord = null;
        activeResponse = null;
        activeAnswer = null;
        activeAskedOf = null;
        setStreaming(false);
    }

    private MessageTurn appendMessage(ConversationMessage.Role role, String text) {
        MessageTurn turn = new MessageTurn(role, text, turnActions);
        transcriptInner.add(turn);
        scrollTranscriptToEnd();
        return turn;
    }

    /**
     * Takes the transcript to the bottom, whatever the reader was looking at.
     *
     * <p>Called only where the reader's own action says they want to be there: sending a message,
     * and opening another conversation. Everything that happens on its own - a token arriving, a
     * turn finishing - leaves the position to the pin installed in
     * {@link #installTranscriptScrollPin}, which follows the answer while the reader is at the
     * bottom and stops the instant they are not.
     */
    private void scrollTranscriptToEnd() {
        transcript.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }

    /**
     * Moves the composer between its two states: while a turn streams the button becomes a stop
     * control - enabled, because a dead slab is not an affordance - and the hint line says so.
     *
     * <p>The field itself does not change, and that is the whole of the fix here. It used to be put
     * into read-only for the length of every turn, which is where the interface started lying: a
     * read-only Vaadin text area is pixel-identical to an editable one - same fill, same
     * placeholder, same caret, same focus ring - so the largest control on the screen went on
     * inviting the reader to type and silently dropped every character they typed. Two exits were
     * available and only one of them ends the lie. Labelling the lock makes the invitation honest;
     * removing it makes the invitation true. The second was chosen, and it is also what the
     * product this composer is measured against does: the reader may draft their next question
     * while an answer arrives.
     *
     * <p>What is still refused is sending, and only sending. The primary action for the length of a
     * turn is Stop, and Enter is answered by {@link #refuseSendWhileStreaming} rather than queued.
     *
     * <p>The field's appearance is left to the stylesheet's resting rules for the same reason. It
     * carried a {@code --busy} modifier while it was locked, and that modifier died with the lock:
     * a control the reader can use is not one to paint as unavailable.
     *
     * <p>Must run on the Vaadin UI thread; it writes to attached components.
     *
     * @param busy true from the moment a question is sent until its answer completes, fails or is
     *             stopped
     */
    private void setStreaming(boolean busy) {
        streaming = busy;
        // Busy while the tokens arrive. Without it a log region announces every fragment as it
        // lands, which is a screen reader reading an answer one word at a time for thirty seconds;
        // with it, the additions are held and read once when the turn closes.
        markTranscriptBusy(busy);

        sendGlyph.setClassName("jc-glyph");
        sendGlyph.addClassName(busy ? "jc-glyph--stop" : "jc-glyph--arrow-up");
        // Stopping is not sending. The accent is the page's one primary action and stays with send;
        // the modifier steps the cancel control down to a raised, outlined button.
        send.setClassName("jc-send");
        if (busy) {
            send.addClassName("jc-send--stop");
        }
        send.setAriaLabel(busy ? STOP_LABEL : SEND_LABEL);
        // One button with two jobs answers to two different keys, and says which on both of them.
        Shortcut key = busy ? Shortcut.ESCAPE : Shortcut.SEND;
        send.setTooltipText(key.tooltip(busy ? STOP_LABEL : SEND_LABEL));
        key.announceOn(send.getElement());

        if (busy) {
            showBusyHint();
        } else {
            showIdleHint();
        }
        updateSendEnabled();
    }

    /**
     * Sending needs something to send: an empty composer leaves the button disabled rather than
     * offering a full-saturation primary action that does nothing when clicked. Stopping is always
     * available while a turn streams.
     */
    private void updateSendEnabled() {
        String draft = prompt.getValue();
        send.setEnabled(streaming || (draft != null && !draft.isBlank()));
    }

    /**
     * The waiting state, with something that actually moves in it.
     *
     * <p>Static text under a locked field is not progress. The other animated cue - the caret at the
     * end of the answer - is hundreds of pixels away from the control the reader is sitting at on a
     * full screen, so the hint carries its own. The dots are decorative: the sentence beside them
     * already says what is happening, and a screen reader should not be read three bullets.
     */
    private void showBusyHint() {
        Element hint = composerHint.getElement();
        hint.removeAllChildren();
        hint.appendChild(Element.createText(BUSY_HINT));
        hint.appendChild(MessageTurn.dots());
        composerHint.removeClassName("jc-composer__hint--notice");
    }

    /** The one place a key cap earns its keep: the two shortcuts the composer answers to. */
    private void showIdleHint() {
        Element hint = composerHint.getElement();
        hint.removeAllChildren();
        hint.appendChild(
                keyCap("Enter"), Element.createText(" to send, "),
                keyCap("Shift"), Element.createText(" + "), keyCap("Enter"),
                Element.createText(" for a new line"));
        composerHint.removeClassName("jc-composer__hint--notice");
    }

    /**
     * Says something went wrong, in the line the composer already keeps for saying what is going on.
     *
     * <p>Not a toast and not a dialog. What is being reported is that the conversation did not
     * change, the reader is looking straight at the conversation that did not change, and a modal
     * would make them dismiss something before they could see that for themselves. It is announced
     * politely so a screen reader is told too, and it is replaced by the next hint the composer has
     * cause to show.
     *
     * @param message an English sentence about the conversation, never an exception
     */
    private void showNotice(String message) {
        Element hint = composerHint.getElement();
        hint.removeAllChildren();
        hint.appendChild(Element.createText(message));
        composerHint.addClassName("jc-composer__hint--notice");
        // Said out loud through the region that was already there. The line itself used to be made
        // a live region here, at the same moment its text was written, which is exactly when a live
        // region does not work.
        announce(message);
    }

    /**
     * Says one short thing, politely, without moving the caret.
     *
     * <p>The politeness is the point: an assertive region interrupts whatever a reader is being
     * read mid-word, and nothing this announces is worth that. Nothing here focuses anything
     * either - the reader stays exactly where they were, which for the length of a turn is the
     * composer.
     *
     * @param message one English sentence or phrase, never an exception
     */
    private void announce(String message) {
        announcer.setText(message);
    }

    private static Element keyCap(String key) {
        Element cap = new Element("kbd");
        cap.setText(key);
        return cap;
    }

    /**
     * What the reader may do to a turn that is already in the transcript.
     *
     * <p>Kept as an inner class rather than implemented by the view itself, so "regenerate" and
     * "edit and resend" do not become part of what a routed Vaadin view publicly is. Both funnel
     * into the same rewind, which is the whole reason neither of them can leave the conversation
     * holding two copies of a question.
     */
    private final class TurnControls implements MessageTurn.Actions {

        @Override
        public void regenerate(MessageTurn answer) {
            MessageTurn question = questionBehind(answer);
            if (question == null || question.turnId() == null) {
                // The answer is on screen but the question behind it was never recorded, so there is
                // nothing to rewind to and no text to re-ask. Saying so beats a button that shrugs.
                showNotice(REWIND_REFUSED);
                return;
            }
            rewindAndAsk(question.turnId(), question.text());
        }

        @Override
        public void editAndResend(MessageTurn question, String text) {
            rewindAndAsk(question.turnId(), text);
        }
    }
}
