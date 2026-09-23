package ai.javaclaw.chat.ui.settings;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

/**
 * Tells the reader that a save just triggered the application restart {@code
 * JavaClawApplicationMonitor} fires on every configuration change, and hides that restart behind a
 * scheduled reload rather than leaving them in a Vaadin session whose server process is about to
 * disappear underneath it.
 *
 * <p>{@code ConfigurationManager.updateProperties()}/{@code removeProperty()} publish a {@code
 * ConfigurationChangedEvent} unconditionally (see {@code base}'s {@code ConfigurationManager}), and
 * {@code JavaClawApplicationMonitor} in the {@code app} module closes and restarts the whole Spring
 * {@code ApplicationContext} two seconds after that event - unchanged behaviour, not something this
 * page can turn off. The existing htmx settings page survives that because a plain page reload
 * tolerates a server restart mid-flight; a stateful Vaadin session does not by default. Every save in
 * this package that reaches {@link ai.javaclaw.configuration.ConfigurationManager} routes through
 * here afterwards instead of returning silently, so the reader is told what is about to happen and
 * lands back on a working page once it has.
 *
 * <p>Not instantiable: a stateless pair of constants and one static call, in the shape of {@link
 * ai.javaclaw.chat.ui.Icons}.
 */
final class RestartNotice {

    /** What the reader is told while the restart is in flight. */
    private static final String MESSAGE = "Settings saved. Restarting to apply changes...";

    /**
     * How long after the save the browser reloads itself, in milliseconds.
     *
     * <p>Longer than the monitor's fixed two-second delay before it closes the old context, with
     * margin left for the new context to finish coming up. A reload issued inside that window would
     * land on a server that is either already gone or not yet accepting requests, and either way the
     * reader would see a connection error instead of their saved settings.
     */
    private static final int RELOAD_DELAY_MILLIS = 4000;

    /** Not instantiable; see the class note. */
    private RestartNotice() {
    }

    /**
     * Shows the restart notice and schedules a client-side reload after {@link
     * #RELOAD_DELAY_MILLIS}.
     *
     * <p>The reload is a plain {@code setTimeout} on the page rather than any Vaadin reconnect
     * mechanism: nobody has watched Vaadin's own reconnect dialog survive this specific restart, and
     * a page that assumes recovery it has not proven is a worse failure mode than one that forces a
     * clean reload the reader can see happen.
     *
     * <p>Must run on the Vaadin UI thread, like any call that reaches the browser.
     *
     * @param anchor a component attached to the page the reload should happen on; never null
     */
    static void showAndScheduleReload(Component anchor) {
        Notification notification =
                Notification.show(MESSAGE, RELOAD_DELAY_MILLIS, Notification.Position.BOTTOM_CENTER);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        anchor.getElement().executeJs("setTimeout(() => location.reload(), $0)", RELOAD_DELAY_MILLIS);
    }
}
