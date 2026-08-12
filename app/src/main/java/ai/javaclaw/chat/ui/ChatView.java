package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * What the polite announcer says at each end of a turn.
     *
     * <p>Short on purpose. This is read out over whatever the reader is doing, so it says what
     * changed and stops; the answer itself is in the transcript, which is where a reader who wants
     * it will go looking.
     */
    private static final String ANSWERING = "Answering";
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
        transcript.getElement().setAttribute("aria-live", "polite");
        // A live region with no name is a region nobody can choose to jump to, because there is
        // nothing in the landmark list to choose.
        transcript.getElement().setAttribute("aria-label", TRANSCRIPT_LABEL);
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
        prompt.addValueChangeListener(event -> updateSendEnabled());
        prompt.addKeyDownListener(Key.ENTER, event -> {
            // Shift + Enter has to reach the textarea so a message can span several lines.
            if (!event.getModifiers().contains(KeyModifier.SHIFT)) {
                submit();
            }
        });

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

        announcer.addClassNames("jc-sr-only", "jc-announcer");
        announcer.getElement().setAttribute("role", "status");
        announcer.getElement().setAttribute("aria-live", "polite");
        // The whole sentence every time, not the words that changed: these are short statements
        // that only mean anything read together.
        announcer.getElement().setAttribute("aria-atomic", "true");

        Div inner = new Div(composerField, composerHint, announcer);
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
        // The composer belongs to this view, so the keystroke that reaches it is registered here
        // rather than in the shell, which has no business knowing there is a field to reach.
        Shortcut.FOCUS_COMPOSER.register(this, prompt::focus);
        // Escape stops the answer - but only when it was pressed inside this view. Scoping it here
        // is what keeps Escape-to-close-a-dialog from also killing a generation running behind it:
        // an overlay is teleported out of this subtree, so a keystroke inside one never reaches
        // this listener at all. Propagation and the browser default are left alone for the same
        // reason the shell leaves them alone on its own Escape.
        Shortcut.ESCAPE.register(this, this::stopStreaming).forEach(registration -> {
            registration.listenOn(this);
            registration.setEventPropagationAllowed(true);
            registration.setBrowserDefaultAllowed(true);
        });
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
        conversationId = conversation.id();
        prompt.clear();
        showStoredTurns();
    }

    /** The conversation currently bound, for the shell that chose it. */
    String conversationId() {
        return conversationId;
    }

    /** Redraws the transcript from what the store holds for the bound conversation. */
    private void showStoredTurns() {
        showTurns(transcripts.turns(conversationId));
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
            message.markComplete();
        }
        scrollTranscriptToEnd();
    }

    // ----------------------------------------------------------------- behaviour

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
                .ifPresent(recorded -> asked.identify(recorded.id()));
        asked.markComplete();

        MessageTurn answer = appendMessage(ConversationMessage.Role.ASSISTANT, "");

        AtomicBoolean turn = new AtomicBoolean();
        activeTurn = turn;
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
        activeResponse = responseExecutor.submit(() -> streamResponse(ui, askedOf, question, answer, turn));
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
     * Runs the agent off the UI thread and pushes each token back through {@code ui.access}, which
     * serialises the update on the Vaadin session lock. Blocking here rather than on the UI thread
     * matters: {@code respondTo} does not return until the whole response is complete, and holding
     * the session lock for that long would freeze the browser for the entire turn.
     */
    private void streamResponse(UI ui, String askedOf, String question, MessageTurn answer,
                                AtomicBoolean turn) {
        try {
            agent.respondTo(askedOf, question, ResponseListener.of(
                    token -> apply(ui, turn, () -> answer.append(token)),
                    () -> apply(ui, turn, () -> {
                        answer.markComplete();
                        recordAnswer(askedOf, answer);
                        announce(ANSWER_COMPLETE);
                        finishTurn(turn);
                    }),
                    message -> apply(ui, turn, () -> {
                        answer.fail(message);
                        // What went wrong is in the turn, and the transcript stops being busy a
                        // line later, so the reader is told that and then read it.
                        announce(ANSWER_FAILED);
                        finishTurn(turn);
                    })
            ));
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
     * Stops the turn that is streaming.
     *
     * <p>{@link Agent} exposes no cancellation, so what is cancelled here is this view's half of the
     * exchange: the flag closes the transcript to further tokens, the composer comes back under the
     * reader's control, and the worker is interrupted in case the provider honours it. The response
     * may still complete server-side; it simply no longer has anywhere to go.
     *
     * <p>What did arrive is kept. Half an answer is marked as one on screen, and it is recorded as
     * one too: a reader who stopped a generation was reading it, and a transcript that dropped it
     * would leave the next question following a turn that appears never to have been answered.
     */
    private void stopStreaming() {
        AtomicBoolean turn = activeTurn;
        if (turn == null || !turn.compareAndSet(false, true)) {
            return;
        }
        if (activeResponse != null) {
            activeResponse.cancel(true);
        }
        if (activeAnswer != null) {
            activeAnswer.stop();
            recordAnswer(activeAskedOf, activeAnswer);
        }
        announce(ANSWER_STOPPED);
        clearTurn();
        prompt.focus();
    }

    /**
     * Writes a finished answer into the transcript and hands the turn its identity.
     *
     * <p>Filed under the conversation the question was asked of rather than the one currently bound,
     * and skipped entirely when nothing arrived: an empty turn in the transcript would be a reply
     * the reader could later see attributed to the assistant, and the assistant did not say it.
     *
     * @param askedOf the conversation this answer belongs to
     * @param answer  the turn that has just closed
     */
    private void recordAnswer(String askedOf, MessageTurn answer) {
        transcripts.append(askedOf, ConversationMessage.Role.ASSISTANT, answer.text())
                .ifPresent(recorded -> answer.identify(recorded.id()));
    }

    private void clearTurn() {
        activeTurn = null;
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
     * Moves the composer between its two states. While a turn streams the field is locked and the
     * button becomes a stop control - enabled, because a dead slab is not an affordance.
     *
     * <p>Read-only rather than disabled, and the difference is the reader's place on the page. A
     * disabled element cannot hold focus, so locking a field the reader is sitting in moves the
     * caret to the document body for the length of the turn and their next Tab starts again from
     * the top of the page. Read-only refuses exactly the same keystrokes and keeps the element
     * focusable and in the tab order. Enter is refused by {@link #submit} either way.
     */
    private void setStreaming(boolean busy) {
        streaming = busy;
        prompt.setReadOnly(busy);
        // Busy while the tokens arrive. Without it a log region announces every fragment as it
        // lands, which is a screen reader reading an answer one word at a time for thirty seconds;
        // with it, the additions are held and read once when the turn closes.
        transcript.getElement().setAttribute("aria-busy", String.valueOf(busy));

        composerField.setClassName("jc-composer__field");
        if (busy) {
            composerField.addClassName("jc-composer__field--busy");
        }

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
