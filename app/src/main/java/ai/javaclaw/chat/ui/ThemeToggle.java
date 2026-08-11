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

    private final Span glyph = new Span();

    private ThemeMode mode = ThemeMode.LIGHT;

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

    private void switchTo(ThemeMode next) {
        getUI().ifPresent(ui -> {
            ThemePreference.store(ui.getSession(), next);
            ThemePreference.apply(ui, next);
        });
        showMode(next);
    }

    private void showMode(ThemeMode current) {
        this.mode = current;
        // The span is a sizing wrapper with no children of its own, so writing its markup here is
        // the whole of the icon swap; the SVG inherits currentColor from the button.
        glyph.getElement().setProperty("innerHTML", current.toggleGlyphSvg());
        setAriaLabel(current.toggleActionLabel());
        setTooltipText(current.toggleActionLabel());
    }
}
