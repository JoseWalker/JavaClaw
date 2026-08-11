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

        Span name = new Span("Local session");
        name.addClassName("jc-user__name");

        Span caption = new Span("Running on this machine");
        caption.addClassName("jc-user__caption");

        Div text = new Div(name, caption);
        text.addClassName("jc-user__text");

        Span chevron = Icons.glyph(Icons.CHEVRON_UP);
        chevron.addClassName("jc-user__chevron");

        NativeButton trigger = new NativeButton();
        trigger.addClassName("jc-user__trigger");
        trigger.getElement().appendChild(avatar.getElement(), text.getElement(), chevron.getElement());
        trigger.getElement().setAttribute("aria-haspopup", "menu");
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

        item(items, menu, "Keyboard shortcuts", () -> shortcutsDialog().open());
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

    private static Dialog shortcutsDialog() {
        Div rows = new Div();
        rows.addClassName("jc-shortcuts");
        shortcut(rows, "Enter", "Send the message");
        shortcut(rows, "Shift + Enter", "New line");
        shortcut(rows, "Ctrl / Cmd + B", "Show or hide the conversation list");
        shortcut(rows, "Ctrl / Cmd + K", "Jump to search");
        shortcut(rows, "Esc", "Close the conversation drawer");
        return dialog("Keyboard shortcuts", rows);
    }

    private static void shortcut(Div rows, String keys, String description) {
        Span combination = new Span(keys);
        combination.addClassName("jc-shortcuts__keys");

        Span text = new Span(description);
        text.addClassName("jc-shortcuts__label");

        Div row = new Div(combination, text);
        row.addClassName("jc-shortcuts__row");
        rows.add(row);
    }

    /**
     * What this build is, including what it is not.
     *
     * <p>The last paragraph used to be a dashed note pinned into the navigation column, above the
     * fold, on every visit. It is still said - a UI that quietly invented folders and timestamps
     * would be teaching the reader to trust it about things it does not know - but a build note is
     * something read once, and this is where things read once belong.
     */
    private static Dialog aboutDialog() {
        Div body = new Div();
        body.addClassName("jc-about");
        List.of("JavaClaw is a self-hosted agent that runs on the JVM.",
                        "This interface is served by Vaadin Flow with no Node build: the styles are a"
                                + " static stylesheet and every icon is inline SVG.",
                        "The classic htmx chat is still available and answers on the same conversation.",
                        "Some conversations in the list are placeholders. The chat store records no"
                                + " folders, tags or modification times yet, so those rows exist to"
                                + " exercise the layout and describe nothing real.")
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
