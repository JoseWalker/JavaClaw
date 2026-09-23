package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three arrangements, at the three widths the layout is actually reviewed at.
 *
 * <p>These numbers are not illustrative: 390, 820 and 1440 are the viewports the screenshot harness
 * captures, so a change that moves a breakpoint past one of them changes what a reviewer sees. The
 * stylesheet holds the same three thresholds, and the pairing between the two is the reason a first
 * paint needs no round trip - so if this file and the media queries ever disagree, the shell and the
 * page it is drawn on are describing different layouts.
 */
class LayoutModeTest {

    @Test
    @DisplayName("a phone gets a drawer over the content, closed until it is asked for")
    void phone() {
        LayoutMode mode = LayoutMode.forWidth(390);

        assertThat(mode).isEqualTo(LayoutMode.MOBILE);
        assertThat(mode.overlaysContent()).isTrue();
        assertThat(mode.sidebarOpenByDefault()).isFalse();
    }

    @Test
    @DisplayName("a tablet pushes the content aside, but not before it is asked for")
    void tablet() {
        LayoutMode mode = LayoutMode.forWidth(820);

        assertThat(mode).isEqualTo(LayoutMode.TABLET);
        assertThat(mode.overlaysContent()).isFalse();
        assertThat(mode.sidebarOpenByDefault()).isFalse();
    }

    @Test
    @DisplayName("a desktop keeps the column open and pushes the content aside for it")
    void desktop() {
        LayoutMode mode = LayoutMode.forWidth(1440);

        assertThat(mode).isEqualTo(LayoutMode.DESKTOP);
        assertThat(mode.overlaysContent()).isFalse();
        assertThat(mode.sidebarOpenByDefault()).isTrue();
    }

    @Test
    @DisplayName("the edges land on the side the stylesheet puts them")
    void breakpointEdges() {
        assertThat(LayoutMode.forWidth(767)).isEqualTo(LayoutMode.MOBILE);
        assertThat(LayoutMode.forWidth(768)).isEqualTo(LayoutMode.TABLET);
        assertThat(LayoutMode.forWidth(1023)).isEqualTo(LayoutMode.TABLET);
        assertThat(LayoutMode.forWidth(1024)).isEqualTo(LayoutMode.DESKTOP);
    }
}
