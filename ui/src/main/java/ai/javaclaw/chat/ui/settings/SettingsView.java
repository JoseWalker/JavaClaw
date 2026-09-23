package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.chat.ui.MainLayout;
import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

/**
 * A standalone, always-reachable page for changing what onboarding otherwise only asks once.
 *
 * <p>Before this existed, a configured reader had no way to change their default LLM provider,
 * model, AGENT.md instructions or MCP servers short of deleting their configuration and running
 * through {@code /onboarding/welcome} again - verified empirically: {@code GET /onboarding},
 * {@code /onboarding/2} and {@code /onboarding/3} all redirect back to the welcome step once {@code
 * application.private.yaml} exists, regardless of what is already configured. This page covers the
 * same ground as {@code S2_ProviderStep} through {@code S5_McpStep} without the wizard's session-
 * carried, run-once shape: every visit reads current configuration directly, and every save writes
 * it directly, rather than accumulating state across steps that only make sense on a first run.
 *
 * <p>Deliberately not the {@code /settings/agents} htmx page reachable from {@code
 * AgentPageController}/{@code SubagentController} - that page manages subagent delegation, an
 * already-solved, unrelated concern this page does not touch.
 *
 * <p>Composed of three independent sections rather than built as one class, because each has its own
 * read/validate/save concern and its own relationship to the restart a save can trigger: {@link
 * ProviderSection} and {@link McpServersSection} write through {@link ConfigurationManager} and end a
 * successful save with {@link RestartNotice}; {@link AgentInstructionsSection} writes a file directly
 * and never triggers a restart at all. Keeping them separate means a reader looking at one section's
 * code never has to hold the other two's concerns in their head.
 */
@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Settings - JavaClaw")
public class SettingsView extends Div {

    /**
     * Builds the page and every section on it, already prefilled from current configuration. Spring
     * and Vaadin supply every argument.
     *
     * <p>Must run on the Vaadin UI thread, like any component construction about to be added to a
     * live layout.
     *
     * @param configurationManager shared by {@link ProviderSection} and {@link McpServersSection},
     *     the only path either of them has to read or change {@code application.private.yaml}
     * @param environment read once for {@link ProviderSection}'s prefill; not held as a field here,
     *     since nothing on this page needs to re-read it after construction
     * @param agentOnboardingProviders the supported provider list {@link ProviderSection} reuses from
     *     the onboarding wizard, so the two never disagree about which providers exist
     * @param agentWorkspace the agent workspace root {@link AgentInstructionsSection} reads and
     *     writes AGENT.md under; {@code "Unknown"} default matches this module's other consumers of
     *     the same property, e.g. {@code StoredTranscripts}
     */
    public SettingsView(ConfigurationManager configurationManager, Environment environment,
                         AgentOnboardingProviders agentOnboardingProviders,
                         @Value("${agent.workspace:Unknown}") Resource agentWorkspace) {
        addClassName("jc-settings");
        add(new H1("Settings"));
        add(new ProviderSection(configurationManager, environment, agentOnboardingProviders));
        add(new AgentInstructionsSection(agentWorkspace));
        add(new McpServersSection(configurationManager));
    }
}
