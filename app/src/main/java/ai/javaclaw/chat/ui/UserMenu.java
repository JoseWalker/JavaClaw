package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;

import java.util.List;

/**
 * The control at the foot of the sidebar: who is using the app, and what else it can do.
 *
 * <p>JavaClaw has no accounts, so this does not pretend to be an account switcher. It names the
 * session honestly and carries the handful of shell-level actions that have nowhere better to live.
 * Every item does something - a menu of greyed-out promises is worse than a shorter menu.
 *
 * <h2>Why the caption does not say where anything runs</h2>
 *
 * <p>It used to read "Running on this machine", which this class had no way of knowing. The agent
 * runs here; the model may not, and on the install this was written against it did not - the bar
 * two hundred pixels away named a {@code -cloud} model at the same moment. A claim about locality is
 * also the one claim a reader of a self-hosted tool will act on, so getting it wrong is not a
 * cosmetic error: it is the chrome telling someone their conversation stayed on their own hardware
 * when it did not.
 *
 * <p>The same application ships a DRAFT badge so an unsaved thread cannot be mistaken for a saved
 * one, and refuses to print a modification time it did not observe. A hardcoded locality claim in
 * the one piece of chrome that is always on screen contradicts both. What is left is what this class
 * can actually verify from where it stands: there is no account, and the session belongs to this
 * browser. Where the model runs is stated once, by the bar, from configuration.
 *
 * <h2>Why the panel is a popover and not a context menu</h2>
 *
 * <p>A context menu opens where the pointer was. Anchored to a full-width row at the foot of a
 * 280px column that put the panel 42px outside the column and over the row that opened it, which is
 * a placement chosen by a default rather than by the design. A popover is anchored to its trigger:
 * {@link PopoverPosition#TOP_START} shares the trigger's inline start and opens upward, because the
 * trigger is at the bottom of the screen and there is nowhere below it to open into.
 *
 * <h2>Why it is no longer called a menu</h2>
 *
 * <p>It used to declare {@code role="menu"} over three {@code role="menuitem"} children, and then
 * ignore ArrowDown, ArrowUp and Home in four attempts out of four. That is not an imperfect menu,
 * it is a false statement: the role's entire content is a promise about which keys work, and a
 * reader who is told they are in a menu presses the keys a menu answers to and concludes the panel
 * is broken. It also nested two menu roles - one on the items container, one on the overlay - so
 * the accessibility tree carried an unnamed inner menu level nobody intended.
 *
 * <p>Two ways out: implement roving tabindex with Arrow, Home and End, or stop claiming to be a
 * menu. This is three buttons that answer to Enter and Space, which is what a button is, so the
 * claim went rather than the behaviour. What is left is a disclosure - a trigger with
 * {@code aria-expanded}, a named group, three ordinary buttons - and every part of that is true.
 */
class UserMenu extends Div {

    /** The pre-Vaadin chat UI, still served outside the Vaadin URL mapping. */
    private static final String CLASSIC_CHAT_PATH = "/chat";

    /**
     * The trigger's own width: the sidebar column less its two gutters and its divider. Set on the
     * panel so the two read as one control rather than as an overlay that happens to be nearby.
     */
    private static final String PANEL_WIDTH = "15.9375rem";

    /**
     * What the panel is called out loud.
     *
     * <p>"Session actions" rather than "Session menu": it is not a menu any more, and a name that
     * says menu would put the word back in the reader's ear after the role was taken out of the
     * tree.
     */
    private static final String PANEL_LABEL = "Session actions";

    /**
     * Hands focus back to whatever had it before an overlay took it.
     *
     * <p>The element is remembered by the shell, which watches focus move and keeps the last one
     * that was not inside an overlay. Everything here is a check on that value rather than a use of
     * it: a remembered element can have been removed from the document since, and a stored
     * reference to something that is no longer focusable is exactly the case that produced the
     * defect this fixes. Nothing is assumed, so nothing throws.
     */
    private static final String RETURN_FOCUS = """
            const target = window.__jcFocusReturn;
            if (target && target.isConnected && typeof target.focus === 'function') {
                target.focus();
            }
            """;

    UserMenu() {
        addClassName("jc-user");

        Span avatar = Icons.glyph(Icons.PERSON);
        avatar.addClassName("jc-user__avatar");

        Span name = new Span("This session");
        name.addClassName("jc-user__name");

        Span caption = new Span("No account needed");
        caption.addClassName("jc-user__caption");

        Div text = new Div(name, caption);
        text.addClassName("jc-user__text");

        Span chevron = Icons.glyph(Icons.CHEVRON_UP);
        chevron.addClassName("jc-user__chevron");

        NativeButton trigger = new NativeButton();
        trigger.addClassName("jc-user__trigger");
        trigger.getElement().appendChild(avatar.getElement(), text.getElement(), chevron.getElement());
        // A disclosure, not a menu button: aria-expanded says something is open, which is the whole
        // of what this control promises. See the class note on why aria-haspopup="menu" is gone.
        trigger.getElement().setAttribute("aria-expanded", "false");
        add(trigger);

        Div items = new Div();
        items.addClassName("jc-menu");
        // Deliberately no role. The panel it sits in is already named as a group, and a second role
        // here is what put an unnamed inner menu level in the accessibility tree.

        Popover menu = new Popover(items);
        menu.setTarget(trigger);
        menu.setPosition(PopoverPosition.TOP_START);
        menu.setWidth(PANEL_WIDTH);
        menu.setRole("group");
        menu.setAriaLabel(PANEL_LABEL);
        // Focus goes into the panel when it opens, and it has to. The panel is teleported to the
        // end of the document, so a reader who left it unfocused could not Tab to it: the three
        // buttons were on screen and unreachable without a pointer. This also gives the panel a
        // focus trap and hands focus back to the trigger when it closes, which is what makes the
        // trip reversible. It used to be off, to keep an accent ring from appearing the moment the
        // panel opened - :focus-visible settles that on its own, because a panel opened by pointer
        // draws no ring and one opened by keyboard draws the ring the reader asked for.
        menu.setAutofocus(true);
        menu.setModal(true, false);
        menu.setOpenOnClick(true);
        menu.setOpenOnHover(false);
        menu.setOpenOnFocus(false);
        // Escape is what closes a panel everywhere; the shell's own Escape shortcut is registered
        // with propagation and the browser default left alone so this one still hears it.
        menu.setCloseOnEsc(true);
        menu.addOpenedChangeListener(event -> {
            // A trigger that says something opens, and never says whether it is open, tells a
            // screen reader half of what it needs. The panel is an overlay elsewhere in the DOM, so
            // this is the only place the state can be read from.
            trigger.getElement().setAttribute("aria-expanded", String.valueOf(event.isOpened()));
            if (!event.isOpened()) {
                restoreFocusTo(trigger);
            }
        });

        item(items, menu, "Keyboard shortcuts", this::showKeyboardShortcuts);
        item(items, menu, "Open the classic chat", UserMenu::openClassicChat);
        item(items, menu, "About JavaClaw", () -> aboutDialog().open());
    }

    /**
     * Puts the caret back on a control after the overlay it opened has gone.
     *
     * <p>Only when it would otherwise be lost. Closing this panel is not always the reader leaving
     * it - they may have clicked a conversation row, which closes the panel as a side effect - and
     * dragging focus back to the trigger in that case would take the row they just chose away from
     * them. The one case worth correcting is the one where the element holding focus has just been
     * removed, which browsers resolve by dropping focus on the document body.
     *
     * @param control the control to return to
     */
    private static void restoreFocusTo(NativeButton control) {
        control.getElement().executeJs("""
                if (!document.activeElement || document.activeElement === document.body) {
                    this.focus();
                }
                """);
    }

    private static void item(Div panel, Popover menu, String label, Runnable action) {
        NativeButton entry = new NativeButton(label, event -> {
            // Closed first: what every item opens covers the panel, and an overlay left open behind
            // a dialog is still in the tab order underneath it.
            menu.close();
            action.run();
        });
        entry.addClassName("jc-menu__item");
        // Deliberately no role. These are buttons, they answer to Enter and Space, and that is what
        // a button is. Calling them menu items promised Arrow, Home and End - which they ignored in
        // four attempts out of four - so the role was not a description, it was a false claim about
        // which keys work. Half a menu is worse than no menu.
        panel.add(entry);
    }

    /**
     * Leaves the Vaadin UI for the htmx one.
     *
     * <p>A router navigation cannot reach it: Vaadin owns {@code /ui/*} and the classic chat lives
     * outside that mapping, so this has to be a real page load.
     */
    private static void openClassicChat() {
        UI.getCurrent().getPage().setLocation(CLASSIC_CHAT_PATH);
    }

    /**
     * Shows every keystroke the interface answers to.
     *
     * <p>Visible to the column above so the same dialog can be opened from the keyboard as well as
     * from this menu. A help panel that could only be reached with a pointer would be the one piece
     * of this feature its own audience could not use.
     *
     * <p>The rows are not written here. They used to be, and two of the five were wrong within a
     * round; they now come from {@link Shortcut}, which is also what the shell registers from, so
     * the list and the behaviour cannot disagree.
     */
    void showKeyboardShortcuts() {
        Div body = new Div(Shortcut.helpRows());
        dialog("Keyboard shortcuts", body).open();
    }

    /**
     * What this build is, including what it is not.
     *
     * <p>The last paragraph is the one that earns its place. The conversation list used to be padded
     * out with invented chats filed under invented folders; they are gone, and what is left is a
     * shorter list and a plain statement of what the store cannot yet answer. A build note is
     * something read once, and this is where things read once belong - not pinned above the fold in
     * the navigation column, which is where it used to live.
     */
    private static Dialog aboutDialog() {
        Div body = new Div();
        body.addClassName("jc-about");
        List.of("JavaClaw is a self-hosted agent that runs on the JVM.",
                        "This interface is served by Vaadin Flow with no Node build: the styles are a"
                                + " static stylesheet and every icon is inline SVG.",
                        "The classic htmx chat is still available. It keeps its own conversation,"
                                + " which appears in the list here like any other.",
                        "Every chat in the list is one the agent really holds. The store records no"
                                + " folders, tags or modification times, so the headings are the"
                                + " channel each conversation arrived on, there are no tags, and a"
                                + " time appears only where this session watched the conversation"
                                + " being used.")
                .forEach(text -> body.add(new Paragraph(text)));
        return dialog("About JavaClaw", body);
    }

    /**
     * The chrome every dialog this application opens shares, including how it gives focus back.
     *
     * <p>The restore is the part that had to be added, and the reason is worth writing down. The
     * browser's own restore target is whatever had focus when the dialog opened, which works
     * exactly until that element stops existing - and opened from this panel, it always does: the
     * control the reader pressed is a button inside an overlay that is closed on the way in. So
     * Escape from the shortcuts dialog dropped focus on the document body when it was opened from
     * the panel and returned correctly when it was opened with Ctrl/Cmd + /. One dialog, two
     * behaviours, decided by which door the reader came through.
     *
     * <p>What is restored is therefore the last element focused <em>outside</em> any overlay, which
     * the shell keeps track of. That is the trigger in the panel case and the reader's previous
     * position in the keyboard case - the same rule, stated once, giving both doors the same
     * behaviour.
     *
     * @param title the dialog's heading
     * @param body  its content
     * @return a dialog that has not been opened yet
     */
    private static Dialog dialog(String title, Div body) {
        Dialog dialog = new Dialog(body);
        dialog.setHeaderTitle(title);
        dialog.setCloseOnEsc(true);
        dialog.setCloseOnOutsideClick(true);
        dialog.addClassName("jc-dialog");

        NativeButton close = new NativeButton("Close", event -> dialog.close());
        close.addClassName("jc-dialog__close");
        dialog.getFooter().add(close);

        dialog.addOpenedChangeListener(event -> {
            if (event.isOpened()) {
                return;
            }
            dialog.getElement().executeJs(RETURN_FOCUS);
        });
        return dialog;
    }
}
