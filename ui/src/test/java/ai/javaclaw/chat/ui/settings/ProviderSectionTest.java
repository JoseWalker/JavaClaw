package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.chat.ui.UiHarness;
import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Exercises {@link ProviderSection}'s prefill and validation without a browser, following {@code
 * KeyboardAccessTest}'s pattern of firing a synthetic click through {@link ComponentUtil} rather
 * than calling a private method directly - the same reasoning applies here as there: a test that
 * called {@code save()} would pass on a defect in how the button is wired to it.
 */
class ProviderSectionTest {

    private static final String PROVIDER_KEY = "agent.llm.providers.default.provider";
    private static final String MODEL_KEY = "agent.llm.providers.default.model";
    private static final String BASE_URL_KEY = "agent.llm.providers.default.base-url";
    private static final String API_KEY_KEY = "agent.llm.providers.default.api-key";

    @Test
    @DisplayName("prefills provider, model and base URL from current configuration, but never the API key")
    void prefillsFromEnvironment() {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        AgentOnboardingProviders agentOnboardingProviders =
                providers(openai, new FakeProvider("ollama", "Ollama", false));
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PROVIDER_KEY, "openai")
                .withProperty(MODEL_KEY, "gpt-4o")
                .withProperty(BASE_URL_KEY, "https://api.openai.com")
                .withProperty(API_KEY_KEY, "sk-test");

        ProviderSection section =
                new ProviderSection(mock(ConfigurationManager.class), environment, agentOnboardingProviders);

        assertThat(section.providerField().getValue()).isEqualTo(openai);
        assertThat(section.modelField().getValue()).isEqualTo("gpt-4o");
        assertThat(section.baseUrlField().getValue()).isEqualTo("https://api.openai.com");
        // The stored key exists (API_KEY_KEY is set above) but must never be sent to the browser -
        // that is the whole point of the fix: a PasswordField's value sits in page state regardless
        // of the masked display, readable by anyone who opens /settings via the field's own
        // plaintext-reveal toggle.
        assertThat(section.apiKeyField().getValue()).isEmpty();
        assertThat(section.apiKeyField().getPlaceholder()).isEqualTo("Leave blank to keep the current key");
        assertThat(section.clearApiKeyButton().isVisible()).isTrue();
    }

    @Test
    @DisplayName("no stored API key leaves the clear button hidden and prompts for one instead")
    void noStoredApiKeyHidesClearButton() {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        ProviderSection section = new ProviderSection(mock(ConfigurationManager.class),
                new MockEnvironment(), providers(openai));

        assertThat(section.apiKeyField().getValue()).isEmpty();
        assertThat(section.apiKeyField().getPlaceholder()).isEqualTo("Enter an API key");
        assertThat(section.clearApiKeyButton().isVisible()).isFalse();
    }

    @Test
    @DisplayName("an unconfigured provider id prefills to no selection rather than the wrong one")
    void unknownPrefilledProviderLeavesNoSelection() {
        MockEnvironment environment = new MockEnvironment().withProperty(PROVIDER_KEY, "not-configured");
        ProviderSection section = new ProviderSection(mock(ConfigurationManager.class), environment,
                providers(new FakeProvider("openai", "OpenAI", true)));

        assertThat(section.providerField().getValue()).isNull();
    }

    @Test
    @DisplayName("refuses to save a blank model, matching S3_CredentialsStep's message")
    void rejectsBlankModel() throws IOException {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section =
                new ProviderSection(configurationManager, new MockEnvironment(), providers(openai));
        section.providerField().setValue(openai);
        section.modelField().setValue("");
        section.apiKeyField().setValue("sk-test");

        click(section.saveButton());

        assertThat(section.modelField().isInvalid()).isTrue();
        assertThat(section.modelField().getErrorMessage()).isEqualTo("Enter a model to continue.");
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("refuses to save without an API key when the provider requires one")
    void rejectsMissingApiKeyWhenRequired() throws IOException {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section =
                new ProviderSection(configurationManager, new MockEnvironment(), providers(openai));
        section.providerField().setValue(openai);
        section.modelField().setValue("gpt-4o");
        section.apiKeyField().setValue("");

        click(section.saveButton());

        assertThat(section.apiKeyField().isInvalid()).isTrue();
        verify(configurationManager, never()).updateProperties(anyMap());
    }

    @Test
    @DisplayName("a provider that does not require a key saves with no API key entered")
    void doesNotRequireApiKeyWhenProviderDoesNotNeedOne() throws IOException {
        FakeProvider ollama = new FakeProvider("ollama", "Ollama", false);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section =
                new ProviderSection(configurationManager, new MockEnvironment(), providers(ollama));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(ollama);
        section.modelField().setValue("llama3.2");
        section.baseUrlField().setValue("http://localhost:11434");

        click(section.saveButton());

        assertThat(section.apiKeyField().isInvalid()).isFalse();
        verify(configurationManager).updateProperties(anyMap());
    }

    @Test
    @DisplayName("saves exactly the property keys S2_ProviderStep/S3_CredentialsStep use")
    void savesExpectedPropertyKeys() throws IOException {
        FakeProvider ollama = new FakeProvider("ollama", "Ollama", false);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section =
                new ProviderSection(configurationManager, new MockEnvironment(), providers(ollama));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(ollama);
        section.modelField().setValue("llama3.2");
        section.baseUrlField().setValue("http://localhost:11434");

        click(section.saveButton());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertThat(saved).containsEntry(PROVIDER_KEY, "ollama");
        assertThat(saved).containsEntry(MODEL_KEY, "llama3.2");
        assertThat(saved).containsEntry(BASE_URL_KEY, "http://localhost:11434");
        // No API key was entered and the provider does not require one, so the key is left out
        // entirely rather than written as an empty string - matching S2_ProviderStep.saveConfiguration,
        // which only writes a property when StringUtils.hasText(value) is true.
        assertThat(saved).doesNotContainKey(API_KEY_KEY);
    }

    @Test
    @DisplayName("a blank API key on save leaves an already-stored key untouched, not overwritten with blank")
    void blankApiKeyOnSaveDoesNotOverwriteStoredKey() throws IOException {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MODEL_KEY, "gpt-4o")
                .withProperty(API_KEY_KEY, "sk-already-stored");
        ProviderSection section = new ProviderSection(configurationManager, environment, providers(openai));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(openai);
        section.modelField().setValue("gpt-4o");
        // apiKeyField is left blank, exactly as prefill leaves it - the reader is not retyping a key
        // they cannot see, they are just changing something else.

        click(section.saveButton());

        assertThat(section.apiKeyField().isInvalid()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        // Not written at all - a blank value in the written map would overwrite the stored key with
        // an empty string, which is exactly the silent data loss this test guards against.
        assertThat(captor.getValue()).doesNotContainKey(API_KEY_KEY);
    }

    @Test
    @DisplayName("typing a new API key overwrites whatever was stored")
    void typedApiKeyOverwritesStoredKey() throws IOException {
        FakeProvider openai = new FakeProvider("openai", "OpenAI", true);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MODEL_KEY, "gpt-4o")
                .withProperty(API_KEY_KEY, "sk-old");
        ProviderSection section = new ProviderSection(configurationManager, environment, providers(openai));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(openai);
        section.modelField().setValue("gpt-4o");
        section.apiKeyField().setValue("sk-new");

        click(section.saveButton());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        assertThat(captor.getValue()).containsEntry(API_KEY_KEY, "sk-new");
    }

    @Test
    @DisplayName("clearing an explicitly emptied base URL removes the stored override")
    void clearingBaseUrlRemovesStoredOverride() throws IOException {
        FakeProvider ollama = new FakeProvider("ollama", "Ollama", false);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(MODEL_KEY, "llama3.2")
                .withProperty(BASE_URL_KEY, "http://old-provider.example");
        ProviderSection section = new ProviderSection(configurationManager, environment, providers(ollama));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(ollama);
        section.modelField().setValue("llama3.2");
        section.baseUrlField().setValue("");

        click(section.saveButton());

        verify(configurationManager).removeProperty(BASE_URL_KEY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(configurationManager).updateProperties(captor.capture());
        assertThat(captor.getValue()).doesNotContainKey(BASE_URL_KEY);
    }

    @Test
    @DisplayName("a non-blank base URL is saved without ever calling removeProperty")
    void nonBlankBaseUrlDoesNotClear() throws IOException {
        FakeProvider ollama = new FakeProvider("ollama", "Ollama", false);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section = new ProviderSection(
                configurationManager, new MockEnvironment().withProperty(MODEL_KEY, "llama3.2"),
                providers(ollama));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(ollama);
        section.modelField().setValue("llama3.2");
        section.baseUrlField().setValue("http://localhost:11434");

        click(section.saveButton());

        verify(configurationManager, never()).removeProperty(anyString());
    }

    @Test
    @DisplayName("the clear-key button removes the stored key without going through save's validation")
    void clearApiKeyButtonRemovesStoredKey() throws IOException {
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        MockEnvironment environment = new MockEnvironment().withProperty(API_KEY_KEY, "sk-stored");
        ProviderSection section = new ProviderSection(
                configurationManager, environment, providers(new FakeProvider("openai", "OpenAI", true)));
        UI ui = UiHarness.ui();
        ui.add(section);
        assertThat(section.clearApiKeyButton().isVisible()).isTrue();

        click(section.clearApiKeyButton());

        verify(configurationManager).removeProperty(API_KEY_KEY);
        verify(configurationManager, never()).updateProperties(anyMap());
        assertThat(section.clearApiKeyButton().isVisible()).isFalse();
    }

    @Test
    @DisplayName("a successful save shows the restart notice and schedules a reload")
    void savingSchedulesRestartReload() throws IOException {
        FakeProvider ollama = new FakeProvider("ollama", "Ollama", false);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        ProviderSection section =
                new ProviderSection(configurationManager, new MockEnvironment(), providers(ollama));
        UI ui = UiHarness.ui();
        ui.add(section);
        section.providerField().setValue(ollama);
        section.modelField().setValue("llama3.2");

        click(section.saveButton());

        // Vaadin's own components queue their own executeJs calls as part of ordinary attach/sync
        // machinery, so the reload's invocation is picked out by content rather than assumed to be
        // the only entry in the queue.
        List<PendingJavaScriptInvocation> reloadInvocations = UiHarness.drainPendingJavaScript(ui).stream()
                .filter(invocation -> invocation.getInvocation().getExpression()
                        .contains("setTimeout(() => location.reload(), $0)"))
                .toList();
        assertThat(reloadInvocations).hasSize(1);
        // The delay RestartNotice scheduled the reload with - asserted literally rather than via a
        // shared constant because RestartNotice keeps RELOAD_DELAY_MILLIS private on purpose (see its
        // class note); a test outside that class can only observe the value it actually sent the
        // browser.
        assertThat(reloadInvocations.getFirst().getInvocation().getParameters().getFirst()).isEqualTo(4000);
    }

    private static AgentOnboardingProviders providers(AgentOnboardingProvider... items) {
        SequencedSet<AgentOnboardingProvider> set = new LinkedHashSet<>(List.of(items));
        return new AgentOnboardingProviders(set);
    }

    private static void click(NativeButton button) {
        ComponentUtil.fireEvent(button,
                new ClickEvent<>(button, true, 0, 0, 0, 0, 1, 0, false, false, false, false));
    }

    /** A minimal {@link AgentOnboardingProvider} double, since the real implementations live in
     *  {@code app} and this module cannot depend on it (see the module boundary in {@code
     *  ModuleBoundaryArchTest}). */
    private static final class FakeProvider implements AgentOnboardingProvider {
        private final String id;
        private final String label;
        private final boolean requiresApiKey;

        FakeProvider(String id, String label, boolean requiresApiKey) {
            this.id = id;
            this.label = label;
            this.requiresApiKey = requiresApiKey;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public String slogan() {
            return label;
        }

        @Override
        public boolean requiresApiKey() {
            return requiresApiKey;
        }

        @Override
        public String defaultModel() {
            return "";
        }
    }
}
