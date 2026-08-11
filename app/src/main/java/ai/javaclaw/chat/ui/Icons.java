package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.html.Span;

/**
 * The icon set for the application shell, as inline SVG.
 *
 * <p>Inline SVG rather than an icon font or {@code @vaadin/icons}: it needs no bundler, no
 * {@code @JsModule} and no Node, it inherits {@code currentColor} so hover and disabled states come
 * for free, and it keeps the shell buildable with nothing but a JDK - the same constraint that keeps
 * the stylesheet in {@code META-INF/resources}.
 *
 * <p>Every glyph is drawn to the same 16-unit box at the same stroke width, so controls sitting next
 * to each other keep the same optical weight. Sizing lives in {@code .jc-glyph}, not here.
 */
final class Icons {

    private static final String OPEN = "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\""
            + " fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\""
            + " stroke-linecap=\"round\" stroke-linejoin=\"round\" focusable=\"false\">";

    /** Three bars: opens the drawer on a narrow window. */
    static final String MENU = OPEN + "<path d=\"M2.25 4h11.5M2.25 8h11.5M2.25 12h11.5\"/>" + "</svg>";

    /**
     * A panel with its left column filled: collapses the sidebar. Read as "the rail goes away",
     * which a bare arrow does not say.
     */
    static final String PANEL = OPEN
            + "<rect x=\"2\" y=\"2.75\" width=\"12\" height=\"10.5\" rx=\"2\"/>"
            + "<path d=\"M6.25 2.75v10.5\"/>"
            + "</svg>";

    /** Plus in a rounded square: start a new chat. */
    static final String NEW_CHAT = OPEN
            + "<path d=\"M8 3.75v8.5M3.75 8h8.5\"/>"
            + "</svg>";

    /** Magnifier for the search field's prefix. */
    static final String SEARCH = OPEN
            + "<circle cx=\"7.2\" cy=\"7.2\" r=\"4.2\"/>"
            + "<path d=\"M10.4 10.4 13.4 13.4\"/>"
            + "</svg>";

    /** Chevron pointing down; rotated by CSS when its group is collapsed. */
    static final String CHEVRON_DOWN = OPEN + "<path d=\"m4.5 6.25 3.5 3.5 3.5-3.5\"/>" + "</svg>";

    /** Chevron pointing up: the user menu's disclosure. */
    static final String CHEVRON_UP = OPEN + "<path d=\"m4.5 9.75 3.5-3.5 3.5 3.5\"/>" + "</svg>";

    /** Head and shoulders: the avatar in the user menu, where there is no picture to show. */
    static final String PERSON = OPEN
            + "<circle cx=\"8\" cy=\"5.75\" r=\"2.75\"/>"
            + "<path d=\"M2.9 13.5a5.1 5.1 0 0 1 10.2 0\"/>"
            + "</svg>";

    /** Speech bubble: the mark on a conversation row. */
    static final String BUBBLE = OPEN
            + "<path d=\"M13.25 8.75a3.5 3.5 0 0 1-3.5 3.5H6.4L3.25 14.1V5.25a3.5 3.5 0 0 1 3.5-3.5h3a3.5 3.5"
            + " 0 0 1 3.5 3.5Z\"/>"
            + "</svg>";

    /**
     * Wraps a glyph in the span the stylesheet sizes.
     *
     * <p>The span is decorative in every place it is used - each of them is either an icon-only
     * control with its own accessible name, or sits beside a visible label - so it is hidden from
     * assistive technology rather than announced as an unnamed image.
     *
     * @param svg one of the constants above
     * @return a detached span carrying the glyph
     */
    static Span glyph(String svg) {
        Span span = new Span();
        span.addClassName("jc-glyph");
        span.getElement().setAttribute("aria-hidden", "true");
        span.getElement().setProperty("innerHTML", svg);
        return span;
    }

    private Icons() {
    }
}
