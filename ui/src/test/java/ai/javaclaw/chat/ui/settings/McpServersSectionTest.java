package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.chat.ui.UiHarness;
import ai.javaclaw.configuration.ConfigurationManager;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.NativeButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link McpServersSection}'s config reading, validation and property-building against a
 * mocked {@link ConfigurationManager}, following {@code KeyboardAccessTest}'s pattern of firing a
 * synthetic click through {@link ComponentUtil} rather than calling a private method directly.
 */
class McpServersSectionTest {

    @Test
    @DisplayName("loads both connection types from the YAML tree, the way S5_McpStep.loadServersFromConfig does")
    void loadsExistingServersOfBothTypes() throws IOException {
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        when(configurationManager.readApplicationYaml()).thenReturn(yamlWith(
                Map.of("existing-http", Map.of("url", "https://example.com", "endpoint", "/mcp")),
                Map.of("existing-stdio", Map.of("command", "npx"))));

        McpServersSection section = new McpServersSection(configurationManager);

        List<Map<String, Object>> servers = section.loadedServers();
        assertThat(servers).hasSize(2);
        assertThat(servers).anySatisfy(server -> {
            assertThat(server.get("name")).isEqualTo("existing-http");
            assertThat(server.get("type")).isEqualTo("streamable-http");
            assertThat(server.get("url")).isEqualTo("https://example.com/mcp");
        });
        assertThat(servers).anySatisfy(server -> {
            assertThat(server.get("name")).isEqualTo("existing-stdio");
            assertThat(server.get("type")).isEqualTo("stdio");
        });
    }

    @Test
    @DisplayName("no configuration at all reads as an empty list, not an error")
    void emptyConfigurationReadsAsNoServers() throws IOException {
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        when(configurationManager.readApplicationYaml()).thenReturn(new LinkedHashMap<>());

        McpServersSection section = new McpServersSection(configurationManager);

        assertThat(section.loadedServers()).isEmpty();
    }

    @Test
    @DisplayName("refuses a blank server name")
    void rejectsBlankName() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        section.nameField().setValue("");
        section.urlField().setValue("https://example.com/mcp");

        click(section.addButton());

        assertThat(section.nameField().isInvalid()).isTrue();
        assertThat(section.nameField().getErrorMessage()).isEqualTo("Server name is required.");
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("refuses a server name with characters outside letters, numbers, hyphen and underscore")
    void rejectsInvalidNameCharacters() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        section.nameField().setValue("bad name!");
        section.urlField().setValue("https://example.com/mcp");

        click(section.addButton());

        assertThat(section.nameField().isInvalid()).isTrue();
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("refuses a name that already exists in configuration")
    void rejectsDuplicateName() throws IOException {
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        when(configurationManager.readApplicationYaml()).thenReturn(yamlWith(
                Map.of("taken", Map.of("url", "https://example.com", "endpoint", "/mcp")), Map.of()));
        McpServersSection section = new McpServersSection(configurationManager);
        section.nameField().setValue("taken");
        section.urlField().setValue("https://elsewhere.example/mcp");

        click(section.addButton());

        assertThat(section.nameField().isInvalid()).isTrue();
        assertThat(section.nameField().getErrorMessage()).isEqualTo("A server named 'taken' already exists.");
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("streamable-http requires a URL")
    void rejectsBlankUrlForStreamableHttp() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        section.nameField().setValue("no-url");
        section.urlField().setValue("");

        click(section.addButton());

        assertThat(section.urlField().isInvalid()).isTrue();
        assertThat(section.urlField().getErrorMessage()).isEqualTo("URL is required for streamable-http.");
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("stdio requires a command")
    void rejectsBlankCommandForStdio() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        section.nameField().setValue("no-command");
        section.typeField().setValue("stdio");
        section.commandField().setValue("");

        click(section.addButton());

        assertThat(section.commandField().isInvalid()).isTrue();
        assertThat(section.commandField().getErrorMessage()).isEqualTo("Command is required for stdio.");
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("adding a streamable-http server writes url, endpoint and headers, as S5_McpStep does")
    void addsStreamableHttpServer() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        UI ui = UiHarness.ui();
        ui.add(section);
        section.nameField().setValue("my-server");
        section.urlField().setValue("https://example.com/mcp/v1");
        section.headersField().setValue("Authorization: Bearer token\nX-Extra: value");

        click(section.addButton());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.streamable-http.connections.my-server.url", "https://example.com");
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.streamable-http.connections.my-server.endpoint", "/mcp/v1");
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.streamable-http.connections.my-server.headers.Authorization",
                "Bearer token");
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.streamable-http.connections.my-server.headers.X-Extra", "value");
    }

    @Test
    @DisplayName("adding a stdio server writes command, args and env, as S5_McpStep does")
    void addsStdioServer() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        UI ui = UiHarness.ui();
        ui.add(section);
        section.nameField().setValue("local-tool");
        section.typeField().setValue("stdio");
        section.commandField().setValue("npx");
        section.argsField().setValue("-y\nsome-package");
        section.envField().setValue("API_TOKEN=secret");

        click(section.addButton());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.stdio.connections.local-tool.command", "npx");
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.stdio.connections.local-tool.args", List.of("-y", "some-package"));
        assertThat(saved).containsEntry(
                "spring.ai.mcp.client.stdio.connections.local-tool.env.API_TOKEN", "secret");
    }

    @Test
    @DisplayName("removing a server deletes its whole connection subtree in a single call")
    void removesServerWithOneKey() throws IOException {
        ConfigurationManager configurationManager = emptyConfig();
        McpServersSection section = new McpServersSection(configurationManager);
        UI ui = UiHarness.ui();
        ui.add(section);

        section.removeServerForTest("gone", "streamable-http");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(configurationManager, times(1)).removeProperty(captor.capture());
        // Exactly one call, naming the connection's own key - not one call per leaf field
        // (url/endpoint/headers), because removing that single key removes the whole subtree.
        assertThat(captor.getValue()).isEqualTo("spring.ai.mcp.client.streamable-http.connections.gone");
    }

    private static ConfigurationManager emptyConfig() throws IOException {
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        when(configurationManager.readApplicationYaml()).thenReturn(new LinkedHashMap<>());
        return configurationManager;
    }

    /**
     * @param httpConnections streamable-http connections, keyed by server name
     * @param stdioConnections stdio connections, keyed by server name
     * @return a YAML tree shaped like {@code application.private.yaml}'s {@code spring.ai.mcp.client} node
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlWith(Map<String, Object> httpConnections,
                                                 Map<String, Object> stdioConnections) {
        Map<String, Object> client = new LinkedHashMap<>();
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("connections", httpConnections);
        Map<String, Object> stdio = new LinkedHashMap<>();
        stdio.put("connections", stdioConnections);
        client.put("streamable-http", http);
        client.put("stdio", stdio);
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("client", client);
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("mcp", mcp);
        Map<String, Object> spring = new LinkedHashMap<>();
        spring.put("ai", ai);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("spring", spring);
        return root;
    }

    private static void click(NativeButton button) {
        ComponentUtil.fireEvent(button,
                new ClickEvent<>(button, true, 0, 0, 0, 0, 1, 0, false, false, false, false));
    }
}
