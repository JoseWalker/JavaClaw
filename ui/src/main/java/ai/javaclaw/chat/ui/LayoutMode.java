package ai.javaclaw.chat.ui;

/**
 * The three shapes the application shell takes, keyed to viewport width.
 *
 * <p>The breakpoints are declared here <em>and</em> in the stylesheet, which is a duplication worth
 * being deliberate about. The stylesheet owns what the shell looks like at a given width, so the
 * first paint is correct before the server has heard anything from the browser; this enum owns what
 * the sidebar toggle <em>does</em>, which needs to know the current mode to flip the right way. The
 * two must be changed together, and the pixel values are stated in both places so that is obvious.
 */
enum LayoutMode {

    /** Below 768px. The sidebar is a drawer over the content, with a scrim. */
    MOBILE(0),

    /** 768px to 1023px. The sidebar is in flow but starts collapsed; the content is narrow. */
    TABLET(768),

    /** 1024px and up. The sidebar is persistent and starts open. */
    DESKTOP(1024);

    /**
     * Inclusive lower bound of the width band this mode covers, in CSS pixels. It has to match the
     * matching media query in {@code javaclaw.css}; that pairing is the duplication the class note
     * above argues for, and this field is one half of it.
     */
    private final int minimumWidth;

    /**
     * Binds a mode to the bottom of its width band. Called only by the constants above.
     *
     * @param minimumWidth the first width, in CSS pixels, that renders in this mode; {@link #MOBILE}
     *     takes zero because it is the band everything narrower falls into
     */
    LayoutMode(int minimumWidth) {
        this.minimumWidth = minimumWidth;
    }

    /**
     * The mode a viewport of the given width renders in.
     *
     * @param width viewport width in CSS pixels; a nonsensical value falls back to {@link #DESKTOP},
     *              which is what the stylesheet also assumes when it cannot tell
     * @return the matching mode, never null
     */
    static LayoutMode forWidth(int width) {
        if (width <= 0) {
            return DESKTOP;
        }
        LayoutMode match = MOBILE;
        for (LayoutMode candidate : values()) {
            if (width >= candidate.minimumWidth) {
                match = candidate;
            }
        }
        return match;
    }

    /**
     * Whether the sidebar is open before the reader has said otherwise.
     *
     * <p>Only the desktop has room to give a permanent column to navigation. Anywhere narrower the
     * reader came to read a conversation, not to browse a list.
     *
     * @return true only for {@link #DESKTOP}; this is the default a stored preference overrides,
     *     not a statement about what is on screen now
     */
    boolean sidebarOpenByDefault() {
        return this == DESKTOP;
    }

    /**
     * Whether the sidebar floats over the content instead of pushing it aside.
     *
     * @return true only for {@link #MOBILE}, which is what decides whether Escape is an exit and
     *     whether the content goes inert behind the column
     */
    boolean overlaysContent() {
        return this == MOBILE;
    }
}
