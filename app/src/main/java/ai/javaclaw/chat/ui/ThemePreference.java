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

    /**
     * Not instantiable. The preference itself lives in the session, so an instance of this class
     * would own nothing; the private constructor keeps that from being discovered by experiment.
     */
    private ThemePreference() {
    }

    /**
     * The mode explicitly chosen in this session, if any.
     *
     * @param session the session to read from; {@code null} is tolerated so callers running outside
     *                a request (or during a teardown) do not have to guard every call site
     *
     * @return the chosen mode, or empty while no explicit choice has been made - which is not the
     *     same as light: with no choice stored the stylesheet's media query is authoritative
     */
    static Optional<ThemeMode> stored(VaadinSession session) {
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.getAttribute(ThemeMode.class));
    }

    /**
     * Remembers an explicit choice for the rest of the session.
     *
     * @param session the session to write to; null is tolerated so callers running outside a
     *     request do not have to guard every call site
     * @param mode the chosen scheme; null is tolerated and stores nothing
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
     *
     * @param ui the UI to write the attribute on; null is tolerated and does nothing
     * @param mode the scheme to apply; null is tolerated and does nothing
     */
    static void apply(UI ui, ThemeMode mode) {
        if (ui == null || mode == null) {
            return;
        }
        ui.getPage().executeJs("document.documentElement.setAttribute('theme', $0)",
                mode.attributeValue());
        syncBrowserChrome(ui);
    }

    /**
     * Colours the browser's own chrome to match the page under it.
     *
     * <p>The shell asks for {@code viewport-fit=cover}, so on a phone the page is drawn right up
     * into the band the address bar occupies. With no {@code theme-color} the browser leaves that
     * band at its default, which puts a light strip directly above a dark header - the one seam in
     * the product where the theme visibly stops.
     *
     * <p>The colour is resolved from {@code --jc-surface} rather than repeated here as a hex. That
     * matters more than it looks: a literal would be a second copy of the palette living in Java,
     * and the copy would survive a token change untouched. Resolving it through a real element is
     * also the only way to read a {@code light-dark()} value at all, since the custom property
     * itself still holds both halves whichever scheme is in force.
     *
     * <p>It is deliberately not read off the header, which is the element it matches. The header
     * transitions its background over {@code --jc-transition-theme}, so for the 260ms after a flip
     * it reports an interpolated colour - and an interpolated colour is not a value anything should
     * be pinned to. The first version of this did read the header, and the strict guard below then
     * rejected every in-flight value and left the browser chrome on the previous theme for good.
     * A throwaway element carrying the same token with no transition of its own reports the
     * destination immediately; created and removed inside one task, it never paints.
     *
     * <p>The script takes no parameters and reads nothing but the document, so there is no value
     * here that could be interpolated into it.
     *
     * @param ui the UI whose page carries the meta tag; null is tolerated and does nothing
     */
    static void syncBrowserChrome(UI ui) {
        if (ui == null) {
            return;
        }
        // Deferred a frame so the theme attribute set by apply() is in force before anything is
        // resolved against it.
        ui.getPage().executeJs(
                """
                requestAnimationFrame(() => {
                  const probe = document.createElement('div');
                  probe.style.cssText =
                      'background-color: var(--jc-surface); transition: none; position: absolute;'
                      + 'visibility: hidden; pointer-events: none; inset: 0 auto auto 0;';
                  document.body.appendChild(probe);
                  const resolved = getComputedStyle(probe).backgroundColor;
                  probe.remove();
                  // Deny by default: only a fully opaque, fully resolved colour is published. A
                  // transparent or half-parsed value would tell the browser to paint its own chrome
                  // see-through, which is worse than leaving it at the default.
                  if (!/^rgb\\(\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\)$/.test(resolved)) {
                    return;
                  }
                  let tag = document.querySelector('meta[name="theme-color"]');
                  if (!tag) {
                    tag = document.createElement('meta');
                    tag.setAttribute('name', 'theme-color');
                    document.head.appendChild(tag);
                  }
                  tag.setAttribute('content', resolved);
                });
                """);
    }

    /**
     * Determines which mode is actually on screen and hands it to {@code onResolved}.
     *
     * <p>An explicit choice is authoritative and is re-applied, because a full page load may have
     * dropped the attribute. Otherwise the browser is asked what the operating system prefers; the
     * answer is only used to label the toggle, since the stylesheet has already applied it.
     *
     * @param ui the UI to resolve against; null is tolerated and never calls back
     * @param onResolved handed the scheme actually on screen, on the UI thread; null is tolerated
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
        // No choice stored, so the stylesheet's media query has already decided and nothing needs
        // applying - but the browser chrome still has to be told what it decided.
        syncBrowserChrome(ui);
        ui.getPage()
                .executeJs("return window.matchMedia('(prefers-color-scheme: dark)').matches")
                .then(Boolean.class, prefersDark ->
                        onResolved.accept(Boolean.TRUE.equals(prefersDark)
                                ? ThemeMode.DARK
                                : ThemeMode.LIGHT));
    }
}
