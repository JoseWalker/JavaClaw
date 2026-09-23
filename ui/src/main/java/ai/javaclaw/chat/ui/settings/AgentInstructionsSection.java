package ai.javaclaw.chat.ui.settings;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextArea;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static ai.javaclaw.JavaClawConfiguration.AGENT_MD;

/**
 * Lets a configured reader change AGENT.md's system instructions without redoing onboarding.
 *
 * <p>Reads and writes the file the same way {@code S4_AgentMdStep} does - {@code
 * agentWorkspace.createRelative(name)}, the private copy name first and falling back to the plain
 * {@code AGENT.md} - but as a direct file edit rather than a wizard step with session-carried state,
 * because this page has to work equally for a reader who never ran onboarding and one revisiting a
 * value they set weeks ago.
 *
 * <p>This section never calls {@link ai.javaclaw.configuration.ConfigurationManager}: AGENT.md is a
 * plain file, not a YAML property, so saving here fires no {@code ConfigurationChangedEvent} and
 * triggers no application restart. Unlike {@link ProviderSection} and {@link McpServersSection}, a
 * save here needs no {@link RestartNotice}.
 */
class AgentInstructionsSection extends Div {

    /** The workspace AGENT.md lives under; resolved once, from application configuration. */
    private final Resource agentWorkspace;

    /** The editable instructions, prefilled from disk in the constructor. */
    private final TextArea content = new TextArea("AGENT.md instructions");

    /** The control that runs {@link #save()}; kept as a field so tests can fire it directly. */
    private final NativeButton saveButton = new NativeButton("Save AGENT.md", event -> save());

    /**
     * Builds the section, already filled with whatever is on disk today.
     *
     * <p>Must run on the Vaadin UI thread, like any component construction added to a live layout.
     *
     * @param agentWorkspace the agent workspace root, from {@code agent.workspace}
     */
    AgentInstructionsSection(Resource agentWorkspace) {
        this.agentWorkspace = agentWorkspace;
        addClassName("jc-settings__section");

        content.setWidthFull();
        content.setMinHeight("16rem");
        content.setValue(readCurrentContent());

        saveButton.addClassName("jc-settings__save");

        Div actions = new Div(saveButton);
        actions.addClassName("jc-settings__actions");

        add(new H2("Agent instructions"),
                new Paragraph("The system instructions the agent reads on every conversation."),
                content, actions);
    }

    /**
     * Reads what is on disk today, mirroring {@code S4_AgentMdStep.prepareModel}'s fallback chain:
     * the private copy first, then the legacy plain name, then an empty editor when neither exists -
     * a reader who has never configured this file lands on a blank field, not an error.
     *
     * @return the file's current content, or the empty string when there is none yet
     */
    private String readCurrentContent() {
        String fromPrivateCopy = readFile(AGENT_MD);
        if (fromPrivateCopy != null) {
            return fromPrivateCopy;
        }
        String fromLegacyName = readFile("AGENT.md");
        return fromLegacyName != null ? fromLegacyName : "";
    }

    /**
     * Reads one candidate file, treating any failure - including, most commonly, the file simply not
     * existing yet - as "nothing here" rather than an error.
     *
     * @param name a file name relative to the agent workspace
     * @return the file's content, or null when it cannot be read
     */
    private String readFile(String name) {
        try {
            return agentWorkspace.createRelative(name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Validates the same way {@code S4_AgentMdStep.processStep} does - non-blank, nothing else - and
     * writes the file exactly as {@code S4_AgentMdStep.saveConfiguration} does.
     *
     * <p>Unlike that step, a write failure here is reported to the reader rather than thrown: the
     * step runs once during setup and can afford to fail loudly, but a page a reader returns to must
     * not crash out from under them on a disk or permissions error they can retry after fixing.
     */
    private void save() {
        content.setInvalid(false);
        String value = content.getValue() == null ? "" : content.getValue();
        if (value.isBlank()) {
            content.setErrorMessage("The AGENT.md instructions cannot be empty.");
            content.setInvalid(true);
            return;
        }
        try {
            Files.writeString(
                    agentWorkspace.createRelative(AGENT_MD).getFilePath(),
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            Notification.show("Could not save AGENT.md: " + e.getMessage());
            return;
        }
        Notification.show("AGENT.md saved.");
    }

    /**
     * Exposes the instructions field for tests.
     *
     * @return the instructions field, for tests asserting prefill and validation without a browser
     */
    TextArea content() {
        return content;
    }

    /**
     * Exposes the save control for tests.
     *
     * @return the save control, for tests that need to fire it without a browser
     */
    NativeButton saveButton() {
        return saveButton;
    }
}
