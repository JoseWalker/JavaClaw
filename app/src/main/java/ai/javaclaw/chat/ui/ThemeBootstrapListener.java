package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Element;

/**
 * Stamps a previously chosen theme onto {@code <html>} while the bootstrap page is still being
 * written.
 *
 * <p>Without this, a visitor who chose dark on a light desktop would see the page painted light and
 * then flip once the view attached and the client round trip completed. Writing the attribute into
 * the served document removes the round trip, so the first paint is already correct.
 *
 * <p>Only an explicit choice is stamped. With no choice stored the stylesheet's
 * {@code prefers-color-scheme} rule is authoritative, and stamping anything here would override it.
 */
class ThemeBootstrapListener implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addIndexHtmlRequestListener(this::stampChosenTheme);
    }

    private void stampChosenTheme(IndexHtmlResponse response) {
        ThemePreference.stored(VaadinSession.getCurrent())
                .ifPresent(mode -> {
                    Element html = response.getDocument().selectFirst("html");
                    if (html != null) {
                        html.attr("theme", mode.attributeValue());
                    }
                });
    }
}
