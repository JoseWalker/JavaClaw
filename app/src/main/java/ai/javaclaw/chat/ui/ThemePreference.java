package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads, stores and applies the visitor's colour scheme.
 *
 * <p>The choice lives in the {@link VaadinSession} rather than in a cookie or in local storage: the
 * brief is to remember it for the session, and a session attribute is the only store that needs no
 * consent banner, no serialisation format and no client round trip to read.
 *
 * <p>Nothing is written to the document until a choice actually exists. Before then the stylesheet's
 * {@code prefers-color-scheme} rule already renders the right theme, so stamping an attribute would
 * only risk a flash of the wrong one.
 */
final class ThemePreference {

    private ThemePreference() {
    }

    /**
     * The mode explicitly chosen in this session, if any.
     *
     * @param session the session to read from; {@code null} is tolerated so callers running outside
     *                a request (or during a teardown) do not have to guard every call site
     */
    static Optional<ThemeMode> stored(VaadinSession session) {
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.getAttribute(ThemeMode.class));
    }

    /**
     * Remembers an explicit choice for the rest of the session.
     */
    static void store(VaadinSession session, ThemeMode mode) {
        if (session == null || mode == null) {
            return;
        }
        session.setAttribute(ThemeMode.class, mode);
    }

    /**
     * Writes the mode onto the document element, where both this stylesheet and Lumo look for it.
     *
     * <p>The value is passed as a parameter rather than concatenated into the script, so a mode can
     * never be interpolated into executable JavaScript.
     */
    static void apply(UI ui, ThemeMode mode) {
        if (ui == null || mode == null) {
            return;
        }
        ui.getPage().executeJs("document.documentElement.setAttribute('theme', $0)",
                mode.attributeValue());
    }

    /**
     * Determines which mode is actually on screen and hands it to {@code onResolved}.
     *
     * <p>An explicit choice is authoritative and is re-applied, because a full page load may have
     * dropped the attribute. Otherwise the browser is asked what the operating system prefers; the
     * answer is only used to label the toggle, since the stylesheet has already applied it.
     */
    static void resolveEffective(UI ui, Consumer<ThemeMode> onResolved) {
        if (ui == null || onResolved == null) {
            return;
        }
        Optional<ThemeMode> chosen = stored(ui.getSession());
        if (chosen.isPresent()) {
            apply(ui, chosen.get());
            onResolved.accept(chosen.get());
            return;
        }
        ui.getPage()
                .executeJs("return window.matchMedia('(prefers-color-scheme: dark)').matches")
                .then(Boolean.class, prefersDark ->
                        onResolved.accept(Boolean.TRUE.equals(prefersDark)
                                ? ThemeMode.DARK
                                : ThemeMode.LIGHT));
    }
}
