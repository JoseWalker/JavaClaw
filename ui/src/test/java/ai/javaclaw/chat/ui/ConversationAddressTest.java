package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a conversation has an address, and that the address is one the router answers.
 *
 * <p>The defect this covers is a reader's place vanishing on reload: clicking a row never moved the
 * URL, so a second page load had nothing to go on. Writing an address is only half a fix, and the
 * half that is easy to get wrong is the other one - an address the route table does not recognise is
 * worse than no address at all, because it turns a reload into a 404 instead of into a lost place.
 *
 * <p>So this registers the view's real annotations into a real route registry and asks the registry
 * the question a reload asks: given this URL, what do you show? Nothing here is a copy of the
 * template. If the annotation and {@link ConversationAddress} ever disagree, this is what says so.
 */
class ConversationAddressTest {

    @Test
    @DisplayName("the address of a conversation is a route the application answers")
    void theAddressResolvesToTheChatView() {
        RouteConfiguration routes = RouteConfiguration.forRegistry(
                ApplicationRouteRegistry.getInstance(new MapContext()));
        routes.update(() -> routes.setAnnotatedRoute(ChatView.class));

        Optional<Class<? extends Component>> target =
                routes.getHandledRegistry().getNavigationTarget(ConversationAddress.of("web-abc"));

        assertThat(target).contains(ChatView.class);
    }

    @Test
    @DisplayName("the conversation the address names is the one the router reports")
    void theRouterReportsTheConversationTheAddressNames() {
        RouteConfiguration routes = RouteConfiguration.forRegistry(
                ApplicationRouteRegistry.getInstance(new MapContext()));
        routes.update(() -> routes.setAnnotatedRoute(ChatView.class));

        RouteParameters parameters = routes.getHandledRegistry()
                .getNavigationRouteTarget(ConversationAddress.of("web-abc"))
                .getRouteParameters();

        assertThat(ConversationAddress.from(parameters)).contains("web-abc");
    }

    @Test
    @DisplayName("the plain route is still the way in for a reader with no conversation in mind")
    void theRootRouteSurvives() {
        RouteConfiguration routes = RouteConfiguration.forRegistry(
                ApplicationRouteRegistry.getInstance(new MapContext()));
        routes.update(() -> routes.setAnnotatedRoute(ChatView.class));

        assertThat(routes.getHandledRegistry().getNavigationTarget("")).contains(ChatView.class);
    }

    @Test
    @DisplayName("an id that could not be a conversation is not read out of an address")
    void refusesAnIdOfTheWrongShape() {
        // The allow-list the store is protected by, applied at the point the value enters the
        // application rather than at the point it would have done damage. Repairing is deliberately
        // not offered: a repaired id addresses a different conversation.
        assertThat(ConversationAddress.from(
                new RouteParameters(ConversationAddress.PARAMETER, "../../etc/passwd"))).isEmpty();
        assertThat(ConversationAddress.from(
                new RouteParameters(ConversationAddress.PARAMETER, "web/../secret"))).isEmpty();
        assertThat(ConversationAddress.from(RouteParameters.empty())).isEmpty();
        assertThat(ConversationAddress.from(null)).isEmpty();
    }

    /**
     * The least context a route registry needs: somewhere to keep its attributes.
     *
     * <p>A real {@code VaadinContext} is a servlet context, and a test about a URL has no business
     * standing one up. Nothing here is asked for a context parameter, so those answer empty rather
     * than pretending.
     */
    private static final class MapContext implements VaadinContext {

        private final Map<Class<?>, Object> attributes = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(Class<T> type, Supplier<T> defaultValueSupplier) {
            Object existing = attributes.get(type);
            if (existing == null && defaultValueSupplier != null) {
                existing = defaultValueSupplier.get();
                attributes.put(type, existing);
            }
            return (T) existing;
        }

        @Override
        public <T> void setAttribute(Class<T> clazz, T value) {
            attributes.put(clazz, value);
        }

        @Override
        public void removeAttribute(Class<?> clazz) {
            attributes.remove(clazz);
        }

        @Override
        public Enumeration<String> getContextParameterNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public String getContextParameter(String name) {
            return null;
        }
    }
}
