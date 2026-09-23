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
 * The control at the foot of the sidebar: an icon-only trigger for the handful of shell-level
 * actions that have nowhere better to live.
 *
 * <p>JavaClaw has no accounts, so this used to name the session instead - an avatar, "This
 * session", "No account needed" - rather than pretend to be an account switcher. A reviewer looked
 * at that row and asked for it to go: there is no account behind it and none planned yet, so it was
 * a claim about identity nobody had asked to see, sitting in front of the three items that actually
 * do something. The row is gone; what opens when the trigger is pressed is not, because that part
 * was never in question. Every item in the panel still does something - a menu of greyed-out
 * promises is worse than a shorter menu.
 *
 * <h2>Why the trigger carries no text of its own</h2>
 *
 * <p>An icon-only control has no text node for a browser to compute an accessible name from, so
 * the name has to be set explicitly rather than left to fall out of the markup. It is set to the
 * same string the panel itself is labelled with, {@link #PANEL_LABEL}, so a reader who presses the
 * trigger and a reader who lands inside the open panel are told the same thing about where they are
 * - one string, not two that could drift apart.
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
     * What the panel is called out loud, and also the icon-only trigger's accessible name.
     *
     * <p>"Session actions" rather than "Session menu": it is not a menu any more, and a name that
     * says menu would put the word back in the reader's ear after the role was taken out of the
     * tree. Shared between the trigger and the panel it opens so the two cannot describe themselves
     * differently.
     */
    private static final String PANEL_LABEL = "Session actions";

    /**
     * Hands focus back to whatever had it before an overlay took it.
     *
     * <p>The shell owns the rule and this asks it to apply, rather than reading the remembered
     * element and deciding here. That split is not tidiness. This used to hold its own copy of the
     * checks, and a copy is free to fall short of the original in exactly the cases nobody drove:
     * it did nothing at all when the memory was empty - a reader who pressed Ctrl/Cmd + / before
     * touching anything - and nothing again when the remembered control had been removed while the
     * dialog was open. Both left the reader on the document body, which for a keyboard-only reader
     * means tabbing the whole page again. The shell answers both with the content landmark, and it
     * answers them once for every overlay rather than once per overlay that remembered to ask.
     *
     * <p>Guarded on the function existing because this component is reachable from a shell that has
     * not installed it yet in a test, and an overlay that throws on close is worse than one that
     * closes without moving the caret.
     */
    private static final String RETURN_FOCUS = """
            if (typeof window.__jcRestoreFocus === 'function') {
                window.__jcRestoreFocus();
            }
            """;

    /**
     * Builds the whole control - trigger, panel and every item - in one go.
     *
     * <p>Assembled in the constructor rather than lazily on first open because the panel's contents
     * are fixed: there is no data to wait for, and a lazily built popover is a popover whose first
     * open is measurably slower and whose accessibility wiring has a window in which it is missing.
     *
     * <p>The trigger itself carries only the disclosure chevron - no avatar, no session-identity
     * text. That row was dropped on review (see the class Javadoc) because this application has no
     * accounts to display; the panel it opens, and every item in it, is unchanged.
     *
     * <p>Must run on the Vaadin UI thread, like any component construction that is about to be added
     * to a live layout.
     */
    UserMenu() {
        addClassName("jc-user");

        Span chevron = Icons.glyph(Icons.CHEVRON_UP);
        chevron.addClassName("jc-user__chevron");

        NativeButton trigger = new NativeButton();
        trigger.addClassName("jc-user__trigger");
        trigger.getElement().appendChild(chevron.getElement());
        // No text node on an icon-only button for the browser to name it from, so the accessible
        // name is set explicitly - and set to PANEL_LABEL rather than separate wording, so the
        // trigger and the panel it opens are never one keystroke away from disagreeing about what
        // this control is called.
        trigger.setAriaLabel(PANEL_LABEL);
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

    /**
     * Adds one entry to the panel, wired to close the menu before it acts.
     *
     * <p>The ordering is the reason this is a helper rather than four inline lambdas. Everything an
     * item opens covers the panel, and a popover left open underneath a dialog stays in the tab
     * order beneath it, so closing has to happen first and has to happen the same way every time.
     * Writing it once is what keeps a later fifth item from getting it wrong.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param panel the container the entry is appended to
     * @param menu the popover to close before the action runs
     * @param label the entry's visible text, which is also its accessible name
     * @param action what the entry does once the menu is out of the way
     */
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
     *
     * @return a fresh dialog each call, closed; the caller opens it
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
     * behaviour. When there is no such element - nothing focused yet, or the control that opened
     * the dialog removed while it was open - the shell falls back to the content landmark, so no
     * exit from this dialog can end with the reader on the document body.
     *
     * <p>Hung on the dialog's own state rather than on any control, because there are three ways
     * out and only one of them is a control: Escape and a click outside are the web component
     * closing itself and telling the server afterwards. All three end in the same opened-change, so
     * one listener is what makes the three exits behave alike; a handler on the footer button would
     * have covered the exit a keyboard reader is least likely to use.
     *
     * <p>The listener runs on the Vaadin UI thread. What it queues runs on the browser's.
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
