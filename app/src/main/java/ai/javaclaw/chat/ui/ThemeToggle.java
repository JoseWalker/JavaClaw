package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;

/**
 * Icon button that flips the UI between the light and the dark theme.
 *
 * <p>It advertises the mode it switches <em>to</em>: a moon while the UI is light, a sun while it is
 * dark. The glyph is inline SVG written into the button's own markup rather than a bundled asset, so
 * the control still needs no icon font and no frontend build.
 *
 * <p>The initial state is resolved on attach rather than assumed. On a first visit no choice has
 * been stored yet and the stylesheet is following {@code prefers-color-scheme}, so the browser is
 * asked what it actually rendered; guessing would leave the button showing the wrong affordance.
 */
class ThemeToggle extends Button {

    /**
     * The sizing wrapper the SVG is written into. Held as a field, and reused rather than replaced,
     * because swapping the icon is then a single property write on an element the browser already
     * has: replacing the component would detach and reattach on every toggle, which is visible.
     */
    private final Span glyph = new Span();

    /**
     * What the button currently believes is on screen. Seeded with light because that is what the
     * stylesheet renders when nothing is known, and corrected on attach once the browser has been
     * asked what it actually painted - see {@link #onAttach}. It is the button's own view of the
     * theme, not the authority: the session holds that.
     */
    private ThemeMode mode = ThemeMode.LIGHT;

    /**
     * Builds the control in its provisional light-theme state.
     *
     * <p>Nothing here reads the session. The constructor runs before the component is attached, so
     * there is no UI to resolve the effective theme against; the correction happens on attach and
     * this only has to make sure the button is never blank in the meantime.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    ThemeToggle() {
        addClassName("jc-icon-button");
        glyph.addClassName("jc-glyph");
        glyph.getElement().setAttribute("aria-hidden", "true");
        setIcon(glyph);
        // Vaadin's default would grey the button out until the server answers, which reads as a
        // failure for a control whose whole job is to respond instantly.
        setDisableOnClick(false);
        addClickListener(event -> switchTo(mode.opposite()));
        showMode(mode);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        ThemePreference.resolveEffective(attachEvent.getUI(), this::showMode);
    }

    /**
     * Records the choice, applies it to the document, and repaints the button.
     *
     * <p>The button is repainted whether or not there was a UI to write to. A detached toggle cannot
     * store anything, but leaving its glyph stale would tell the next reader the theme is the one
     * they just left - and this control's only job is to say truthfully what pressing it does.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param next the theme to move to, never null
     */
    private void switchTo(ThemeMode next) {
        getUI().ifPresent(ui -> {
            ThemePreference.store(ui.getSession(), next);
            ThemePreference.apply(ui, next);
        });
        showMode(next);
    }

    /**
     * Makes the button describe the theme that is on screen now.
     *
     * <p>The single place the glyph, the accessible name and the tooltip are set, which is why it is
     * a method and not three lines at each call site: those three all answer "what does pressing
     * this do", and a sighted reader seeing a sun beside a tooltip that says "switch to dark" is
     * exactly the disagreement that splitting them invites.
     *
     * <p>Also the only writer of {@link #mode}, so the field cannot drift from what is drawn.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param current the theme now in effect, never null
     */
    private void showMode(ThemeMode current) {
        this.mode = current;
        // The span is a sizing wrapper with no children of its own, so writing its markup here is
        // the whole of the icon swap; the SVG inherits currentColor from the button.
        glyph.getElement().setProperty("innerHTML", current.toggleGlyphSvg());
        setAriaLabel(current.toggleActionLabel());
        setTooltipText(current.toggleActionLabel());
    }
}
