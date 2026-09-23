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

    /** The default scheme. Carries the moon, because from here the toggle leads to the dark one. */
    LIGHT("light", Glyphs.MOON, "Switch to dark theme"),

    /** The dark scheme. Carries the sun, for the same reason in the other direction. */
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

        /**
         * The opening tag both glyphs share, written once so the two cannot drift apart in stroke
         * width or box size and start looking like different weights when the theme flips.
         * {@code focusable="false"} keeps the SVG out of the tab order on engines that put it in.
         */
        private static final String OPEN = "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\""
                + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\" focusable=\"false\">";

        /** A disc with rays: shown while the dark theme is applied, because it leads back to light. */
        private static final String SUN = OPEN
                + "<circle cx=\"8\" cy=\"8\" r=\"3.1\"/>"
                + "<path d=\"M8 1.3v1.5M8 13.2v1.5M1.3 8h1.5M13.2 8h1.5"
                + "M3.3 3.3l1.1 1.1M11.6 11.6l1.1 1.1M12.7 3.3l-1.1 1.1M4.4 11.6l-1.1 1.1\"/>"
                + "</svg>";

        /** A crescent: shown while the light theme is applied, because it leads to the dark one. */
        private static final String MOON = OPEN
                + "<path d=\"M13.4 9.7A5.9 5.9 0 0 1 6.3 2.6 5.9 5.9 0 1 0 13.4 9.7Z\"/>"
                + "</svg>";

        /**
         * Not instantiable. The class exists only to give the enum constructor something it is
         * allowed to read: constants of the enum itself are not initialised yet at that point.
         */
        private Glyphs() {
        }
    }

    /** The token written to {@code <html theme="...">}, and the one Lumo's dark variant matches. */
    private final String attributeValue;

    /** Markup for the glyph the toggle shows while <em>this</em> mode is the one on screen. */
    private final String toggleGlyphSvg;

    /** What the toggle is called while this mode is applied; an icon-only control has nothing else. */
    private final String toggleActionLabel;

    /**
     * Binds a mode to its attribute token and to how the toggle presents it. Called only by the
     * constants above.
     *
     * <p>The glyph and the label describe the <em>other</em> mode, because they describe what
     * pressing the toggle will do. Pairing them in one constructor call is what keeps a later edit
     * from changing the icon and leaving the accessible name announcing the opposite action.
     *
     * @param attributeValue the {@code theme} attribute value; must stay in step with the stylesheet
     * @param toggleGlyphSvg inline SVG for the toggle, drawn from {@link Glyphs}
     * @param toggleActionLabel the toggle's accessible name and tooltip while this mode applies
     */
    ThemeMode(String attributeValue, String toggleGlyphSvg, String toggleActionLabel) {
        this.attributeValue = attributeValue;
        this.toggleGlyphSvg = toggleGlyphSvg;
        this.toggleActionLabel = toggleActionLabel;
    }

    /**
     * Value written to {@code <html theme="...">}. Also the value Lumo uses for its dark variant.
     *
     * @return the attribute token, never null and never blank
     */
    public String attributeValue() {
        return attributeValue;
    }

    /**
     * Markup for the glyph the toggle shows. The toggle advertises the mode it switches <em>to</em>,
     * not the one currently applied, so a moon appears while the UI is light.
     *
     * @return a complete inline {@code <svg>} element, never null; written straight into the DOM,
     *     so it is a source constant and never anything derived from input
     */
    public String toggleGlyphSvg() {
        return toggleGlyphSvg;
    }

    /**
     * Accessible name for the toggle. Icon-only controls have no text to fall back on, so this is
     * the only thing a screen reader announces.
     *
     * @return the action the toggle performs, phrased as an instruction, never null
     */
    public String toggleActionLabel() {
        return toggleActionLabel;
    }

    /**
     * The mode the toggle moves to from here.
     *
     * <p>A method rather than a field because the two constants would each have to name the other,
     * which an enum cannot do at construction time. Written as a two-way test rather than an index
     * arithmetic trick so that adding a third scheme fails to compile instead of silently wrapping.
     *
     * @return the other mode, never null and never {@code this}
     */
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
