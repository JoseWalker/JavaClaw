package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.server.VaadinSession;
import org.mockito.Mockito;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The little bit of Vaadin a keyboard test needs that a plain component tree does not give it.
 *
 * <h2>Why a keystroke is fired rather than a handler called</h2>
 *
 * <p>Every defect these tests exist for was a defect of <em>where the listener lives</em>, not of
 * what it does. Escape stopped a generation perfectly well and was registered with
 * {@code listenOn(this)}, so it never heard a keystroke from the skip link's landing point. A test
 * that called the handler would have passed on that code, which makes calling the handler the one
 * thing such a test must not do. So the event goes through {@link ElementListenerMap} exactly as
 * the client's would: on the element the browser would deliver it to, carrying the key filter's
 * result the way a real event carries it.
 *
 * <h2>Why the UI needs a session</h2>
 *
 * <p>Vaadin attaches a shortcut's DOM listener during the work it defers to the client response,
 * and that queue also holds every {@code executeJs} the shell installs on attach. Draining it goes
 * through the session lock, so a sessionless UI throws before a single listener is attached. The
 * session here is a stub with the lock check answered rather than satisfied: these tests run the
 * whole exchange on the calling thread, so there is nothing to lock and nobody to lock against,
 * and a real session needs a servlet and a deployment configuration to exist at all.
 */
final class UiHarness {

    private UiHarness() {
    }

    /**
     * A UI that can drain its deferred work.
     *
     * @return a fresh UI with a stub session already set
     */
    static UI ui() {
        UI ui = new UI();
        ui.getInternals().setSession(new UnlockedSession());
        // Vaadin's overlays add themselves to whichever UI is current when they open, and outside a
        // request there is no such thing unless a test says so. Without it a dialog opened by a
        // shortcut throws instead of appearing, which is a failure about the harness rather than
        // about the shortcut.
        UI.setCurrent(ui);
        return ui;
    }

    /**
     * Delivers a keystroke to an element, the way the browser would.
     *
     * <p>The chord is identified by the {@code KeyboardEvent.code} token Vaadin writes into the key
     * filter - "Escape", "KeyK", "KeyB" - because that is what the filter is written in. A primary
     * chord is registered twice, once for Control and once for Meta, so exactly one of the two
     * matching filters is answered: firing both would run the action twice, and a toggle run twice
     * is a toggle that did nothing.
     *
     * @param element the element the keystroke is delivered to
     * @param code    the {@code KeyboardEvent.code} token of the chord's primary key
     * @throws AssertionError when nothing on that element is listening for the chord
     */
    static void press(Element element, String code) {
        ((StateTree) element.getNode().getOwner()).runExecutionsBeforeClientResponse();
        ElementListenerMap listeners = element.getNode().getFeature(ElementListenerMap.class);
        List<String> filters = listeners.getExpressions("keydown").stream()
                .filter(expression -> expression.contains("'" + code + "'"))
                .toList();
        if (filters.isEmpty()) {
            throw new AssertionError("Nothing listens for " + code + " on <" + element.getTag() + ">");
        }
        ObjectNode event = JacksonUtils.createObjectNode();
        event.put(filters.getFirst(), true);
        listeners.fireEvent(new DomEvent(element, "keydown", event));
    }

    /** A session that exists and holds no lock, which is all the UI is asked for here. */
    private static final class UnlockedSession extends VaadinSession {

        /**
         * Production mode, so attaching a component does not go looking for the development-time
         * class-name metadata that needs a real deployment behind it.
         */
        private final DeploymentConfiguration configuration = Mockito.mock(DeploymentConfiguration.class);

        private UnlockedSession() {
            super(null);
            Mockito.when(configuration.isProductionMode()).thenReturn(true);
        }

        @Override
        public DeploymentConfiguration getConfiguration() {
            return configuration;
        }

        @Override
        public void checkHasLock() {
            // Nothing to check: no service, no other thread.
        }

        @Override
        public void checkHasLock(String message) {
            // Nothing to check: no service, no other thread.
        }
    }
}
