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

    /**
     * An arrow down to a line: return to the newest message.
     *
     * <p>The line under the arrowhead is what makes it "to the end" rather than "one page down",
     * which is the difference between this control and a scroll.
     */
    static final String ARROW_DOWN = OPEN
            + "<path d=\"M8 2.75v7.5M4.75 7l3.25 3.25L11.25 7\"/>"
            + "<path d=\"M3.5 13.25h9\"/>"
            + "</svg>";

    /** Chevron pointing up: the user menu's disclosure. */
    static final String CHEVRON_UP = OPEN + "<path d=\"m4.5 9.75 3.5-3.5 3.5 3.5\"/>" + "</svg>";

    /** Head and shoulders: the avatar in the user menu, where there is no picture to show. */
    static final String PERSON = OPEN
            + "<circle cx=\"8\" cy=\"5.75\" r=\"2.75\"/>"
            + "<path d=\"M2.9 13.5a5.1 5.1 0 0 1 10.2 0\"/>"
            + "</svg>";

    /**
     * Two offset sheets: copy this message.
     *
     * <p>Drawn as one full rectangle behind a second one rather than as a clipboard, because a
     * clipboard at 16 units is a rounded rectangle with a nub on it that reads as a document.
     */
    static final String COPY = OPEN
            + "<rect x=\"5.75\" y=\"5.75\" width=\"7.5\" height=\"7.5\" rx=\"1.75\"/>"
            + "<path d=\"M10.25 5.75V4.5a1.75 1.75 0 0 0-1.75-1.75h-4A1.75 1.75 0 0 0 2.75 4.5v4"
            + " A1.75 1.75 0 0 0 4.5 10.25h1.25\"/>"
            + "</svg>";

    /** A tick: the copy landed. Only ever shown in place of {@link #COPY}, never beside it. */
    static final String CHECK = OPEN + "<path d=\"m3.25 8.5 3 3 6.5-7\"/>" + "</svg>";

    /**
     * A pencil over a line: edit this message and ask again from there.
     *
     * <p>The line under the nib is what separates it from a generic diagonal stroke, and it is the
     * same affordance every text field on the web has taught readers to expect.
     */
    static final String PENCIL = OPEN
            + "<path d=\"M11.1 2.65a1.55 1.55 0 0 1 2.2 2.2L6.4 11.75l-2.9.75.75-2.9Z\"/>"
            + "<path d=\"M2.75 14.25h10.5\"/>"
            + "</svg>";

    /**
     * An arrow closing a circle: answer this again.
     *
     * <p>A full loop rather than the two-arrow refresh glyph: two arrows say "synchronise", which
     * is what a reader would expect to affect the whole conversation rather than one answer.
     */
    static final String REGENERATE = OPEN
            + "<path d=\"M13.1 8a5.1 5.1 0 1 1-1.5-3.6\"/>"
            + "<path d=\"M13.25 2.4v2.9h-2.9\"/>"
            + "</svg>";

    /**
     * Speech bubble, formerly the mark on a conversation row.
     *
     * <p>Dropped from the row because it was the same glyph on all of them: it distinguished nothing
     * and spent title width in a column already truncating titles mid-word.
     *
     * @deprecated nothing draws this; remove it once no branch still expects a row icon
     */
    @Deprecated(forRemoval = true)
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
