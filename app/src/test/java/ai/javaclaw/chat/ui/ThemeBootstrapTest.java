package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the served document has to say about the theme before a single stylesheet byte has arrived.
 *
 * <h2>Why this is not the stylesheet's job</h2>
 *
 * <p>Everything else about the theme is decided in CSS, and deliberately so. These two declarations
 * cannot be: they are read by the browser itself, before and outside the cascade. {@code
 * color-scheme} tells the user agent what to paint its own canvas, its scrollbars and its form
 * controls as, during the window in which the stylesheet is still in flight and there is no cascade
 * to consult. {@code theme-color} is not read by the page at all - it colours the browser's own
 * chrome around it, which on a phone is the address bar directly above our header.
 *
 * <p>The second one matters more here than it would elsewhere, because the shell asks for
 * {@code viewport-fit=cover}: the page is explicitly drawn into the area the browser chrome shares,
 * so leaving that chrome at its default paints a light band above a dark product.
 */
class ThemeBootstrapTest {

    private static final String BARE = "<html><head></head><body></body></html>";

    @Test
    @DisplayName("the document tells the browser both schemes exist before any CSS has loaded")
    void theDocumentDeclaresBothSchemes() {
        Document document = Jsoup.parse(BARE);

        ThemeBootstrapListener.stamp(document, null);

        // Without this the user agent assumes light and paints a white canvas and light scrollbars
        // until the stylesheet parses, however dark the visitor's system is set.
        assertThat(document.selectFirst("meta[name=color-scheme]").attr("content"))
                .isEqualTo("light dark");
    }

    @Test
    @DisplayName("with no choice stored, nothing pins the document to one scheme")
    void noChoiceLeavesTheMediaQueryInCharge() {
        Document document = Jsoup.parse(BARE);

        ThemeBootstrapListener.stamp(document, null);

        // The stylesheet's prefers-color-scheme rule is authoritative on a first visit. Stamping
        // anything here would override the visitor's own system setting with a guess.
        assertThat(document.selectFirst("html").hasAttr("theme")).isFalse();
    }

    @Test
    @DisplayName("an explicit choice is on the document element before the first paint")
    void anExplicitChoiceIsStamped() {
        Document document = Jsoup.parse(BARE);

        ThemeBootstrapListener.stamp(document, ThemeMode.DARK);

        assertThat(document.selectFirst("html").attr("theme")).isEqualTo("dark");
        assertThat(document.selectFirst("meta[name=color-scheme]").attr("content"))
                .isEqualTo("light dark");
    }

    @Test
    @DisplayName("stamping the same document twice leaves one declaration, not two")
    void stampingIsIdempotent() {
        Document document = Jsoup.parse(BARE);

        ThemeBootstrapListener.stamp(document, ThemeMode.LIGHT);
        ThemeBootstrapListener.stamp(document, ThemeMode.DARK);

        assertThat(document.select("meta[name=color-scheme]")).hasSize(1);
        assertThat(document.selectFirst("html").attr("theme")).isEqualTo("dark");
    }

    @Test
    @DisplayName("a document with no head is left alone rather than half-stamped")
    void aDocumentWithoutAHeadIsNotGuessedAt() {
        // Jsoup always synthesises a head for parsed markup, so the guard is reached by removing it.
        // The listener runs on whatever Vaadin hands it, and a partial stamp - a theme attribute
        // with no scheme declaration - is worse than none, because it looks correct.
        Document document = Jsoup.parse(BARE);
        document.head().remove();

        ThemeBootstrapListener.stamp(document, ThemeMode.DARK);

        assertThat(document.select("meta[name=color-scheme]")).isEmpty();
    }
}
