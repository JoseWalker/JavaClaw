package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.chat.ui.UiHarness;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.NativeButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link AgentInstructionsSection}'s read/validate/write logic against a real temporary
 * workspace rather than a mock, because the behaviour under test - which of two file names wins, and
 * what {@link java.nio.file.Files#writeString} actually leaves on disk - is a filesystem contract
 * that a mocked {@link Resource} would only restate rather than verify.
 */
class AgentInstructionsSectionTest {

    @Test
    @DisplayName("reads AGENT.private.md when it exists, in preference to the legacy name")
    void prefersPrivateCopy(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("AGENT.private.md"), "private instructions", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AGENT.md"), "legacy instructions", StandardCharsets.UTF_8);

        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));

        assertThat(section.content().getValue()).isEqualTo("private instructions");
    }

    @Test
    @DisplayName("falls back to the legacy AGENT.md when no private copy exists")
    void fallsBackToLegacyName(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("AGENT.md"), "legacy instructions", StandardCharsets.UTF_8);

        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));

        assertThat(section.content().getValue()).isEqualTo("legacy instructions");
    }

    @Test
    @DisplayName("a reader who has never configured either file lands on a blank editor, not an error")
    void blankWhenNeitherFileExists(@TempDir Path workspace) {
        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));

        assertThat(section.content().getValue()).isEmpty();
    }

    @Test
    @DisplayName("refuses to save blank instructions, matching S4_AgentMdStep's message")
    void rejectsBlankContent(@TempDir Path workspace) {
        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));
        section.content().setValue("");

        click(section.saveButton());

        assertThat(section.content().isInvalid()).isTrue();
        assertThat(section.content().getErrorMessage())
                .isEqualTo("The AGENT.md instructions cannot be empty.");
        assertThat(Files.exists(workspace.resolve("AGENT.private.md"))).isFalse();
    }

    @Test
    @DisplayName("a valid save writes AGENT.private.md, exactly as S4_AgentMdStep.saveConfiguration does")
    void savesToThePrivateFileName(@TempDir Path workspace) throws IOException {
        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));
        // A successful save calls Notification.show, which needs a current UI to attach to - the
        // same reason ProviderSectionTest's happy-path tests attach through UiHarness.
        UI ui = UiHarness.ui();
        ui.add(section);
        section.content().setValue("updated instructions");

        click(section.saveButton());

        assertThat(section.content().isInvalid()).isFalse();
        assertThat(Files.readString(workspace.resolve("AGENT.private.md"), StandardCharsets.UTF_8))
                .isEqualTo("updated instructions");
    }

    @Test
    @DisplayName("saving overwrites rather than appends, on a second save")
    void secondSaveReplacesRatherThanAppends(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("AGENT.private.md"), "first version", StandardCharsets.UTF_8);
        AgentInstructionsSection section = new AgentInstructionsSection(workspaceResource(workspace));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.content().setValue("second version");

        click(section.saveButton());

        assertThat(Files.readString(workspace.resolve("AGENT.private.md"), StandardCharsets.UTF_8))
                .isEqualTo("second version");
    }

    /**
     * Wraps a {@code @TempDir} as the section under test expects to receive {@code agent.workspace}:
     * as a directory, with a trailing separator.
     *
     * <p>Without the trailing separator, {@link FileSystemResource#createRelative(String)} resolves
     * {@code "AGENT.md"} as a <em>sibling</em> of the workspace directory rather than a child inside
     * it - {@code Resource.createRelative} strips everything after the last {@code '/'} in the
     * resource's own path before appending, the same rule a browser applies to a relative link, and a
     * path with no trailing slash treats its last segment as a file name to be replaced rather than a
     * directory to descend into. Production configuration never hits this: {@code agent.workspace}'s
     * documented default is {@code file:./workspace/}, already carrying the slash this method adds
     * back for a bare {@link Path}.
     *
     * @param workspace a temporary directory
     * @return a resource pointing at it, safe to pass to {@link AgentInstructionsSection}'s constructor
     */
    private static Resource workspaceResource(Path workspace) {
        return new FileSystemResource(workspace.toString() + "/");
    }

    private static void click(NativeButton button) {
        ComponentUtil.fireEvent(button,
                new ClickEvent<>(button, true, 0, 0, 0, 0, 1, 0, false, false, false, false));
    }
}
