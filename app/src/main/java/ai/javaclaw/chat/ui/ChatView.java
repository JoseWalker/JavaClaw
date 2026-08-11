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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.List;
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
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("JavaClaw")
public class ChatView extends Div {

    /**
     * Conversation the web UI talks to. The existing htmx chat uses the same id, so both UIs show
     * one conversation during the migration instead of silently diverging.
     *
     * <p>Package-private because the shell needs it: {@link MainLayout} marks the row the reader is
     * actually in, and it cannot do that without knowing which conversation that is.
     */
    static final String CONVERSATION_ID = "web";

    private static final String BUSY_HINT = "JavaClaw is responding";
    private static final String SEND_LABEL = "Send message";
    private static final String STOP_LABEL = "Stop responding";

    private final transient Agent agent;
    private final transient ExecutorService responseExecutor;

    private final Div transcript = new Div();
    private final Div transcriptInner = new Div();
    private final Div emptyState = createEmptyState();
    private final Div composer = new Div();
    private final Div composerField = new Div();
    private final Span composerHint = new Span();
    private final TextArea prompt = new TextArea();
    private final Button send = new Button();
    private final Span sendGlyph = new Span();

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
    private transient MessageBubble activeAnswer;

    private boolean scrollPinInstalled;

    public ChatView(Agent agent, ExecutorService responseExecutor) {
        this.agent = agent;
        this.responseExecutor = responseExecutor;

        addClassName("jc-app");
        setSizeFull();
        add(createTranscript(), createComposer());
        setStreaming(false);
    }

    // ------------------------------------------------------------------ layout

    private Component createTranscript() {
        transcriptInner.addClassNames("jc-transcript__inner", "jc-measure");
        transcriptInner.add(emptyState);

        transcript.addClassName("jc-transcript");
        // Announced as a log so a screen reader reads streamed tokens without stealing focus.
        transcript.getElement().setAttribute("role", "log");
        transcript.getElement().setAttribute("aria-live", "polite");
        transcript.add(transcriptInner);
        return transcript;
    }

    private Div createEmptyState() {
        Span mark = brandMark("jc-mark--l");

        Span title = new Span("Ask JavaClaw anything");
        title.addClassName("jc-empty__title");

        Span hint = new Span("Answers stream in token by token, straight from the agent.");
        hint.addClassName("jc-empty__hint");

        Div empty = new Div(mark, title, hint);
        empty.addClassName("jc-empty");
        return empty;
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

        Div inner = new Div(composerField, composerHint);
        inner.addClassNames("jc-composer__inner", "jc-measure");

        composer.addClassName("jc-composer");
        composer.add(inner);
        return composer;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        installTranscriptScrollPin();
    }

    /**
     * Keeps the newest turn visible when the composer grows under it.
     *
     * <p>A draft that wraps onto more lines makes the composer taller and the transcript shorter in
     * the same frame, which would otherwise slide the last message behind the composer's top edge -
     * the newest content hidden by the act of replying to it. A resize observer re-pins the
     * transcript, but only when it was already at the bottom, so a reader who scrolled up to quote
     * something is never yanked back down while typing.
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
                });
                new ResizeObserver(() => {
                    if (pinned) {
                        transcript.scrollTo({ top: transcript.scrollHeight, behavior: 'instant' });
                    }
                }).observe($0);
                """, composer.getElement());
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
        emptyState.setVisible(false);
        announceTitle(question);

        appendMessage("user", question).markComplete();
        MessageBubble answer = appendMessage("assistant", "");

        AtomicBoolean turn = new AtomicBoolean();
        activeTurn = turn;
        activeAnswer = answer;
        setStreaming(true);

        // The UI reference must be captured on the UI thread; UI.getCurrent() is null on the worker.
        UI ui = getUI().orElseThrow(() -> new IllegalStateException("View is not attached to a UI"));
        activeResponse = responseExecutor.submit(() -> streamResponse(ui, question, answer, turn));
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
     * Runs the agent off the UI thread and pushes each token back through {@code ui.access}, which
     * serialises the update on the Vaadin session lock. Blocking here rather than on the UI thread
     * matters: {@code respondTo} does not return until the whole response is complete, and holding
     * the session lock for that long would freeze the browser for the entire turn.
     */
    private void streamResponse(UI ui, String question, MessageBubble answer, AtomicBoolean turn) {
        try {
            agent.respondTo(CONVERSATION_ID, question, ResponseListener.of(
                    token -> apply(ui, turn, () -> answer.append(token)),
                    () -> apply(ui, turn, () -> {
                        answer.markComplete();
                        finishTurn(turn);
                    }),
                    message -> apply(ui, turn, () -> {
                        answer.fail(message);
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
        scrollTranscriptToEnd();
        prompt.focus();
    }

    /**
     * Stops the turn that is streaming.
     *
     * <p>{@link Agent} exposes no cancellation, so what is cancelled here is this view's half of the
     * exchange: the flag closes the transcript to further tokens, the composer comes back under the
     * reader's control, and the worker is interrupted in case the provider honours it. The response
     * may still complete server-side; it simply no longer has anywhere to go.
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
        }
        clearTurn();
        scrollTranscriptToEnd();
        prompt.focus();
    }

    private void clearTurn() {
        activeTurn = null;
        activeResponse = null;
        activeAnswer = null;
        setStreaming(false);
    }

    private MessageBubble appendMessage(String role, String text) {
        MessageBubble bubble = new MessageBubble(role, text);
        transcriptInner.add(bubble);
        scrollTranscriptToEnd();
        return bubble;
    }

    /**
     * Keeps the newest turn in view. Done on append and on completion rather than on every token,
     * so a reader who has scrolled up is not yanked back down mid-answer.
     */
    private void scrollTranscriptToEnd() {
        transcript.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }

    /**
     * Moves the composer between its two states. While a turn streams the field is locked and the
     * button becomes a stop control - enabled, because a dead slab is not an affordance.
     */
    private void setStreaming(boolean busy) {
        streaming = busy;
        prompt.setEnabled(!busy);

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
        send.setTooltipText(busy ? STOP_LABEL : SEND_LABEL);

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

        Element dots = new Element("span");
        dots.getClassList().add("jc-hint__dots");
        dots.setAttribute("aria-hidden", "true");
        for (int i = 0; i < 3; i++) {
            Element dot = new Element("span");
            dot.getClassList().add("jc-hint__dot");
            dots.appendChild(dot);
        }
        hint.appendChild(dots);
    }

    /** The one place a key cap earns its keep: the two shortcuts the composer answers to. */
    private void showIdleHint() {
        Element hint = composerHint.getElement();
        hint.removeAllChildren();
        hint.appendChild(
                keyCap("Enter"), Element.createText(" to send, "),
                keyCap("Shift"), Element.createText(" + "), keyCap("Enter"),
                Element.createText(" for a new line"));
    }

    private static Element keyCap(String key) {
        Element cap = new Element("kbd");
        cap.setText(key);
        return cap;
    }

    /**
     * One turn in the transcript.
     *
     * <p>The two speakers are told apart by alignment and tint rather than by a label printed above
     * every message; the role is still carried as text for assistive technology, where neither of
     * those signals exists. There is no per-turn avatar: it was the same two letters on every
     * answer, and in the flow it pushed the prose off the measure the user's own bubble is flush
     * against, so the two speakers never shared an axis.
     *
     * <p>While a turn is streaming the root carries {@code jc-message--streaming}, which is what
     * draws the caret and keeps an as-yet-empty turn from collapsing to a sliver.
     *
     * <h2>Text while it arrives, markup once it has</h2>
     *
     * <p>Tokens land in a raw buffer and are painted as plain text, because re-parsing and replacing
     * the whole answer on every token would push a growing subtree over the wire dozens of times a
     * second. The moment the turn is closed - completed or stopped - the buffer is rendered into the
     * prose markup the stylesheet is written for.
     */
    private static final class MessageBubble extends Div {

        private final boolean fromUser;
        private final StringBuilder raw = new StringBuilder();
        private final Span content;
        private final Div bubble;
        private final Div body;

        /** True once the turn's text has been replaced by markup, or claimed by an error. */
        private boolean settled;

        private MessageBubble(String role, String text) {
            fromUser = "user".equals(role);
            raw.append(text);
            content = new Span(text);

            Span label = new Span(fromUser ? "You" : "JavaClaw");
            label.addClassName("jc-sr-only");

            bubble = new Div(content);
            bubble.addClassNames("jc-message__bubble", "jc-prose");

            body = new Div(label, bubble);
            body.addClassName("jc-message__body");

            addClassNames("jc-message", "jc-message--streaming");
            getElement().setAttribute("data-role", role);
            add(body);
        }

        private void append(String token) {
            raw.append(token);
            content.setText(raw.toString());
        }

        private void markComplete() {
            removeClassName("jc-message--streaming");
            renderAnswer();
        }

        /**
         * Replaces the streamed text with the markup it describes.
         *
         * <p>Only the assistant's turn: what the reader typed is their text, not a document, and
         * turning their asterisks into emphasis would be editing their message.
         */
        private void renderAnswer() {
            if (fromUser || settled) {
                return;
            }
            List<Element> markup = Markdown.render(raw.toString());
            if (markup.isEmpty()) {
                return;
            }
            settled = true;
            bubble.getElement().removeAllChildren();
            markup.forEach(bubble.getElement()::appendChild);
            bubble.addClassName("jc-message__bubble--rendered");
        }

        private void fail(String message) {
            // An error is this application's own sentence, not the model's markdown.
            settled = true;
            content.setText(message);
            removeClassName("jc-message--streaming");
            addClassName("jc-message--failed");
        }

        /** Marks a turn the reader cut short, so a half answer is not mistaken for a whole one. */
        private void stop() {
            markComplete();
            Span note = new Span("Stopped");
            note.addClassName("jc-message__note");
            body.add(note);
        }
    }
}
