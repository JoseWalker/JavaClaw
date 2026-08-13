package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beans backing the Vaadin UI.
 */
@Configuration
public class ChatUiConfiguration {

    /**
     * Carries agent responses off the Vaadin UI thread.
     *
     * <p>Virtual threads suit this exactly: each task spends nearly all its time blocked on the
     * model, and there is one task per in-flight response rather than a bounded pool to size. The
     * executor is destroyed with the context so a restart does not leak threads.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatResponseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Applies a theme chosen earlier in the session to the bootstrap page itself.
     *
     * <p>Vaadin picks up {@link VaadinServiceInitListener} beans automatically, so registering it
     * here is all that is needed to make the first paint use the right colour scheme.
     */
    @Bean
    public VaadinServiceInitListener themeBootstrapListener() {
        return new ThemeBootstrapListener();
    }

    /**
     * Declares the language of the served page, which Vaadin would otherwise infer.
     *
     * <p>A separate bean rather than another job for the theme listener: the two write to the same
     * document and share nothing else, and a class named for the theme quietly deciding the
     * document's language is how a reader stops trusting the names.
     */
    @Bean
    public VaadinServiceInitListener documentLanguageListener() {
        return new DocumentLanguage();
    }
}
