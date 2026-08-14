package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * States, on the served document, what language this interface is written in.
 *
 * <h2>Why it cannot be left to Vaadin</h2>
 *
 * <p>Vaadin fills {@code <html lang>} from the first of these it can find: the current UI's locale,
 * an {@code I18NProvider}'s default, and failing both, {@link java.util.Locale#getDefault()}. This
 * application has no {@code I18NProvider} and the attribute is written before the UI exists on a
 * cold request, so what shipped was the JVM default locale of whoever started the process - the
 * page declared {@code lang="es"} on a machine in Colombia, and declared it identically under three
 * different {@code Accept-Language} headers, so it was not negotiation, it was an accident.
 *
 * <p>That is a Level A failure with a real cost, not a lint: every string in this interface is
 * English, and a screen reader told the document is Spanish reads English prose with Spanish
 * pronunciation rules and switches voice to do it. Assistive technology has no way to notice it is
 * wrong; the markup is the only claim it has.
 *
 * <h2>Why "en" is hardcoded</h2>
 *
 * <p>Because it is true, and because the alternative is worse. Nothing in this UI is translated:
 * the labels, the announcements and the shortcut help are all written in English in the source. A
 * declaration derived from the request would tell a Spanish-speaking reader's screen reader to read
 * English text as Spanish, which is the exact defect being fixed with a more sophisticated cause.
 * The day this interface is translated, this class is where the language comes from, and it should
 * be given the same locale the strings are chosen with rather than a second, independent guess.
 *
 * <p>Registered as an index-html listener rather than through {@link
 * com.vaadin.flow.component.page.AppShellConfigurator}, which has no accessor for the attribute,
 * and it runs after the framework has written its own value, which is what lets it correct one.
 */
class DocumentLanguage implements VaadinServiceInitListener {

    /**
     * Built once by {@link ChatUiConfiguration} and by nothing else. Written out rather than left
     * implicit so it can record that this listener is stateless: it is invoked concurrently for
     * every bootstrap request, and holding per-request state here would be a data race.
     */
    DocumentLanguage() {
    }

    /**
     * The language every string in this interface is written in.
     *
     * <p>A bare language subtag rather than a region: this is English, and it is not any country's
     * English in particular.
     */
    private static final String LANGUAGE = "en";

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addIndexHtmlRequestListener(this::declareLanguage);
    }

    /**
     * Adapts Vaadin's response callback to {@link #pin}, which is the part worth testing.
     *
     * <p>It exists only to unwrap the document: keeping the response type out of {@code pin} is what
     * lets the assertion about the served markup run against a plain parsed document rather than a
     * mocked servlet stack.
     *
     * <p>Runs on the request thread that is building the bootstrap page, not on a UI thread.
     *
     * @param response the index page Vaadin is about to send
     */
    private void declareLanguage(IndexHtmlResponse response) {
        pin(response.getDocument());
    }

    /**
     * Writes the language onto the document's root element, replacing whatever was there.
     *
     * <p>Split out from the listener so the claim can be tested against a plain document: what is
     * being asserted is about the markup that leaves the server, and nothing about it needs a
     * session, a request or a running Vaadin service to be true.
     *
     * <p>Replaces rather than fills a gap. The value this exists to correct is one the framework
     * has already written by the time this runs, so a version that only wrote into an empty
     * attribute would be a no-op in production and green in a test.
     *
     * @param document the bootstrap document to write into; a null document, or one with no root
     *                 element, is left alone rather than synthesised - a document this class does
     *                 not recognise is not one it should be inventing structure for
     */
    static void pin(Document document) {
        if (document == null) {
            return;
        }
        Element html = document.selectFirst("html");
        if (html == null) {
            return;
        }
        html.attr("lang", LANGUAGE);
    }
}
