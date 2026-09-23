package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Writes the two theme facts the browser needs before it has any CSS into the bootstrap page.
 *
 * <p>Without the first, a visitor who chose dark on a light desktop would see the page painted light
 * and then flip once the view attached and the client round trip completed. Writing the attribute
 * into the served document removes the round trip, so the first paint is already correct.
 *
 * <p>The second is for the window in which no cascade exists yet. The stylesheet is the only thing
 * that declares {@code color-scheme}, and it arrives as a separate request; until it parses, the
 * user agent has no reason not to paint its own canvas, its scrollbars and its form controls light.
 * {@code <meta name="color-scheme">} is read straight out of the markup, so it closes that window
 * without duplicating a single colour - it names the two schemes the page supports and leaves which
 * one to the visitor's system setting, exactly as the stylesheet's media query would.
 *
 * <p>Only an explicit choice is stamped as a theme. With no choice stored the stylesheet's
 * {@code prefers-color-scheme} rule is authoritative, and stamping anything here would override it.
 */
class ThemeBootstrapListener implements VaadinServiceInitListener {

    /**
     * Built once by {@link ChatUiConfiguration} and by nothing else. Written out rather than left
     * implicit so it can record that this listener holds no state: it serves every bootstrap request
     * concurrently, and the only per-request value it needs is read from the current session.
     */
    ThemeBootstrapListener() {
    }

    /**
     * Both schemes, in the order that makes light the default when a user agent honours only the
     * first. Never derived from a request, so there is nothing here to interpolate.
     */
    private static final String SUPPORTED_SCHEMES = "light dark";

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addIndexHtmlRequestListener(this::stampChosenTheme);
    }

    /**
     * Adapts Vaadin's response callback to {@link #stamp}, resolving the session on the way.
     *
     * <p>The session lookup happens here rather than inside {@code stamp} so that the assertion about
     * the served markup can be made against a plain document and an explicit choice, with no session
     * to set up. A missing choice is passed through as null, which {@code stamp} reads as "leave the
     * stylesheet's media query in charge" - the default has to stay unstamped or it would win.
     *
     * <p>Runs on the request thread building the bootstrap page, not on a UI thread.
     *
     * @param response the index page Vaadin is about to send
     */
    private void stampChosenTheme(IndexHtmlResponse response) {
        stamp(response.getDocument(),
                ThemePreference.stored(VaadinSession.getCurrent()).orElse(null));
    }

    /**
     * Declares the supported schemes on the document, and the chosen one if there is a choice.
     *
     * <p>Split out from the listener so it can be exercised against a plain document: the claim
     * being made is about the markup that leaves the server, and nothing about it needs a session,
     * a request or a running Vaadin service to be true.
     *
     * @param document the bootstrap document to write into
     * @param chosen   the mode explicitly chosen for this session, or {@code null} when the visitor
     *                 has made no choice and the stylesheet's media query should decide
     */
    static void stamp(Document document, ThemeMode chosen) {
        if (document == null) {
            return;
        }
        // selectFirst rather than Document.head(), which synthesises a head when the document has
        // none and would turn the guard below into dead code.
        Element head = document.selectFirst("head");
        if (head == null) {
            // A document with no head is not one we understand. A theme attribute with no scheme
            // declaration beside it is worse than neither, because it looks like it worked.
            return;
        }
        Element declaration = head.selectFirst("meta[name=color-scheme]");
        if (declaration == null) {
            declaration = head.appendElement("meta").attr("name", "color-scheme");
        }
        declaration.attr("content", SUPPORTED_SCHEMES);

        Element html = document.selectFirst("html");
        if (html != null && chosen != null) {
            html.attr("theme", chosen.attributeValue());
        }
    }
}
