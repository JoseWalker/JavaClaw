package ai.javaclaw.chat.ui;

/**
 * The two colour schemes the UI ships with.
 *
 * <p>The stylesheet holds one token table whose colours are declared with {@code light-dark()}, so a
 * mode is nothing more than a {@code color-scheme}. {@link #attributeValue()} is what gets written
 * to the {@code theme} attribute of the document element; it doubles as the value Vaadin's own Lumo
 * stylesheet reacts to, which is why the built-in components follow along without extra rules.
 */
public enum ThemeMode {

    LIGHT("light", Glyphs.MOON, "Switch to dark theme"),
    DARK("dark", Glyphs.SUN, "Switch to light theme");

    /**
     * Inline SVG for the toggle, held in a nested class so the enum constructor can read it.
     *
     * <p>Both icons are stroked at the same width and drawn to the same 16-unit box, so the header
     * control keeps its optical weight when the theme flips. Inline SVG in the DOM is not a frontend
     * asset: it needs no bundler, no {@code @JsModule} and no Node, and {@code currentColor} still
     * inherits the button's hover and disabled colours.
     */
    private static final class Glyphs {

        private static final String OPEN = "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\""
                + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\" focusable=\"false\">";

        private static final String SUN = OPEN
                + "<circle cx=\"8\" cy=\"8\" r=\"3.1\"/>"
                + "<path d=\"M8 1.3v1.5M8 13.2v1.5M1.3 8h1.5M13.2 8h1.5"
                + "M3.3 3.3l1.1 1.1M11.6 11.6l1.1 1.1M12.7 3.3l-1.1 1.1M4.4 11.6l-1.1 1.1\"/>"
                + "</svg>";

        private static final String MOON = OPEN
                + "<path d=\"M13.4 9.7A5.9 5.9 0 0 1 6.3 2.6 5.9 5.9 0 1 0 13.4 9.7Z\"/>"
                + "</svg>";

        private Glyphs() {
        }
    }

    private final String attributeValue;
    private final String toggleGlyphSvg;
    private final String toggleActionLabel;

    ThemeMode(String attributeValue, String toggleGlyphSvg, String toggleActionLabel) {
        this.attributeValue = attributeValue;
        this.toggleGlyphSvg = toggleGlyphSvg;
        this.toggleActionLabel = toggleActionLabel;
    }

    /**
     * Value written to {@code <html theme="...">}. Also the value Lumo uses for its dark variant.
     */
    public String attributeValue() {
        return attributeValue;
    }

    /**
     * Markup for the glyph the toggle shows. The toggle advertises the mode it switches <em>to</em>,
     * not the one currently applied, so a moon appears while the UI is light.
     */
    public String toggleGlyphSvg() {
        return toggleGlyphSvg;
    }

    /**
     * Accessible name for the toggle. Icon-only controls have no text to fall back on, so this is
     * the only thing a screen reader announces.
     */
    public String toggleActionLabel() {
        return toggleActionLabel;
    }

    public ThemeMode opposite() {
        return this == LIGHT ? DARK : LIGHT;
    }

    /**
     * Resolves a stored attribute value back to a mode, tolerating anything unrecognised.
     *
     * @param attributeValue value previously produced by {@link #attributeValue()}, possibly null
     * @return the matching mode, or {@code null} when the value is absent or not one of ours
     */
    static ThemeMode fromAttributeValue(String attributeValue) {
        for (ThemeMode mode : values()) {
            if (mode.attributeValue.equals(attributeValue)) {
                return mode;
            }
        }
        return null;
    }
}
