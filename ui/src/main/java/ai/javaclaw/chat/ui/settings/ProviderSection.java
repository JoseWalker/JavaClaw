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
 *
 * <p><b>The stored API key is never sent to the browser.</b> {@code S3_CredentialsStep} prefills its
 * own key field from {@code Environment} the same way this page used to, but a wizard step reached
 * once during onboarding and a page reachable by anyone who can open {@code /settings} are different
 * exposures - here, a stored secret would be read back out through the password field's plaintext-
 * reveal toggle on every single visit. {@link #apiKeyField} is therefore always blank on load; see
 * {@link #save()} for what a blank field means on submit, and {@link #clearApiKey()} for the only way
 * left to remove a stored key once its value is no longer shown back.
 */
class ProviderSection extends Div {

    /**
     * The property prefix every field here reads from and writes to - the same "default" named
     * provider the onboarding wizard configures, per {@link LlmProviderProperties#DEFAULT_PROVIDER_NAME}.
     */
    private static final String PROPERTY_BASE =
            "agent.llm.providers." + LlmProviderProperties.DEFAULT_PROVIDER_NAME;

    /**
     * Where the provider id lived before named providers existed - what {@code S2_ProviderStep}
     * read/wrote until upstream #70 introduced {@link #PROPERTY_BASE}. Read-only, and only consulted
     * when {@link #PROPERTY_BASE}{@code .provider} is blank: a reader who configured JavaClaw before
     * that migration has a real, working setup under this key, and a page that cannot see it looks
     * exactly like a page that reset their configuration to nothing - the two are indistinguishable to
     * someone who has never read this class. A save here always writes {@link #PROPERTY_BASE} only
     * (see {@link #save()}), so every save is also, incidentally, one reader's migration off this key.
     */
    private static final String LEGACY_PROVIDER_KEY = "spring.ai.model.chat";

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
     * The provider credential. Unlike every other field on this page, this one is never prefilled
     * from what is stored - see the class note on why sending a secret back to the browser on every
     * visit is not an acceptable cost of the same always-current-configuration design the other
     * fields use. A blank value on save means "leave the stored key alone", not "there is no key";
     * see {@link #save()}.
     */
    private final PasswordField apiKeyField = new PasswordField("API key");

    /**
     * Whether a non-blank API key is currently stored under {@link #PROPERTY_BASE}, set once in
     * {@link #prefill} and kept in step with every save or clear this section performs afterwards.
     *
     * <p>This is the only trace of the stored key this section keeps in memory - never the value
     * itself - and it exists so {@link #save()} can tell "the reader left this blank because they
     * don't want to change it" apart from "the reader left this blank and there has never been a
     * key", which needs a different validation outcome for a provider that requires one.
     */
    private boolean hasStoredApiKey;

    /** The control that runs {@link #save()}; kept as a field so tests can fire it directly. */
    private final NativeButton saveButton = new NativeButton("Save provider settings", event -> save());

    /**
     * The control that runs {@link #clearApiKey()}; kept as a field so tests can fire it directly.
     *
     * <p>Visible only while {@link #hasStoredApiKey} is true - there is nothing to clear otherwise,
     * and showing it regardless would invite a reader to click it looking for confirmation that no
     * key is stored, which it does not provide.
     */
    private final NativeButton clearApiKeyButton = new NativeButton("Clear stored API key", event -> clearApiKey());

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
        clearApiKeyButton.addClassName("jc-settings__clear-api-key");

        // Grouped so the stylesheet can lay the four fields out as a responsive grid, and the two
        // buttons as their own row, without reaching past this class into a flat list of siblings.
        Div fields = new Div(providerField, modelField, baseUrlField, apiKeyField);
        fields.addClassName("jc-settings__fields");

        Div actions = new Div(clearApiKeyButton, saveButton);
        actions.addClassName("jc-settings__actions");

        add(new H2("Provider & credentials"),
                new Paragraph("The default model JavaClaw answers with, and the credentials it uses to reach it."),
                fields, actions);
    }

    /**
     * Reads today's configuration into the fields, the same properties {@code
     * S3_CredentialsStep.prepareModel} reads for its prefill - except {@link #apiKeyField}, which
     * stays blank; see the class note on why.
     *
     * <p>Falls back to {@link #LEGACY_PROVIDER_KEY} and its matching pre-#70 model/API-key keys
     * whenever the current-format keys are blank, so a reader who configured JavaClaw before that
     * migration sees their real setup here instead of an apparently-reset page - see {@link
     * #LEGACY_PROVIDER_KEY}. The fallback is read-only and per-field: a reader with a new-format
     * provider but no new-format model (unlikely, but not impossible after a partial manual edit)
     * still gets the legacy model value rather than a blank one.
     *
     * @param environment application configuration, read once
     */
    private void prefill(Environment environment) {
        String providerId = environment.getProperty(PROPERTY_BASE + ".provider", "");
        if (providerId.isBlank()) {
            providerId = environment.getProperty(LEGACY_PROVIDER_KEY, "");
        }
        agentOnboardingProviders.findById(providerId).ifPresent(providerField::setValue);

        String model = environment.getProperty(PROPERTY_BASE + ".model", "");
        String legacyApiKey = "";
        if (!providerId.isBlank()) {
            String legacyBase = "spring.ai." + providerId;
            if (model.isBlank()) {
                model = environment.getProperty(legacyBase + ".chat.options.model", "");
            }
            legacyApiKey = environment.getProperty(legacyBase + ".api-key", "");
        }
        modelField.setValue(model);
        baseUrlField.setValue(environment.getProperty(PROPERTY_BASE + ".base-url", ""));

        hasStoredApiKey = StringUtils.hasText(environment.getProperty(PROPERTY_BASE + ".api-key", ""))
                || StringUtils.hasText(legacyApiKey);
        apiKeyField.setPlaceholder(
                hasStoredApiKey ? "Leave blank to keep the current key" : "Enter an API key");
        clearApiKeyButton.setVisible(hasStoredApiKey);
    }

    /**
     * Validates every field in the same order and with the same messages {@code S2_ProviderStep} and
     * {@code S3_CredentialsStep} use, then writes the result and hands off to {@link RestartNotice}.
     *
     * <p>Stops at the first failing field rather than collecting all of them, matching the wizard's
     * own one-error-at-a-time behaviour - a reader fixing one problem at a time is not shown stale
     * errors for fields they have not touched yet.
     *
     * <p>{@link #apiKeyField} is treated differently from every other field here: since it is never
     * prefilled (see the class note), a blank value does not mean "no key" the way a blank {@link
     * #modelField} does. It is written only when non-blank - see {@link #hasStoredApiKey} for how a
     * provider that requires a key still validates correctly against a key that is already stored but
     * not currently visible. {@link #baseUrlField} is the opposite case: it *is* prefilled, so an
     * explicitly emptied value is a request to clear the stored override, not a value to leave alone.
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
        if (provider.requiresApiKey() && apiKey.isBlank() && !hasStoredApiKey) {
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
            if (!StringUtils.hasText(baseUrl)) {
                // The field was left blank on purpose - S2_ProviderStep's "skip when blank"
                // behaviour is right for a wizard step that never had a value to begin with, but
                // here a blank field can only be reached by clearing one that was prefilled, so
                // skipping would leave the stale override in place forever. removeProperty is a
                // no-op when nothing was stored either.
                configurationManager.removeProperty(PROPERTY_BASE + ".base-url");
            }
        } catch (IOException e) {
            // Surfaced as a notification rather than thrown: a write failure here is a disk or
            // permissions problem the reader can act on, not a reason to crash a page they were
            // already using successfully a moment ago.
            Notification.show("Could not save provider settings: " + e.getMessage());
            return;
        }
        if (StringUtils.hasText(apiKey)) {
            hasStoredApiKey = true;
            clearApiKeyButton.setVisible(true);
        }
        RestartNotice.showAndScheduleReload(this);
    }

    /**
     * Removes the stored API key outright.
     *
     * <p>The only way left to intentionally remove a stored key once {@link #save()} stopped treating
     * a blank {@link #apiKeyField} as "clear it" - see the class note on why that field is never
     * prefilled, which is exactly what makes a plain empty-and-save ambiguous between "I mean to
     * clear this" and "I just didn't retype it". An explicit action carries no such ambiguity, the
     * same reasoning {@link McpServersSection#removeServer} applies to removing a configured server.
     *
     * <p>Does not re-validate whether the provider still requires a key afterwards; the reader who
     * clicked this button made that call themselves, the same way clearing {@link #baseUrlField} is
     * accepted without asking whether the provider still needs a base URL override.
     */
    private void clearApiKey() {
        try {
            configurationManager.removeProperty(PROPERTY_BASE + ".api-key");
        } catch (IOException e) {
            Notification.show("Could not clear the API key: " + e.getMessage());
            return;
        }
        hasStoredApiKey = false;
        clearApiKeyButton.setVisible(false);
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

    /**
     * Exposes the clear-API-key control for tests.
     *
     * @return the clear-API-key control, for tests that need to fire it without a browser
     */
    NativeButton clearApiKeyButton() {
        return clearApiKeyButton;
    }
}
