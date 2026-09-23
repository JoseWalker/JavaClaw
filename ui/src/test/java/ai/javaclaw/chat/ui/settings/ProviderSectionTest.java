package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.chat.ui.UiHarness;
import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.NativeButton;
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
    @DisplayName("prefills provider, model, base URL and API key from current configuration")
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
        assertThat(section.apiKeyField().getValue()).isEqualTo("sk-test");
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
