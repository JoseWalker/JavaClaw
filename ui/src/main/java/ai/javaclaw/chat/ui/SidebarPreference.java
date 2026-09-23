package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.VaadinSession;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers whether the reader wants the navigation column showing.
 *
 * <p>Kept in the {@link VaadinSession}, for the same reasons the theme is: it survives a reload and
 * a navigation, it needs no consent banner, and it is readable on the server before the first paint
 * rather than one client round trip after it. What it deliberately does not survive is closing the
 * browser - that would need local storage, and a persisted layout is not worth a client round trip
 * on every visit to something whose default is already correct.
 *
 * <h2>Why the choice is remembered per width, not once</h2>
 *
 * <p>Collapsing the column on a phone says "get this drawer off my conversation". Collapsing it on a
 * desktop says "I want the wider measure". They are different requests that happen to move the same
 * element, and applying one to the other is how a reader ends up with no navigation on a screen with
 * room for it. So each arrangement remembers its own answer, and a window that has changed shape
 * gets that shape's answer - or, when there is none yet, that shape's default.
 */
final class SidebarPreference {

    /** Key for the session attribute. Its own type would be a map, which is not a useful key. */
    private static final String ATTRIBUTE = SidebarPreference.class.getName();

    /**
     * The reader's answer for one arrangement, if they have given one.
     *
     * @param session the session to read from; null is tolerated so callers running outside a
     *                request do not have to guard every call site
     * @param mode    the arrangement currently on screen
     * @return true or false as chosen, or empty while the per-breakpoint default still applies
     */
    static Optional<Boolean> stored(VaadinSession session, LayoutMode mode) {
        if (session == null || mode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(choices(session).get(mode));
    }

    /**
     * Remembers an explicit choice for the rest of the session.
     *
     * @param session the session to write to; null is tolerated
     * @param mode    the arrangement the choice was made in
     * @param open    whether the column should be showing
     */
    static void store(VaadinSession session, LayoutMode mode, boolean open) {
        if (session == null || mode == null) {
            return;
        }
        Map<LayoutMode, Boolean> choices = choices(session);
        choices.put(mode, open);
        // Re-set rather than mutated in place: a session attribute may be held somewhere that only
        // notices a write, and a map that was never stored back is a preference silently lost.
        session.setAttribute(ATTRIBUTE, choices);
    }

    /**
     * The session's choice map, or a fresh empty one when there is nothing usable stored.
     *
     * <p>Never returns null and never throws, which is the point of routing both readers through it.
     * Session attributes are a namespace shared with the framework and with anything else in this
     * application, so a value of an unexpected type is treated as "no preference" rather than cast:
     * the alternative is a {@link ClassCastException} that takes down the render of a page whose
     * only fault was a stale attribute.
     *
     * <p>The empty map handed back on the miss path is deliberately <em>not</em> stored. Reading a
     * preference should not create one; {@link #store} is what puts a map into the session.
     *
     * @param session the session to read from, never null - callers guard first
     * @return a mutable map from arrangement to choice, empty when nothing has been chosen
     */
    @SuppressWarnings("unchecked")
    private static Map<LayoutMode, Boolean> choices(VaadinSession session) {
        Object stored = session.getAttribute(ATTRIBUTE);
        // Anything that is not the map this class writes is treated as no preference at all. The
        // attribute is shared namespace, and a cast that assumed otherwise would fail the render.
        if (stored instanceof EnumMap<?, ?> map) {
            return (Map<LayoutMode, Boolean>) map;
        }
        return new EnumMap<>(LayoutMode.class);
    }

    /**
     * Not instantiable. The preference lives in the session, so an instance would own nothing and
     * would invite a second, per-view copy of state that has to be single.
     */
    private SidebarPreference() {
    }
}
