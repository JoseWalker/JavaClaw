package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.dom.DebouncePhase;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.JsonConstants;
import org.mockito.Mockito;
import tools.jackson.databind.node.ObjectNode;

import java.util.Comparator;
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
 *
 * <p>The class and {@link #ui()} are public rather than package-private so that {@code
 * ai.javaclaw.chat.ui.settings}'s tests can reuse this harness too, for the same reason any of this
 * package's own tests do: a component under test that calls {@code Notification.show} or {@code
 * Element.executeJs} needs a session capable of draining deferred work, and duplicating that stub
 * session in a second package would be two copies free to drift apart. The remaining methods stay
 * package-private because nothing outside this package currently needs them.
 */
public final class UiHarness {

    private UiHarness() {
    }

    /**
     * A UI that can drain its deferred work.
     *
     * @return a fresh UI with a stub session already set
     */
    public static UI ui() {
        return ui(session());
    }

    /**
     * A UI over a session the caller already holds.
     *
     * <p>Exists so a test can stage a reload, which is the one browser event that destroys the whole
     * component tree and keeps the session. Anything a view is expected to carry across a refresh has
     * to live outside the tree by definition, so a test that reused one UI could not tell a value
     * that survived from a field that was never cleared.
     *
     * @param session the session both UIs are to share; never null
     * @return a fresh UI bound to it
     */
    static UI ui(VaadinSession session) {
        UI ui = new UI();
        ui.getInternals().setSession(session);
        // Vaadin's overlays add themselves to whichever UI is current when they open, and outside a
        // request there is no such thing unless a test says so. Without it a dialog opened by a
        // shortcut throws instead of appearing, which is a failure about the harness rather than
        // about the shortcut.
        UI.setCurrent(ui);
        return ui;
    }

    /**
     * A session with no browser and no lock behind it.
     *
     * @return a fresh stub session, holding no attributes
     */
    static VaadinSession session() {
        return new UnlockedSession();
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
                // Shortest first, and that is not a tie-break: a registration that also asks the
                // browser to swallow the key publishes a second expression alongside its filter -
                // the filter wrapped in "(...) && event.preventDefault()" - and the set they arrive
                // in is unordered. Only the filter itself is what the server matches a delivered
                // event against, so answering the wrapper means the event arrives, nothing runs, and
                // the test reports an absence rather than a failure. The wrapper is the filter plus
                // something, so the filter is always the shorter of the two.
                .sorted(Comparator.comparingInt(String::length))
                .toList();
        if (filters.isEmpty()) {
            throw new AssertionError("Nothing listens for " + code + " on <" + element.getTag() + ">");
        }
        ObjectNode event = JacksonUtils.createObjectNode();
        event.put(filters.getFirst(), true);
        listeners.fireEvent(new DomEvent(element, "keydown", event));
    }

    /**
     * Tells the shell how wide the window is, the way the browser tells it.
     *
     * <p>The shell learns its arrangement from the viewport, and everything that follows from the
     * arrangement - whether the column is a drawer, whether it covers the content, whether the page
     * behind it is taken out of play - is unreachable from a test until this has been said. It is
     * delivered as the DOM event Vaadin's own resize plumbing listens for, on the element it listens
     * on, carrying the two values it reads: driving the listener list directly would be a test of a
     * path the browser does not take.
     *
     * @param ui     the UI whose shell is to hear about it
     * @param width  viewport width in CSS pixels
     * @param height viewport height in CSS pixels
     * @throws AssertionError when nothing is listening, which means the shell never asked to be told
     */
    static void resizeTo(UI ui, int width, int height) {
        Element element = ui.getElement();
        ((StateTree) element.getNode().getOwner()).runExecutionsBeforeClientResponse();
        ElementListenerMap listeners = element.getNode().getFeature(ElementListenerMap.class);
        if (listeners.getExpressions("window-resize").isEmpty()) {
            throw new AssertionError("Nothing is listening for the window being resized");
        }
        ObjectNode event = JacksonUtils.createObjectNode();
        event.put("event.w", width);
        event.put("event.h", height);
        // Vaadin debounces its resize listener, and a debounced registration answers only the phase
        // it asked for - so an event with no phase on it is dropped before any handler sees it,
        // silently, which is a test that passes by never running the code it is about. The browser
        // stamps this field; so does this.
        event.put(JsonConstants.EVENT_DATA_PHASE, DebouncePhase.TRAILING.getIdentifier());
        listeners.fireEvent(new DomEvent(element, "window-resize", event));
    }

    /**
     * The JavaScript invocations a UI has queued to send to its browser, when there is no browser for
     * them to reach.
     *
     * <p>{@code RestartNotice.showAndScheduleReload} is the motivating case: it schedules a reload
     * through {@code Element.executeJs}, and a test asserting that a save actually triggered it has no
     * other way to observe that without a real client connection. Vaadin queues an {@code executeJs}
     * call on the owning {@link UI}'s session-side state rather than sending it immediately, so
     * draining that queue - the same operation the server uses to decide what is "sent" on a real
     * response - is a faithful way to prove the call happened, not a simulation of one.
     *
     * <p>{@code Element.executeJs} does not queue its invocation directly - it registers a callback
     * for the state tree's "before client response" phase, the same deferred-work queue {@link
     * #press} and {@link #resizeTo} already have to drain before their own assertions hold, and for
     * the same reason: nothing outside a real request drains it on its own here.
     *
     * @param ui the UI to drain; must be backed by a session that can answer {@code checkHasLock()},
     *     like the one {@link #ui()} returns
     * @return every JavaScript invocation queued on {@code ui} so far, in queue order; empty when
     *     nothing has called {@code executeJs} on a component attached to it
     */
    public static List<PendingJavaScriptInvocation> drainPendingJavaScript(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations();
    }

    /** A session that exists and holds no lock, which is all the UI is asked for here. */
    private static final class UnlockedSession extends VaadinSession {

        /**
         * Production mode, so attaching a component does not go looking for the development-time
         * class-name metadata that needs a real deployment behind it.
         */
        private final DeploymentConfiguration configuration = Mockito.mock(DeploymentConfiguration.class);

        /**
         * A service that exists only to answer for the deployment configuration.
         *
         * <p>{@code Page.getHistory()} asks the session for its service and the service for its
         * configuration before it can decide which of two scripts to send the browser, so a session
         * with no service turns every address change into a null dereference. Nothing else is asked
         * of it: a real service needs a servlet, a registry and a deployment behind it, none of which
         * a test about a URL has any business standing up.
         */
        private final VaadinService service = Mockito.mock(VaadinService.class);

        private UnlockedSession() {
            super(null);
            Mockito.when(configuration.isProductionMode()).thenReturn(true);
            Mockito.when(service.getDeploymentConfiguration()).thenReturn(configuration);
        }

        @Override
        public VaadinService getService() {
            return service;
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
