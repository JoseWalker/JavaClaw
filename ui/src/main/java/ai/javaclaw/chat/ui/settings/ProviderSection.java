package ai.javaclaw.chat.ui.settings;

import ai.javaclaw.configuration.ConfigurationManager;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets a configured reader change the default LLM provider, model and credentials without redoing
 * onboarding from {@code /onboarding/welcome}.
 *
 * <p>Reads and writes exactly the {@code agent.llm.providers.default.*} keys {@code
 * S2_ProviderStep} and {@code S3_CredentialsStep} use, with the same validation messages, so a value
 * set here and a value set during onboarding are indistinguishable to the rest of the application.
 * What differs is the shape: the wizard spreads provider choice and credential entry across two
 * session-carried steps that only run once; this is one form, always available, that reads current
 * configuration on every visit instead of session state that resets between requests.
 *
 * <p>A save here writes through {@link ConfigurationManager}, which fires the configuration-changed
 * event that restarts the application - see {@link RestartNotice} for why every successful save ends
 * by calling it rather than just returning.
 */
class ProviderSection extends Div {

    /**
     * The property prefix every field here reads from and writes to - the same "default" named
     * provider the onboarding wizard configures, per {@link LlmProviderProperties#DEFAULT_PROVIDER_NAME}.
     */
    private static final String PROPERTY_BASE =
            "agent.llm.providers." + LlmProviderProperties.DEFAULT_PROVIDER_NAME;

    /** Resolves a provider id to the object the rest of the form validates and saves against. */
    private final AgentOnboardingProviders agentOnboardingProviders;

    /** Where a valid save is written; never called until every field above has passed validation. */
    private final ConfigurationManager configurationManager;

    /** Provider choice, mirroring {@code S2_ProviderStep}'s single required field. */
    private final Select<AgentOnboardingProvider> providerField = new Select<>();

    /** The chat model name, mirroring {@code S3_CredentialsStep}'s required {@code model} field. */
    private final TextField modelField = new TextField("Model");

    /** Optional endpoint override, mirroring {@code S3_CredentialsStep}'s optional {@code baseUrl}. */
    private final TextField baseUrlField = new TextField("Base URL");

    /**
     * The provider credential, masked because it is read back from disk on every visit to this page
     * rather than typed fresh each time - see the class note on this page reading current
     * configuration rather than session state.
     */
    private final PasswordField apiKeyField = new PasswordField("API key");

    /** The control that runs {@link #save()}; kept as a field so tests can fire it directly. */
    private final NativeButton saveButton = new NativeButton("Save provider settings", event -> save());

    /**
     * Builds the section, already prefilled from whatever is configured today.
     *
     * <p>Must run on the Vaadin UI thread, like any component construction added to a live layout.
     *
     * @param configurationManager where a valid save is written
     * @param environment read once, here, for the prefill - not held as a field, because nothing
     *     after construction needs to re-read it
     * @param agentOnboardingProviders the supported provider list, shared with the onboarding wizard
     *     so the two never disagree about which providers exist or what they require
     */
    ProviderSection(ConfigurationManager configurationManager, Environment environment,
                     AgentOnboardingProviders agentOnboardingProviders) {
        this.configurationManager = configurationManager;
        this.agentOnboardingProviders = agentOnboardingProviders;

        addClassName("jc-settings__section");

        providerField.setLabel("Provider");
        providerField.setItems(agentOnboardingProviders.getAll());
        providerField.setItemLabelGenerator(AgentOnboardingProvider::getLabel);

        modelField.setPlaceholder("e.g. gpt-4o, claude-opus-4-6, llama3.2");
        baseUrlField.setPlaceholder("Optional - overrides the provider's default endpoint");

        prefill(environment);

        saveButton.addClassName("jc-settings__save");

        add(new H2("Provider & credentials"),
                new Paragraph("The default model JavaClaw answers with, and the credentials it uses to reach it."),
                providerField, modelField, baseUrlField, apiKeyField, saveButton);
    }

    /**
     * Reads today's configuration into the fields, the same properties {@code
     * S3_CredentialsStep.prepareModel} reads for its prefill.
     *
     * @param environment application configuration, read once
     */
    private void prefill(Environment environment) {
        String providerId = environment.getProperty(PROPERTY_BASE + ".provider", "");
        agentOnboardingProviders.findById(providerId).ifPresent(providerField::setValue);
        modelField.setValue(environment.getProperty(PROPERTY_BASE + ".model", ""));
        baseUrlField.setValue(environment.getProperty(PROPERTY_BASE + ".base-url", ""));
        apiKeyField.setValue(environment.getProperty(PROPERTY_BASE + ".api-key", ""));
    }

    /**
     * Validates every field in the same order and with the same messages {@code S2_ProviderStep} and
     * {@code S3_CredentialsStep} use, then writes the result and hands off to {@link RestartNotice}.
     *
     * <p>Stops at the first failing field rather than collecting all of them, matching the wizard's
     * own one-error-at-a-time behaviour - a reader fixing one problem at a time is not shown stale
     * errors for fields they have not touched yet.
     */
    private void save() {
        clearErrors();

        AgentOnboardingProvider provider = providerField.getValue();
        if (provider == null) {
            providerField.setErrorMessage("Choose one of the supported providers to continue.");
            providerField.setInvalid(true);
            return;
        }

        String model = trim(modelField.getValue());
        if (model.isBlank()) {
            modelField.setErrorMessage("Enter a model to continue.");
            modelField.setInvalid(true);
            return;
        }

        String apiKey = trim(apiKeyField.getValue());
        if (provider.requiresApiKey() && apiKey.isBlank()) {
            apiKeyField.setErrorMessage("Enter an API key to continue.");
            apiKeyField.setInvalid(true);
            return;
        }

        String baseUrl = trim(baseUrlField.getValue());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PROPERTY_BASE + ".provider", provider.getId());
        if (StringUtils.hasText(baseUrl)) {
            props.put(PROPERTY_BASE + ".base-url", baseUrl);
        }
        if (StringUtils.hasText(apiKey)) {
            props.put(PROPERTY_BASE + ".api-key", apiKey);
        }
        props.put(PROPERTY_BASE + ".model", model);

        try {
            configurationManager.updateProperties(props);
        } catch (IOException e) {
            // Surfaced as a notification rather than thrown: a write failure here is a disk or
            // permissions problem the reader can act on, not a reason to crash a page they were
            // already using successfully a moment ago.
            Notification.show("Could not save provider settings: " + e.getMessage());
            return;
        }
        RestartNotice.showAndScheduleReload(this);
    }

    /** Clears every field-level error left over from a previous failed attempt. */
    private void clearErrors() {
        providerField.setInvalid(false);
        modelField.setInvalid(false);
        apiKeyField.setInvalid(false);
    }

    /**
     * Normalises a field's raw value for validation and saving.
     *
     * @param value a field value, possibly null
     * @return the value trimmed, or the empty string when it was null - every caller here treats a
     *     never-typed-into field the same as a field that was typed into and then cleared
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Exposes the provider selector for tests.
     *
     * @return the provider selector, for tests asserting prefill and validation without a browser
     */
    Select<AgentOnboardingProvider> providerField() {
        return providerField;
    }

    /**
     * Exposes the model field for tests.
     *
     * @return the model field, for tests asserting prefill and validation without a browser
     */
    TextField modelField() {
        return modelField;
    }

    /**
     * Exposes the base URL field for tests.
     *
     * @return the base URL field, for tests asserting prefill without a browser
     */
    TextField baseUrlField() {
        return baseUrlField;
    }

    /**
     * Exposes the API key field for tests.
     *
     * @return the API key field, for tests asserting prefill and validation without a browser
     */
    PasswordField apiKeyField() {
        return apiKeyField;
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
