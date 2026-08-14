package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;
import com.vaadin.flow.component.dependency.StyleSheet;

/**
 * Application shell for the Vaadin UI.
 *
 * <p>Styles are loaded with {@link StyleSheet} from {@code META-INF/resources} rather than with
 * {@code @CssImport} or a {@code frontend/themes} folder. That is deliberate and load bearing: those
 * two mechanisms compile styles into the frontend bundle, which forces a Node/Vite build on every
 * contributor, in CI, and inside a container image that has no Node. Served as a plain static
 * resource, the stylesheet keeps the project buildable with nothing but a JDK, which is how it
 * builds today. The same rule applies to every style added later - if it cannot be expressed as a
 * stylesheet under {@code META-INF/resources}, it needs a decision, not a workaround.
 *
 * <p>{@link Push} is required because responses are streamed from a background thread; without it
 * the tokens would only reach the browser on the next client-initiated request.
 */
@Push
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("/styles/javaclaw.css")
public class JavaClawAppShell implements AppShellConfigurator {

    /**
     * Vaadin instantiates the shell reflectively, so a public no-argument constructor has to exist.
     * It is written out rather than left implicit because an implicit one carries no documentation,
     * and a reader arriving here needs to be told that the class is never constructed by our code.
     */
    public JavaClawAppShell() {
    }
}
