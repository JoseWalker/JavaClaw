package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.configuration.ConfigurationManager;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists, adds and removes the MCP servers {@code S5_McpStep} configures during onboarding, without
 * requiring the wizard to be re-run.
 *
 * <p>The server list is read fresh from {@code application.private.yaml} on every render rather than
 * carried in session state the way the wizard's step does: this page has no multi-step flow to carry
 * state across, and a reader who removes a server should see the removal reflected immediately by
 * reading it back, not by trusting a client-side list to stay in sync with the file.
 *
 * <p>Adding a server writes through {@link ConfigurationManager}, which fires the configuration-
 * changed event that restarts the application; removing one does too, since {@code removeProperty}
 * writes the file back just like {@code updateProperties} does. Both routes end by calling {@link
 * RestartNotice}.
 */
class McpServersSection extends Div {

    /** Reports a read failure from {@link #loadServersFromConfig()} without interrupting the page. */
    private static final Logger log = LoggerFactory.getLogger(McpServersSection.class);

    /** One of the two server types {@code S5_McpStep} supports; see {@code processStep}. */
    private static final String STREAMABLE_HTTP = "streamable-http";

    /** The other of the two server types {@code S5_McpStep} supports. */
    private static final String STDIO = "stdio";

    /**
     * What a server name, header name or environment variable name is allowed to contain.
     *
     * <p>{@code ConfigurationManager.setNestedValue} splits every property key it is given on {@code
     * "."} to walk/build the nested YAML map, so a name containing a dot does not save as a single
     * value - it saves as an extra, unintended level of nesting under whatever came before the dot,
     * and if that shorter path already holds a plain value (rather than a map) from an unrelated key,
     * the cast in {@code setNestedValue} throws instead of failing gracefully. Restricting every name
     * this page writes into a property key to this pattern keeps every key exactly one segment long,
     * so it can never collide with or nest under another. This is the same pattern the server-name
     * field already validated against; headers and environment variable names now share it too.
     */
    private static final String NAME_PATTERN = "[a-zA-Z0-9_-]+";

    /** Property prefix for a streamable-http connection's keys, mirroring {@code S5_McpStep}. */
    private static final String HTTP_BASE = "spring.ai.mcp.client.streamable-http.connections.";

    /** Property prefix for a stdio connection's keys, mirroring {@code S5_McpStep}. */
    private static final String STDIO_BASE = "spring.ai.mcp.client.stdio.connections.";

    /** Where an add or remove is written, and where the current list is read back from. */
    private final ConfigurationManager configurationManager;

    /** Rebuilt from disk on every {@link #refreshServerList()} call. */
    private final Div serverList = new Div();

    /** The new server's name; validated in {@link #addServer()} against the same pattern {@code
     *  S5_McpStep} uses. */
    private final TextField nameField = new TextField("Name");

    /** Which of the two supported connection kinds to add; toggles {@link #applyTypeVisibility()}. */
    private final Select<String> typeField = new Select<>();

    /** The streamable-http endpoint; shown only when {@link #typeField} is {@link #STREAMABLE_HTTP}. */
    private final TextField urlField = new TextField("URL");

    /** Streamable-http request headers, one {@code Key: Value} pair per line. */
    private final TextArea headersField = new TextArea("Headers (one \"Key: Value\" per line)");

    /** The stdio launch command; shown only when {@link #typeField} is {@link #STDIO}. */
    private final TextField commandField = new TextField("Command");

    /** Stdio command-line arguments, one per line. */
    private final TextArea argsField = new TextArea("Arguments (one per line)");

    /** Stdio environment variables, one {@code KEY=VALUE} pair per line. */
    private final TextArea envField = new TextArea("Environment (one KEY=VALUE per line)");

    /** The control that runs {@link #addServer()}; kept as a field so tests can fire it directly. */
    private final NativeButton addButton = new NativeButton("Add server", event -> addServer());

    /**
     * Builds the section and its current server list.
     *
     * <p>Must run on the Vaadin UI thread, like any component construction added to a live layout.
     *
     * @param configurationManager where a valid add or remove is written, and where the current
     *     server list is read from
     */
    McpServersSection(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
        addClassName("jc-settings__section");
        serverList.addClassName("jc-settings__mcp-list");

        typeField.setLabel("Type");
        typeField.setItems(STREAMABLE_HTTP, STDIO);
        typeField.setValue(STREAMABLE_HTTP);
        typeField.setEmptySelectionAllowed(false);
        typeField.addValueChangeListener(event -> applyTypeVisibility());

        addButton.addClassName("jc-settings__save");

        // Same grouping ProviderSection uses: fields as a grid, the submit action as its own row.
        Div fields = new Div(nameField, typeField, urlField, headersField, commandField, argsField,
                envField);
        fields.addClassName("jc-settings__fields");

        Div actions = new Div(addButton);
        actions.addClassName("jc-settings__actions");

        Div form = new Div(fields, actions);
        form.addClassName("jc-settings__mcp-form");

        add(new H2("MCP servers"),
                new Paragraph("Tools the agent can reach over MCP, in addition to what is built in."),
                serverList, new H3("Add a server"), form);

        applyTypeVisibility();
        refreshServerList();
    }

    /**
     * Shows only the fields the selected type actually uses - URL and headers for streamable-http,
     * command/arguments/environment for stdio - so the form does not ask for input {@link
     * #addServer()} would ignore.
     */
    private void applyTypeVisibility() {
        boolean http = STREAMABLE_HTTP.equals(typeField.getValue());
        urlField.setVisible(http);
        headersField.setVisible(http);
        commandField.setVisible(!http);
        argsField.setVisible(!http);
        envField.setVisible(!http);
    }

    /**
     * Validates in the same order and with the same messages {@code S5_McpStep.processStep} uses for
     * its {@code "add"} action, then writes the new server's keys and hands off to {@link
     * RestartNotice}.
     *
     * <p>Only the new server's own keys are written - unlike the onboarding step, which re-saves
     * every server accumulated in the wizard's session on each step, this page has no session list to
     * flush; whatever is already in the file stays exactly as it is.
     */
    private void addServer() {
        clearErrors();
        String name = trim(nameField.getValue());
        String type = typeField.getValue();

        if (name.isBlank()) {
            nameField.setErrorMessage("Server name is required.");
            nameField.setInvalid(true);
            return;
        }
        if (!name.matches(NAME_PATTERN)) {
            nameField.setErrorMessage("Server name may only contain letters, numbers, hyphens and underscores.");
            nameField.setInvalid(true);
            return;
        }
        List<Map<String, Object>> existing = loadServersFromConfig();
        if (existing.stream().anyMatch(s -> name.equals(s.get("name")))) {
            nameField.setErrorMessage("A server named '" + name + "' already exists.");
            nameField.setInvalid(true);
            return;
        }

        Map<String, Object> props;
        if (STREAMABLE_HTTP.equals(type)) {
            props = buildStreamableHttpProperties(name);
        } else {
            props = buildStdioProperties(name);
        }
        if (props == null) {
            // A field-level error was already set by the builder below; nothing left to do.
            return;
        }

        try {
            configurationManager.updateProperties(props);
        } catch (IOException e) {
            Notification.show("Could not save the MCP server: " + e.getMessage());
            return;
        }
        clearForm();
        refreshServerList();
        RestartNotice.showAndScheduleReload(this);
    }

    /**
     * Validates the URL and header fields and, when both pass, builds the properties a
     * streamable-http server needs.
     *
     * @param name the already-validated, unique server name
     * @return the properties to write for a streamable-http server, or null when the URL or a header
     *     name failed validation - the failing field's own error message is set before returning null
     */
    private Map<String, Object> buildStreamableHttpProperties(String name) {
        String url = trim(urlField.getValue());
        if (url.isBlank()) {
            urlField.setErrorMessage("URL is required for streamable-http.");
            urlField.setInvalid(true);
            return null;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            urlField.setErrorMessage("Enter a valid URL.");
            urlField.setInvalid(true);
            return null;
        }
        // A syntactically valid URI is not necessarily one this page can use: "example.com/mcp" with
        // no scheme parses fine as a path-only URI, with both getScheme() and getHost() null, and
        // would otherwise be saved as the literal string "null://null" below - a server entry that
        // looks saved but fails at connection time on every restart until someone notices.
        if (!isHttpOrHttps(uri.getScheme()) || uri.getHost() == null) {
            urlField.setErrorMessage("Enter a valid URL, including the http:// or https:// scheme.");
            urlField.setInvalid(true);
            return null;
        }
        Map<String, String> headers = parseColonSeparated(headersField.getValue());
        if (!allNamesValid(headers.keySet())) {
            headersField.setErrorMessage(
                    "Header names may only contain letters, numbers, hyphens and underscores.");
            headersField.setInvalid(true);
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(HTTP_BASE + name + ".url", uri.getScheme() + "://" + uri.getAuthority());
        props.put(HTTP_BASE + name + ".endpoint", uri.getPath());
        headers.forEach((k, v) -> props.put(HTTP_BASE + name + ".headers." + k, v));
        return props;
    }

    /**
     * Whether a URI scheme is one this page's saved {@code url} property can be dereferenced with.
     *
     * @param scheme a URI's scheme, or null when the URI had none
     * @return true only for {@code http} or {@code https}, case-insensitively
     */
    private static boolean isHttpOrHttps(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * Whether every name in a set is safe to use as one segment of a {@link ConfigurationManager}
     * property key; see {@link #NAME_PATTERN}.
     *
     * @param names header or environment variable names parsed from a form field
     * @return true when every name matches {@link #NAME_PATTERN}; true for an empty set
     */
    private static boolean allNamesValid(Iterable<String> names) {
        for (String name : names) {
            if (!name.matches(NAME_PATTERN)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the command and environment fields and, when both pass, builds the properties a
     * stdio server needs.
     *
     * @param name the already-validated, unique server name
     * @return the properties to write for a stdio server, or null when the command or an environment
     *     variable name failed validation - the failing field's own error message is set before
     *     returning null
     */
    private Map<String, Object> buildStdioProperties(String name) {
        String command = trim(commandField.getValue());
        if (command.isBlank()) {
            commandField.setErrorMessage("Command is required for stdio.");
            commandField.setInvalid(true);
            return null;
        }
        Map<String, String> env = parseEqualsSeparated(envField.getValue());
        if (!allNamesValid(env.keySet())) {
            envField.setErrorMessage(
                    "Environment variable names may only contain letters, numbers, hyphens and underscores.");
            envField.setInvalid(true);
            return null;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(STDIO_BASE + name + ".command", command);
        List<String> args = parseLines(argsField.getValue());
        if (!args.isEmpty()) {
            props.put(STDIO_BASE + name + ".args", args);
        }
        env.forEach((k, v) -> props.put(STDIO_BASE + name + ".env." + k, v));
        return props;
    }

    /**
     * Removes every key one server occupies and hands off to {@link RestartNotice}.
     *
     * <p>One {@link ConfigurationManager#removeProperty} call, not one per leaf key: a server's
     * fields (url/endpoint/headers, or command/args/env) are all written as nested values under one
     * connection key, so removing that single key removes the whole subtree in the same write - see
     * {@code ConfigurationManager}'s nested-map removal. {@code updateProperties} cannot do this at
     * all, since it only ever adds or overwrites keys.
     *
     * @param server one row as {@link #loadServersFromConfig} produced it, carrying at least
     *     {@code "name"} and {@code "type"}
     */
    private void removeServer(Map<String, Object> server) {
        String name = String.valueOf(server.get("name"));
        String type = String.valueOf(server.get("type"));
        String base = STREAMABLE_HTTP.equals(type) ? HTTP_BASE : STDIO_BASE;
        try {
            configurationManager.removeProperty(base + name);
        } catch (IOException e) {
            Notification.show("Could not remove '" + name + "': " + e.getMessage());
            return;
        }
        refreshServerList();
        RestartNotice.showAndScheduleReload(this);
    }

    /** Empties every add-form field, ready for the next entry. */
    private void clearForm() {
        nameField.clear();
        urlField.clear();
        headersField.clear();
        commandField.clear();
        argsField.clear();
        envField.clear();
    }

    /** Clears every field-level error left over from a previous failed attempt. */
    private void clearErrors() {
        nameField.setInvalid(false);
        urlField.setInvalid(false);
        headersField.setInvalid(false);
        commandField.setInvalid(false);
        envField.setInvalid(false);
    }

    /** Rebuilds {@link #serverList} from what is on disk right now. */
    private void refreshServerList() {
        serverList.removeAll();
        List<Map<String, Object>> servers = loadServersFromConfig();
        if (servers.isEmpty()) {
            serverList.add(new Paragraph("No MCP servers configured."));
            return;
        }
        servers.forEach(server -> serverList.add(serverRow(server)));
    }

    /**
     * Renders one configured server as a row in {@link #serverList}.
     *
     * @param server one row as {@link #loadServersFromConfig} produced it
     * @return a labelled row with a remove action, wired to {@link #removeServer}
     */
    private Component serverRow(Map<String, Object> server) {
        String name = String.valueOf(server.get("name"));
        String type = String.valueOf(server.get("type"));
        Span label = new Span(name + " (" + type + ")");
        NativeButton remove = new NativeButton("Remove", event -> removeServer(server));
        remove.setAriaLabel("Remove " + name);
        remove.addClassName("jc-settings__mcp-remove");
        Div row = new Div(label, remove);
        row.addClassName("jc-settings__mcp-row");
        return row;
    }

    // ------------------------------------------------------------------ reading config, mirrors S5_McpStep

    /**
     * Reads the current server list from {@code application.private.yaml}, the same navigation {@code
     * S5_McpStep.loadServersFromConfig} does.
     *
     * <p>A connection entry that is not itself a map - empty from a hand edit, or left behind
     * mid-shape by a previous write that failed partway through - is skipped rather than crashing
     * this method: every caller here is either rendering the whole Settings page or validating a new
     * server name against the existing list, and one bad entry in the file is not a reason to lock
     * the reader out of the page that is the only reasonable way for them to fix or remove it.
     *
     * @return every configured server, streamable-http first then stdio, skipping any entry that is
     *     not a map; empty when none are configured or the file cannot be read
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadServersFromConfig() {
        List<Map<String, Object>> servers = new ArrayList<>();
        try {
            Map<String, Object> yaml = configurationManager.readApplicationYaml();
            Map<String, Object> mcp = navigateTo(yaml, "spring", "ai", "mcp", "client");
            if (mcp == null) {
                return servers;
            }

            Map<String, Object> httpConns = navigateTo(mcp, "streamable-http", "connections");
            if (httpConns != null) {
                httpConns.forEach((name, v) -> {
                    if (!(v instanceof Map)) {
                        log.warn("Skipping malformed streamable-http MCP server entry '{}': "
                                + "expected a map, found {}", name, v == null ? "null" : v.getClass());
                        return;
                    }
                    Map<String, Object> conn = (Map<String, Object>) v;
                    Map<String, Object> server = new LinkedHashMap<>();
                    server.put("name", name);
                    server.put("type", STREAMABLE_HTTP);
                    String url = String.valueOf(conn.getOrDefault("url", ""));
                    String endpoint = String.valueOf(conn.getOrDefault("endpoint", ""));
                    server.put("url", url + endpoint);
                    servers.add(server);
                });
            }

            Map<String, Object> stdioConns = navigateTo(mcp, "stdio", "connections");
            if (stdioConns != null) {
                stdioConns.forEach((name, v) -> {
                    if (!(v instanceof Map)) {
                        log.warn("Skipping malformed stdio MCP server entry '{}': expected a map, "
                                + "found {}", name, v == null ? "null" : v.getClass());
                        return;
                    }
                    Map<String, Object> conn = (Map<String, Object>) v;
                    Map<String, Object> server = new LinkedHashMap<>();
                    server.put("name", name);
                    server.put("type", STDIO);
                    server.put("command", conn.getOrDefault("command", ""));
                    servers.add(server);
                });
            }
        } catch (IOException e) {
            log.warn("Failed to load MCP servers from config: {}", e.getMessage());
        }
        return servers;
    }

    /**
     * Walks a chain of nested map keys, the way a YAML path like {@code spring.ai.mcp.client} has to
     * be walked one segment at a time once snakeyaml has parsed it into plain {@code Map}s.
     *
     * @param map a YAML tree (or subtree) read by snakeyaml, so nested values are plain {@code Map}s
     * @param keys the path to navigate, outermost first
     * @return the map found at that path, or null when any step of the path is absent or not itself
     *     a map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigateTo(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map == null) {
                return null;
            }
            Object next = map.get(key);
            map = next instanceof Map ? (Map<String, Object>) next : null;
        }
        return map;
    }

    /**
     * Parses {@code Key: Value} lines into a map, exactly as {@code S5_McpStep.parseColonSeparated}
     * does for MCP headers.
     *
     * @param text raw textarea content, possibly null
     * @return one entry per non-blank, colon-separated line; empty when {@code text} is null
     */
    private static Map<String, String> parseColonSeparated(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            int sep = line.indexOf(':');
            if (sep > 0) {
                result.put(line.substring(0, sep).trim(), line.substring(sep + 1).trim());
            }
        }
        return result;
    }

    /**
     * Parses {@code KEY=VALUE} lines into a map, exactly as {@code S5_McpStep.parseEqualsSeparated}
     * does for stdio environment variables.
     *
     * @param text raw textarea content, possibly null
     * @return one entry per non-blank, equals-separated line; empty when {@code text} is null
     */
    private static Map<String, String> parseEqualsSeparated(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\n")) {
            line = line.trim();
            int sep = line.indexOf('=');
            if (sep > 0) {
                result.put(line.substring(0, sep).trim(), line.substring(sep + 1).trim());
            }
        }
        return result;
    }

    /**
     * Parses one value per line into a list, ignoring blanks, exactly as {@code
     * S5_McpStep.parseLines} does for stdio arguments.
     *
     * @param text raw textarea content, possibly null
     * @return the non-blank lines, trimmed; empty when {@code text} is null
     */
    private static List<String> parseLines(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Normalises a field's raw value for validation and saving.
     *
     * @param value a field value, possibly null
     * @return the value trimmed, or the empty string when it was null
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------------ test seams

    /**
     * Exposes the name field for tests.
     *
     * @return the name field, for tests asserting validation without a browser
     */
    TextField nameField() {
        return nameField;
    }

    /**
     * Exposes the type selector for tests.
     *
     * @return the type selector, for tests asserting form visibility and validation
     */
    Select<String> typeField() {
        return typeField;
    }

    /**
     * Exposes the URL field for tests.
     *
     * @return the URL field, for tests asserting streamable-http validation
     */
    TextField urlField() {
        return urlField;
    }

    /**
     * Exposes the headers field for tests.
     *
     * @return the headers field, for tests asserting streamable-http header parsing
     */
    TextArea headersField() {
        return headersField;
    }

    /**
     * Exposes the command field for tests.
     *
     * @return the command field, for tests asserting stdio validation
     */
    TextField commandField() {
        return commandField;
    }

    /**
     * Exposes the arguments field for tests.
     *
     * @return the arguments field, for tests asserting stdio argument parsing
     */
    TextArea argsField() {
        return argsField;
    }

    /**
     * Exposes the environment field for tests.
     *
     * @return the environment field, for tests asserting stdio environment parsing
     */
    TextArea envField() {
        return envField;
    }

    /**
     * Exposes the add control for tests.
     *
     * @return the add control, for tests that need to fire it without a browser
     */
    NativeButton addButton() {
        return addButton;
    }

    /**
     * Exposes the current server list for tests.
     *
     * @return the current server rows, for tests asserting what {@link #refreshServerList} loaded
     */
    List<Map<String, Object>> loadedServers() {
        return loadServersFromConfig();
    }

    /**
     * Removes a server by name and type, for tests exercising {@link #removeServer} without needing
     * to click a generated row's button.
     *
     * @param name the server's configured name
     * @param type either {@code "streamable-http"} or {@code "stdio"}
     */
    void removeServerForTest(String name, String type) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", name);
        server.put("type", type);
        removeServer(server);
    }
}
