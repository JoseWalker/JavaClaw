package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.ShortcutRegistration;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.server.Command;

import java.util.List;
import java.util.Objects;

/**
 * Every keystroke this interface answers to, written down once.
 *
 * <h2>Why a table and not a call site</h2>
 *
 * <p>The shell used to register two shortcuts in {@code onAttach} and describe five in a dialog on
 * the other side of the application, maintained by hand. Two of the five were wrong within a round.
 * A shortcut nobody can find is not a feature, it is a trap the reader falls into by accident, so
 * the registration and the help are now the same declaration: the dialog is generated from these
 * constants, and a new constant that is never registered still appears in the list rather than
 * silently existing. The reverse - a registration with no entry - cannot be written at all, because
 * there is nothing to register except a constant from this table.
 *
 * <h2>Why "primary" rather than Ctrl</h2>
 *
 * <p>The same shortcut is Ctrl on a PC keyboard and Cmd on a Mac one, and Vaadin matches modifiers
 * exactly: a registration for Ctrl does not fire when Cmd is held, and a registration with no
 * modifier does not fire when one is. So a primary-modifier shortcut is two registrations, which is
 * a detail every call site would otherwise have to remember - and the one that forgets it ships a
 * product that works on one half of the machines it runs on.
 *
 * <h2>Which keys, and why not the obvious ones</h2>
 *
 * <p>Ctrl/Cmd + B and Ctrl/Cmd + K are what a sidebar toggle and a search box are called in every
 * editor and every chat product, so they are not up for redesign. Ctrl/Cmd + Shift + O for a new
 * chat is what the two nearest comparable products use. Shift + Escape would be the conventional
 * way to reach the composer and is deliberately not used: Chrome takes it for its own task manager
 * before the page ever sees it, and a shortcut the browser eats is a shortcut that appears broken.
 */
enum Shortcut {

    /** Handled by the composer's own key listener - see {@code ChatView}. */
    SEND(Chord.field(Key.ENTER, "Enter", false), "Send the message"),

    /** Also the composer's, and the reason Enter alone can be the send key at all. */
    NEW_LINE(Chord.field(Key.ENTER, "Enter", true), "Start a new line without sending"),

    /**
     * One key, two exits, and never both at once: the drawer only overlays the transcript on a
     * phone, where a turn is rarely streaming under it.
     */
    ESCAPE(Chord.plain(Key.ESCAPE, "Escape", "Esc"),
            "Stop the answer being written, or close the conversation drawer"),

    /**
     * Takes Shift because Ctrl/Cmd + M is a browser command on some platforms, and M rather than L
     * because the conventional Shift + Escape is eaten by Chrome before the page sees it - see the
     * class note.
     */
    FOCUS_COMPOSER(Chord.primary(Key.KEY_M, "M", true), "Put the caret in the message box"),

    /** Ctrl/Cmd + Shift + O, which is what the two nearest comparable products call a new chat. */
    NEW_CHAT(Chord.primary(Key.KEY_O, "O", true), "Start a new chat"),

    /** Ctrl/Cmd + B, which is what a sidebar toggle is called in every editor worth copying. */
    TOGGLE_SIDEBAR(Chord.primary(Key.KEY_B, "B", false), "Show or hide the conversation list"),

    /** Ctrl/Cmd + K, the near-universal "find something in this application" chord. */
    SEARCH(Chord.primary(Key.KEY_K, "K", false), "Search the conversation list"),

    /** The entry that shows the rest, which is why it is the one that has to be guessable. */
    HELP(Chord.primary(Key.SLASH, "/", false), "Show this list of shortcuts");

    /** The keystroke itself, in every vocabulary it has to be expressed in. See {@link Chord}. */
    private final Chord chord;

    /**
     * What the shortcut does, in the imperative, as one line a reader can scan in the help dialog.
     * Written here rather than at the registration site so the dialog and the behaviour cannot
     * disagree - which is the defect the class note records having shipped twice.
     */
    private final String description;

    /**
     * Binds a shortcut to its chord and its description. Called only by the constants above.
     *
     * @param chord the keystroke, built through one of the {@link Chord} factories so that the
     *     global-versus-field distinction is decided here rather than at a registration site
     * @param description what the keystroke does, in the imperative
     */
    Shortcut(Chord chord, String description) {
        this.chord = chord;
        this.description = description;
    }

    /**
     * One chord, in the three vocabularies it has to be expressed in.
     *
     * @param key      what Vaadin matches on
     * @param ariaKey  the {@code KeyboardEvent.key} value, which is what {@code aria-keyshortcuts}
     *                 is defined in terms of - "Escape", never "Esc"
     * @param label    what a reader is shown, which is the opposite convention - "Esc", never
     *                 "Escape"
     * @param primary  true when the chord takes Ctrl on a PC keyboard and Cmd on a Mac one
     * @param shift    true when Shift is part of the chord
     * @param global   false for a key that belongs to whichever field the caret is in, and must
     *                 therefore never be registered on the shell
     */
    private record Chord(Key key, String ariaKey, String label, boolean primary, boolean shift,
                         boolean global) {

        /**
         * A page-level chord on the platform-appropriate primary modifier.
         *
         * <p>The three factories exist so that a constant declares what kind of shortcut it is
         * rather than filling in six positional arguments, where {@code true, false, true} is a
         * mistake nobody can see in review. This one is the case where the label and the ARIA name
         * coincide, because a letter key is called the same thing in both vocabularies.
         *
         * @param key what Vaadin matches on
         * @param label the single key, as both shown and announced - "M", "B", "/"
         * @param shift true when Shift is part of the chord
         * @return a global chord that registers on Control and on Meta
         */
        static Chord primary(Key key, String label, boolean shift) {
            return new Chord(key, label, label, true, shift, true);
        }

        /**
         * A page-level chord with no modifier, where the shown and announced names differ.
         *
         * <p>The only reason this factory takes both names is Escape: {@code aria-keyshortcuts} is
         * defined in terms of {@code KeyboardEvent.key} and must say "Escape", while a reader
         * expects to see "Esc" on the page. Collapsing the two would make one of them wrong.
         *
         * @param key what Vaadin matches on
         * @param ariaKey the {@code KeyboardEvent.key} value
         * @param label what the reader is shown
         * @return a global chord with no modifiers
         */
        static Chord plain(Key key, String ariaKey, String label) {
            return new Chord(key, ariaKey, label, false, false, true);
        }

        /**
         * A chord that belongs to whichever field the caret is in, and never to the page.
         *
         * <p>Marked not global so {@link Shortcut#register} refuses it outright. Enter registered on
         * the shell would send the composer's draft from anywhere on the page - from the sidebar's
         * search box included - so the distinction is enforced by the type rather than remembered.
         *
         * @param key what the field's own listener matches on
         * @param label the key as both shown and announced
         * @param shift true when Shift is part of the chord
         * @return a field-scoped chord, which cannot be registered globally
         */
        static Chord field(Key key, String label, boolean shift) {
            return new Chord(key, label, label, false, shift, false);
        }
    }

    /**
     * What the reader is shown, in the dialog and in the tooltips.
     *
     * @return the chord as a reader sees it, never null; a primary-modifier chord spells out both
     *     modifiers, because the server cannot tell which keyboard is reading it
     */
    String keys() {
        StringBuilder shown = new StringBuilder();
        if (chord.primary()) {
            // Both, spelled out. "Cmd" alone would be wrong on the machines this is self-hosted on
            // as often as it is right, and the shell cannot tell which one is reading it.
            shown.append("Ctrl / Cmd + ");
        }
        if (chord.shift()) {
            shown.append("Shift + ");
        }
        return shown.append(chord.label()).toString();
    }

    /**
     * What this keystroke does, for the help dialog.
     *
     * @return one imperative line, never null and never blank
     */
    String description() {
        return description;
    }

    /**
     * The same chord as {@code aria-keyshortcuts}, which is how a screen reader tells its user that
     * a control has a keystroke without anyone having to open the help.
     *
     * @return one space-separated token per chord: two when the primary modifier is involved,
     *         because Control and Meta are two different keystrokes to everything that reads this
     */
    String ariaKeyShortcuts() {
        if (chord.primary()) {
            return token("Control+") + " " + token("Meta+");
        }
        return token("");
    }

    /**
     * One {@code aria-keyshortcuts} token, built in the order the specification fixes.
     *
     * <p>Modifiers have to appear before the key and Shift after the primary one; assistive
     * technology matches the string, so a token assembled in another order is a shortcut that is
     * never announced. Writing it in one place is what keeps the Control and the Meta token from
     * being assembled two slightly different ways.
     *
     * @param primaryModifier {@code "Control+"}, {@code "Meta+"}, or an empty string for a chord
     *     that has no primary modifier
     * @return a single token, never null
     */
    private String token(String primaryModifier) {
        return primaryModifier + (chord.shift() ? "Shift+" : "") + chord.ariaKey();
    }

    /**
     * A control's tooltip with its keystroke appended.
     *
     * <p>A dialog nobody opens teaches nobody anything. This is the other half of discoverability:
     * the keystroke is on the control that performs the action, where a reader who is already
     * reaching for it will read it once and stop reaching.
     *
     * @param action what the control does, in the imperative
     * @return the action followed by the chord in parentheses
     */
    String tooltip(String action) {
        return action + " (" + keys() + ")";
    }

    /**
     * False for a keystroke that belongs to a field rather than to the page.
     *
     * @return false for a keystroke that belongs to a field, which {@link #register} refuses
     */
    boolean isGlobal() {
        return chord.global();
    }

    /**
     * Registers this shortcut for as long as the owner is attached.
     *
     * <p>Refuses a chord this table has declared to belong to a field. Registering Enter on the
     * shell would send the composer's draft from anywhere on the page, including from the search
     * box in the conversation column - which is why the refusal is an exception rather than a
     * silent no-op: it is a programming error, and it is one that only shows up in use.
     *
     * @param owner  the component whose lifecycle the registration follows
     * @param action what the keystroke does
     * @return one registration per chord, so a caller can relax propagation on all of them
     * @throws IllegalStateException when the chord is not a global one
     */
    List<ShortcutRegistration> register(Component owner, Command action) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(action, "action");
        if (!chord.global()) {
            throw new IllegalStateException(this + " belongs to a field and is not registered globally");
        }
        if (!chord.primary()) {
            return List.of(Shortcuts.addShortcutListener(owner, action, chord.key(), modifiers()));
        }
        return List.of(
                Shortcuts.addShortcutListener(owner, action, chord.key(),
                        with(KeyModifier.CONTROL)),
                Shortcuts.addShortcutListener(owner, action, chord.key(),
                        with(KeyModifier.META)));
    }

    /**
     * The modifiers for a chord that has no primary one.
     *
     * <p>An empty array rather than null, because Vaadin's varargs registration treats the absence
     * of modifiers as "this key with nothing held" - which is exactly what is wanted - while null
     * would be a dereference inside the framework.
     *
     * @return a fresh array each call, since the caller passes it into a varargs parameter that is
     *     free to retain it
     */
    private KeyModifier[] modifiers() {
        return chord.shift() ? new KeyModifier[] {KeyModifier.SHIFT} : new KeyModifier[0];
    }

    /**
     * The modifiers for one half of a primary-modifier registration.
     *
     * <p>Vaadin matches modifiers exactly, so a chord that means "Ctrl on a PC, Cmd on a Mac" is two
     * registrations rather than one; this builds the modifier set for whichever half is being
     * registered. Getting it wrong ships an application that works on half the keyboards it runs on,
     * which is why neither call site assembles the array itself.
     *
     * @param primaryModifier {@link KeyModifier#CONTROL} or {@link KeyModifier#META}
     * @return a fresh array each call, for the same varargs reason as {@link #modifiers()}
     */
    private KeyModifier[] with(KeyModifier primaryModifier) {
        return chord.shift()
                ? new KeyModifier[] {primaryModifier, KeyModifier.SHIFT}
                : new KeyModifier[] {primaryModifier};
    }

    /**
     * Names a control with its own keystroke, for whoever is listening rather than looking.
     *
     * @param control the element to name, which is the control the keystroke operates
     */
    void announceOn(Element control) {
        control.setAttribute("aria-keyshortcuts", ariaKeyShortcuts());
    }

    /**
     * The whole table, as the rows a dialog can show.
     *
     * <p>Built here rather than in the dialog so there is no second list to keep in step. The
     * chrome around it - the title, the close control - stays with {@code UserMenu}, which owns
     * every other dialog this application opens.
     *
     * @return a fresh component each time, because a component belongs to one place in one DOM
     */
    static Component helpRows() {
        Div rows = new Div();
        rows.addClassName("jc-shortcuts");
        for (Shortcut shortcut : values()) {
            Span combination = new Span(shortcut.keys());
            combination.addClassName("jc-shortcuts__keys");

            Span text = new Span(shortcut.description());
            text.addClassName("jc-shortcuts__label");

            Div row = new Div(combination, text);
            row.addClassName("jc-shortcuts__row");
            rows.add(row);
        }
        return rows;
    }
}
