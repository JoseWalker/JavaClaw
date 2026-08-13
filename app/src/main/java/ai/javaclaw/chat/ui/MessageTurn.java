package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.Element;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One turn of the conversation, as a component that owns it.
 *
 * <p>It began as an anonymous {@code Div} inside the view, which was enough while a transcript was
 * something to read and nothing else. It is not enough for a transcript that can be acted on: "copy
 * this", "answer this again", "I meant to ask something else" are all sentences with a subject, and
 * a div in a list is not one. So a turn is now a thing with an identity, a role, the text it is
 * showing, and the small set of controls that identity makes honest.
 *
 * <h2>What is drawn</h2>
 *
 * <p>The two speakers are told apart by alignment and tint rather than by a label printed above
 * every message; the role is still carried as text for assistive technology, where neither of those
 * signals exists. There is no per-turn avatar: it was the same two letters on every answer, and in
 * the flow it pushed the prose off the measure the user's own bubble is flush against, so the two
 * speakers never shared an axis.
 *
 * <h2>Typeset while it arrives, not only once it has</h2>
 *
 * <p>Tokens used to be painted as the source they were written in, on the reasoning that re-parsing
 * and replacing the whole answer on every token would push a growing subtree over the wire dozens of
 * times a second. That reasoning holds for the answer and not for the sentence being written, and the
 * difference is what the reader was paying: an answer of one item per line contains no blank line, so
 * nothing in it was ever final and the whole of it arrived as {@code **} runs and backticks.
 *
 * <p>{@link StreamingProse} separates the two questions. What it reports as final is rendered once
 * and appended above the tail, and only the tail is re-rendered per token - from the buffer, so
 * nothing drawn there is committed to and a later line can still change what it meant. The moment
 * the turn is closed - completed
 * or stopped - the raw buffer is rendered whole, so a finished answer is the same markup whether it
 * was watched arriving or read back off disk.
 *
 * <h2>When a control may appear</h2>
 *
 * <p>Only on a closed turn, and only when it can do what it says. Regenerating and editing both
 * mean "take the conversation back to this point", which needs the store's id for this turn; until
 * {@link #identify} has supplied one, those controls are not drawn at all rather than drawn and
 * left inert. Copy needs no identity - the text is already in front of the reader - so it is the
 * one control a turn the store could not record still offers.
 */
final class MessageTurn extends Div {

    /**
     * What the view can be asked to do about a turn the reader is not satisfied with.
     *
     * <p>The turn owns the affordance and the view owns the conversation. Editing is the clearer
     * half of that split: this component knows how to open a field on a message and how to take it
     * away again, and knows nothing about which turns have to be discarded to ask the question a
     * second time.
     */
    interface Actions {

        /** Answer this turn's question again, in place of the answer that is on screen. */
        void regenerate(MessageTurn answer);

        /**
         * Ask this turn's question again, reworded.
         *
         * @param question the turn the reader edited
         * @param text     the new wording, already known to be non-blank
         */
        void editAndResend(MessageTurn question, String text);
    }

    private static final String COPY_LABEL = "Copy message";
    private static final String REGENERATE_LABEL = "Answer again";
    private static final String EDIT_LABEL = "Edit and ask again";
    private static final String SEND_EDIT_LABEL = "Ask again";
    private static final String CANCEL_EDIT_LABEL = "Discard the edit";

    private final transient Actions actions;
    private final ConversationMessage.Role role;
    private final StringBuilder raw = new StringBuilder();

    /**
     * Decides how much of the answer so far can be committed to markup. Only the assistant's turn
     * ever feeds it: the reader's own message is text they typed, not a document to be parsed.
     */
    private final transient StreamingProse prose = new StreamingProse();

    private final Span content;
    private final Div bubble;
    private final Div body;
    private final Div controls = new Div();

    /** True once the turn's text has been replaced by markup, or claimed by an error. */
    private boolean settled;

    /** True from the moment the turn is closed - completed, stopped or failed. */
    private boolean closed;

    /** True while this turn's text was this application's own apology rather than the model's. */
    private boolean failed;

    /** The store's handle on this turn, or null while the store has no record of it. */
    private String turnId;

    private Div editPanel;

    MessageTurn(ConversationMessage.Role role, String text, Actions actions) {
        this.role = Objects.requireNonNull(role, "role");
        this.actions = Objects.requireNonNull(actions, "actions");
        raw.append(Objects.requireNonNullElse(text, ""));
        content = new Span(raw.toString());

        Span label = new Span(fromUser() ? "You" : "JavaClaw");
        label.addClassName("jc-sr-only");

        bubble = new Div();
        bubble.addClassNames("jc-message__bubble", "jc-prose");
        bubble.add(raw.isEmpty() && !fromUser() ? thinking() : content);

        controls.addClassName("jc-message__actions");
        controls.getElement().setAttribute("role", "group");
        controls.getElement().setAttribute("aria-label", "Message actions");
        controls.setVisible(false);

        body = new Div(label, bubble, controls);
        body.addClassName("jc-message__body");

        addClassNames("jc-message", "jc-message--streaming");
        if (raw.isEmpty() && !fromUser()) {
            addClassName("jc-message--thinking");
        }
        getElement().setAttribute("data-role", fromUser() ? "user" : "assistant");
        add(body);
    }

    // ------------------------------------------------------------------ identity

    /**
     * Tells this turn what the store called it.
     *
     * <p>Separate from the constructor because the two orders both happen: a turn read back from
     * the store arrives with its id, and a turn that is being written gets one only once there is
     * something worth recording. Supplying it late has to be able to add the controls it unlocks,
     * so the row is rebuilt rather than assumed to have been drawn already.
     */
    void identify(String id) {
        turnId = id;
        refreshControls();
    }

    /** The store's handle on this turn, or null when the store has no record of it. */
    String turnId() {
        return turnId;
    }

    ConversationMessage.Role role() {
        return role;
    }

    /** The text as it was written or streamed, before any markdown in it was rendered. */
    String text() {
        return raw.toString();
    }

    private boolean fromUser() {
        return role == ConversationMessage.Role.USER;
    }

    // ------------------------------------------------------------------ the stream

    void append(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        boolean wasWaiting = raw.isEmpty();
        raw.append(token);
        if (wasWaiting) {
            showContent();
        }
        if (fromUser()) {
            // Nothing streams into the reader's own turn today, and if anything ever did it would
            // still be their text rather than a document.
            content.setText(raw.toString());
            return;
        }
        settle(prose.absorb(token));
        showTail();
    }

    /**
     * Puts a run of the answer that can no longer change above the part that still can.
     *
     * <p>Inserted before the tail rather than appended to the bubble, because the tail is where the
     * caret is and where the next token lands: a settled paragraph appended after it would put the
     * end of the answer above its own middle.
     *
     * @param blocks what {@link StreamingProse} has just released, possibly empty
     */
    private void settle(List<Element> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        Element parent = bubble.getElement();
        int at = parent.indexOfChild(content.getElement());
        if (at < 0) {
            // The tail is not in the bubble, which means this turn is no longer streaming into it.
            // Dropping the blocks beats appending them somewhere they do not belong.
            return;
        }
        for (Element block : blocks) {
            parent.insertChild(at, block);
            at++;
        }
    }

    /**
     * Redraws the sentence still being written.
     *
     * <p>Typesetting the tail means re-deriving it on every token: a later token can change what an
     * earlier one meant, and two asterisks are two asterisks until the closing pair lands. What does
     * <em>not</em> have to happen on every token is replacing the nodes. The transcript is a
     * {@code role="log"} region, and a log region announces what is added to it, so tearing the tail
     * down and putting it back is the answer being re-added to a live region several times a second:
     * an audit measured 289 nodes added and 284 removed carrying 48,562 characters for a final answer
     * of 772, against 1.6 characters per final character in the product this is measured against.
     *
     * <p>So the common shape is grown instead: when the fresh tail has the same shape as the drawn
     * one - same nodes, same tags, same attributes - only the text inside it is written, which is
     * one changed value per node and no structural churn at all. Anything else, including the moment
     * a delimiter closes and prose becomes markup or a table gains a row, still rebuilds: that is a
     * real change in what the answer means, and keeping the old node would freeze the answer as the
     * source it was typed in.
     *
     * <p>This is not only the ordinary-prose case. A code block arriving line by line is the shape
     * that would cost the most to rebuild - a figure, a caption, a control and one element per line
     * of source, thrown away on every token - and under this rule it is rebuilt once per line rather
     * than once per token.
     *
     * <p>Must run on the Vaadin UI thread; it writes to an attached element.
     */
    private void showTail() {
        Element element = content.getElement();
        List<Element> tail = prose.tail();
        if (element.getChildCount() != tail.size()) {
            element.removeAllChildren();
            tail.forEach(element::appendChild);
            return;
        }
        for (int i = 0; i < tail.size(); i++) {
            merge(element, i, tail.get(i));
        }
    }

    /**
     * Puts one freshly typeset node where the drawn one is, moving as little as possible.
     *
     * <p>The replacement is as deep as the change and no deeper, which is what keeps a code block
     * affordable. A line of source whose highlighting has changed because a keyword just finished
     * being typed is a genuinely different subtree and is replaced; the figure around it, its
     * caption, its copy control and the forty lines above it are not, because nothing about them
     * changed. Rebuilding from the top on any difference would throw all of that away on the token
     * that completed a word.
     *
     * @param parent the drawn parent
     * @param index  which of its children this is
     * @param fresh  what that child should now be
     */
    private static void merge(Element parent, int index, Element fresh) {
        Element drawn = parent.getChild(index);
        if (sameShape(drawn, fresh)) {
            writeInto(drawn, fresh);
            return;
        }
        parent.setChild(index, fresh);
    }

    /**
     * Whether one node can be written into the other without moving it.
     *
     * <p>Compares this node only - its tag, its attributes and how many children it has - because
     * the children are compared by {@link #merge} as it descends. Attributes count as shape: they
     * are where this parser puts meaning a reader can see, so a node that kept its tag and changed
     * its href is a different node, not the same one with new text.
     */
    private static boolean sameShape(Element drawn, Element fresh) {
        if (drawn.isTextNode() || fresh.isTextNode()) {
            return drawn.isTextNode() && fresh.isTextNode();
        }
        if (!drawn.getTag().equals(fresh.getTag())
                || drawn.getChildCount() != fresh.getChildCount()
                || !drawn.getAttributeNames().collect(Collectors.toSet())
                        .equals(fresh.getAttributeNames().collect(Collectors.toSet()))) {
            return false;
        }
        return drawn.getAttributeNames().toList().stream()
                .allMatch(name -> Objects.equals(drawn.getAttribute(name), fresh.getAttribute(name)));
    }

    /**
     * Writes a fresh node's contents onto the drawn node it matches.
     *
     * <p>The fresh children are taken as a list first. Handing one of them to {@link #merge} moves
     * it out of its own parent, which would renumber the siblings this loop has not reached yet -
     * a defect that shows up as a subtree drawn with its own children in the wrong order.
     */
    private static void writeInto(Element drawn, Element fresh) {
        if (drawn.isTextNode()) {
            if (!drawn.getText().equals(fresh.getText())) {
                drawn.setText(fresh.getText());
            }
            return;
        }
        List<Element> children = fresh.getChildren().toList();
        for (int i = 0; i < children.size(); i++) {
            merge(drawn, i, children.get(i));
        }
    }

    void markComplete() {
        closed = true;
        showContent();
        removeClassName("jc-message--streaming");
        renderAnswer();
        refreshControls();
    }

    /**
     * Replaces the streamed text with the markup it describes.
     *
     * <p>Only the assistant's turn: what the reader typed is their text, not a document, and turning
     * their asterisks into emphasis would be editing their message.
     */
    private void renderAnswer() {
        if (fromUser() || settled) {
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

    /**
     * Replaces this turn with the news that it failed.
     *
     * <p>Takes a notice rather than a message, and that is the whole of the fix for a leak: the
     * provider's own sentence used to be drawn here, so a refusal printed the inference endpoint -
     * scheme, host, port and path - into the transcript. There is now no signature on this class
     * that a provider string fits into. See {@link FailureNotice}.
     *
     * <p>Must run on the Vaadin UI thread; it rewrites an attached element.
     *
     * @param notice what the reader is told; null is treated as {@link FailureNotice#UNKNOWN}
     *               rather than drawn, because an empty failure is indistinguishable from an empty
     *               answer
     */
    void fail(FailureNotice notice) {
        String message = Objects.requireNonNullElse(notice, FailureNotice.UNKNOWN).sentence();
        // An error is this application's own sentence, not the model's markdown.
        settled = true;
        failed = true;
        closed = true;
        showContent();
        // Anything that had settled above the tail is cleared with it. It would otherwise sit under
        // the failure tint, which would present half of the model's answer as part of the apology.
        bubble.getElement().removeAllChildren();
        bubble.getElement().appendChild(content.getElement());
        content.setText(message);
        removeClassName("jc-message--streaming");
        addClassName("jc-message--failed");
        refreshControls();
    }

    /** Marks a turn the reader cut short, so a half answer is not mistaken for a whole one. */
    void stop() {
        markComplete();
        Span note = new Span("Stopped");
        note.addClassName("jc-message__note");
        body.addComponentAtIndex(body.getComponentCount() - 1, note);
    }

    /**
     * Puts the answer itself back in the bubble, in place of the waiting indicator.
     *
     * <p>Called from every path that ends the wait, including the ones that end it with nothing:
     * an answer that failed or was stopped before its first token would otherwise be left claiming
     * to be thinking about a turn that is over.
     */
    private void showContent() {
        if (!removeClassName("jc-message--thinking")) {
            return;
        }
        bubble.removeAll();
        bubble.add(content);
    }

    /**
     * The waiting state, before the model has sent anything at all.
     *
     * <p>An empty bubble with a caret in it is indistinguishable from an answer that arrived empty,
     * and the wait before the first token is the longest silence in the exchange. The dots are
     * decorative - the word beside them already says what is happening, and a screen reader should
     * not be read three bullets.
     */
    private static Span thinking() {
        Span waiting = new Span("Thinking");
        waiting.addClassName("jc-message__thinking");
        waiting.getElement().appendChild(dots());
        return waiting;
    }

    /** The animated ellipsis, shared with the composer's own busy hint. */
    static Element dots() {
        Element dots = new Element("span");
        dots.getClassList().add("jc-dots");
        dots.setAttribute("aria-hidden", "true");
        for (int i = 0; i < 3; i++) {
            Element dot = new Element("span");
            dot.getClassList().add("jc-dot");
            dots.appendChild(dot);
        }
        return dots;
    }

    // ------------------------------------------------------------------ controls

    /**
     * Rebuilds the row of controls to match what this turn can currently honour.
     *
     * <p>Rebuilt rather than shown and hidden, because what the turn can honour changes twice in
     * the ordinary case - once when it closes and once when the store hands back an id - and a row
     * assembled once would have to be right before either of those had happened.
     */
    private void refreshControls() {
        controls.removeAll();
        controls.setVisible(closed);
        if (!closed) {
            return;
        }
        // Copying this application's own apology is not something anyone wants to paste anywhere,
        // and the model's half-finished sentence is not what the error says.
        if (!failed && !raw.isEmpty()) {
            controls.add(copyControl());
        }
        if (turnId == null) {
            return;
        }
        if (fromUser()) {
            controls.add(control(EDIT_LABEL, Icons.PENCIL, button -> openEditor(), null));
        } else {
            controls.add(control(REGENERATE_LABEL, Icons.REGENERATE, button -> actions.regenerate(this), null));
        }
    }

    /**
     * One icon-only control.
     *
     * <p>Icon-only because a row of worded buttons under every turn competes with the answer it is
     * about; the name is carried on the control instead, where both a screen reader and a hovering
     * pointer will find it. A second glyph may be supplied for controls that confirm themselves -
     * it is drawn into the same button and swapped for the first by the stylesheet, so the
     * confirmation costs no round trip and builds no markup at the browser.
     */
    private NativeButton control(String label, String glyph, Consumer<NativeButton> onClick,
                                 String confirmationGlyph) {
        NativeButton button = iconButton(label, glyph, confirmationGlyph);
        button.addClickListener(event -> onClick.accept(button));
        return button;
    }

    private NativeButton iconButton(String label, String glyph, String confirmationGlyph) {
        NativeButton button = new NativeButton();
        button.addClassName("jc-msg-action");
        button.getElement().setAttribute("type", "button");
        button.getElement().setAttribute("aria-label", label);
        // The native title is the tooltip here rather than Vaadin's: a tooltip component per control
        // per turn is a lot of overlay for a row that is usually not even visible.
        button.getElement().setAttribute("title", label);
        button.add(icon(glyph, null));
        if (confirmationGlyph != null) {
            button.add(icon(confirmationGlyph, "jc-msg-action__icon--done"));
        }
        return button;
    }

    private static Span icon(String glyph, String modifier) {
        Span span = Icons.glyph(glyph);
        span.addClassName("jc-msg-action__icon");
        if (modifier != null) {
            span.addClassName(modifier);
        }
        return span;
    }

    /**
     * The control that puts this turn on the clipboard.
     *
     * <p>It carries the text and no behaviour. Copying used to be a script installed on each button
     * as it was drawn, which meant one client-side function per turn per redraw, all of them
     * identical; the transcript now installs one delegated listener for the whole conversation and
     * this control simply marks itself as something that listener may act on.
     *
     * <p>The text travels as an element property rather than inside the script or inside an
     * attribute. It is model output: concatenated into an expression it would be a script the model
     * gets to write, and reflected into an attribute it would be a second copy of the whole answer
     * sitting in the markup. A property is set through Vaadin's own protocol and never parsed.
     *
     * <p>What is copied is the answer as the model wrote it - the markdown, not the typeset text -
     * because the commonest thing a reader does with a copied answer is paste it somewhere that
     * understands markdown.
     */
    private NativeButton copyControl() {
        NativeButton button = iconButton(COPY_LABEL, Icons.COPY, Icons.CHECK);
        button.getElement().setAttribute("data-jc-copy", "message");
        button.getElement().setProperty("jcCopySource", raw.toString());
        return button;
    }

    // ------------------------------------------------------------------ editing

    /**
     * Turns the message into a field holding what it says.
     *
     * <p>In place rather than in the composer at the foot of the page. The reader is editing this
     * message, and a draft that appears 500px away from the thing it replaces makes them check
     * twice which one they are changing - and leaves the original still sitting there as though it
     * were still part of the conversation.
     */
    private void openEditor() {
        if (editPanel != null) {
            return;
        }
        TextArea editor = new TextArea();
        editor.setValue(raw.toString());
        editor.setAriaLabel("Edit your message");
        editor.addClassName("jc-message__editor");
        editor.setMinRows(1);
        editor.setMaxRows(12);
        editor.setValueChangeMode(ValueChangeMode.EAGER);
        // Escape is what every field on the web means by "leave this alone", and without it the
        // only way out of an edit is a button the reader has to go and find.
        editor.addKeyDownListener(Key.ESCAPE, event -> closeEditor());
        editor.addKeyDownListener(Key.ENTER, event -> {
            if (!event.getModifiers().contains(KeyModifier.SHIFT)) {
                submitEdit(editor);
            }
        });

        NativeButton cancel = editButton(CANCEL_EDIT_LABEL, "Cancel", false);
        cancel.addClickListener(event -> closeEditor());
        NativeButton send = editButton(SEND_EDIT_LABEL, "Ask again", true);
        send.addClickListener(event -> submitEdit(editor));

        Div buttons = new Div(cancel, send);
        buttons.addClassName("jc-message__edit-controls");

        editPanel = new Div(editor, buttons);
        editPanel.addClassName("jc-message__edit");

        bubble.setVisible(false);
        controls.setVisible(false);
        body.add(editPanel);
        addClassName("jc-message--editing");
        editor.focus();
    }

    private NativeButton editButton(String label, String text, boolean primary) {
        NativeButton button = new NativeButton(text);
        button.addClassName("jc-edit-button");
        if (primary) {
            button.addClassName("jc-edit-button--primary");
        }
        button.getElement().setAttribute("type", "button");
        button.getElement().setAttribute("aria-label", label);
        return button;
    }

    /**
     * Hands the reworded question to the view, or refuses it.
     *
     * <p>A message edited down to nothing is refused where the reader can still see it, rather than
     * accepted and turned into a question the agent has to make something of - and rather than
     * silently discarding the turn it would have replaced.
     */
    private void submitEdit(TextArea editor) {
        String edited = editor.getValue();
        if (edited == null || edited.isBlank()) {
            return;
        }
        String question = edited.strip();
        closeEditor();
        actions.editAndResend(this, question);
    }

    private void closeEditor() {
        if (editPanel == null) {
            return;
        }
        body.remove(editPanel);
        editPanel = null;
        bubble.setVisible(true);
        controls.setVisible(closed);
        removeClassName("jc-message--editing");
    }
}
