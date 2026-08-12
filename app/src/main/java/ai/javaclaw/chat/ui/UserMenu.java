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
 */
class UserMenu extends Div {

    /** The pre-Vaadin chat UI, still served outside the Vaadin URL mapping. */
    private static final String CLASSIC_CHAT_PATH = "/chat";

    /**
     * The trigger's own width: the sidebar column less its two gutters and its divider. Set on the
     * panel so the two read as one control rather than as an overlay that happens to be nearby.
     */
    private static final String PANEL_WIDTH = "15.9375rem";

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
        trigger.getElement().setAttribute("aria-haspopup", "menu");
        trigger.getElement().setAttribute("aria-expanded", "false");
        add(trigger);

        Div items = new Div();
        items.addClassName("jc-menu");
        items.getElement().setAttribute("role", "menu");

        Popover menu = new Popover(items);
        menu.setTarget(trigger);
        menu.setPosition(PopoverPosition.TOP_START);
        menu.setWidth(PANEL_WIDTH);
        menu.setRole("menu");
        menu.setAriaLabel("Session menu");
        // Nothing is focused on open. An accent ring on the first item the moment a menu appears
        // reads as an error state; a reader who arrived by keyboard tabs into the panel and gets
        // the same ring, which is then a ring they asked for.
        menu.setAutofocus(false);
        menu.setOpenOnClick(true);
        menu.setOpenOnHover(false);
        menu.setOpenOnFocus(false);
        // Escape is what closes a menu everywhere; the shell's own Escape shortcut is registered
        // with propagation and the browser default left alone so this one still hears it.
        menu.setCloseOnEsc(true);
        // A trigger that says it opens a menu, and never says whether the menu is open, tells a
        // screen reader half of what it needs. The panel is an overlay elsewhere in the DOM, so
        // this is the only place the state can be read from.
        menu.addOpenedChangeListener(event ->
                trigger.getElement().setAttribute("aria-expanded", String.valueOf(event.isOpened())));

        item(items, menu, "Keyboard shortcuts", this::showKeyboardShortcuts);
        item(items, menu, "Open the classic chat", UserMenu::openClassicChat);
        item(items, menu, "About JavaClaw", () -> aboutDialog().open());
    }

    private static void item(Div panel, Popover menu, String label, Runnable action) {
        NativeButton entry = new NativeButton(label, event -> {
            // Closed first: what every item opens covers the panel, and an overlay left open behind
            // a dialog is still in the tab order underneath it.
            menu.close();
            action.run();
        });
        entry.addClassName("jc-menu__item");
        entry.getElement().setAttribute("role", "menuitem");
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

    private static Dialog dialog(String title, Div body) {
        Dialog dialog = new Dialog(body);
        dialog.setHeaderTitle(title);
        dialog.setCloseOnEsc(true);
        dialog.setCloseOnOutsideClick(true);
        dialog.addClassName("jc-dialog");

        NativeButton close = new NativeButton("Close", event -> dialog.close());
        close.addClassName("jc-dialog__close");
        dialog.getFooter().add(close);
        return dialog;
    }
}
