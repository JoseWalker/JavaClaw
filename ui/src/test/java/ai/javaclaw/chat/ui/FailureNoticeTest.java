package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reader is told when a turn fails, and what they are never told.
 *
 * <h2>The defect this file exists for</h2>
 *
 * <p>The provider's own message went straight into the transcript. A blind audit with the model
 * refusing measured a turn whose entire text was "JavaClaw\n429 Too Many Requests from POST
 * http://localhost:11434/api/chat" - the inference endpoint, scheme, host, port and path, printed
 * on the page by an application that had no idea it was quoting anything. The next provider's
 * message might carry an API path, a workspace path, a key prefix or the name of a model nobody is
 * meant to know is configured, and the code had no opinion about any of it.
 *
 * <h2>Why an allow-list rather than a scrub</h2>
 *
 * <p>Removing the URLs from a message is a blacklist, and a blacklist has to be right about every
 * shape of secret a provider will ever put in a string. So nothing from upstream is repeated at
 * all: the message is classified into one of a closed set of sentences this application wrote, and
 * an unrecognised message classifies as the one that promises nothing. That is the property pinned
 * below, and it is pinned as a property rather than for the one string the auditor happened to
 * catch: whatever goes in, what comes out is one of ours.
 */
class FailureNoticeTest {

    /** The string a blind audit measured on screen, kept verbatim so this is the reported case. */
    private static final String MEASURED =
            "429 Too Many Requests from POST http://localhost:11434/api/chat";

    @Test
    @DisplayName("nothing a provider wrote is ever repeated back to the reader")
    void repeatsNothingFromUpstream() {
        List<String> hostile = List.of(
                MEASURED,
                "500 Internal Server Error from POST https://api.example.com/v1/chat/completions",
                "I/O error on POST request for \"http://10.0.0.4:8080/api/chat\": Connection refused",
                "Bearer sk-abcdef0123456789 was rejected",
                "Could not read /Users/someone/.config/javaclaw/application.private.yaml",
                "");

        for (String raw : hostile) {
            String sentence = FailureNotice.of(raw).sentence();
            assertThat(sentence)
                    .as("the notice for %s", raw)
                    .doesNotContain("http", "localhost", "11434", "/api/", "10.0.0.4", "sk-",
                            "Bearer", "/Users/", ".yaml");
            // Every sentence the reader can be shown is one of this application's own.
            assertThat(sentences()).contains(sentence);
        }
    }

    @Test
    @DisplayName("a message with no recognisable cause promises nothing it cannot check")
    void deniesByDefault() {
        assertThat(FailureNotice.of(null)).isEqualTo(FailureNotice.UNKNOWN);
        assertThat(FailureNotice.of("   ")).isEqualTo(FailureNotice.UNKNOWN);
        assertThat(FailureNotice.of("NullPointerException")).isEqualTo(FailureNotice.UNKNOWN);
        // A three-digit number that is not a status is not read as one: the reader would be told a
        // cause this application had invented for them.
        assertThat(FailureNotice.of("Model llama 405 could not be loaded")).isEqualTo(FailureNotice.UNKNOWN);
    }

    @Test
    @DisplayName("the causes a reader can act on differently are told apart")
    void classifiesTheCausesWorthTellingApart() {
        assertThat(FailureNotice.of(MEASURED)).isEqualTo(FailureNotice.RATE_LIMITED);
        assertThat(FailureNotice.of("401 Unauthorized from POST https://api.example.com/v1/chat"))
                .isEqualTo(FailureNotice.REFUSED_CREDENTIALS);
        assertThat(FailureNotice.of("404 Not Found from POST https://api.example.com/v1/chat"))
                .isEqualTo(FailureNotice.NO_SUCH_MODEL);
        assertThat(FailureNotice.of("503 Service Unavailable from POST https://api.example.com"))
                .isEqualTo(FailureNotice.PROVIDER_BROKE);
        assertThat(FailureNotice.of("I/O error: Connection refused"))
                .isEqualTo(FailureNotice.UNREACHABLE);
        assertThat(FailureNotice.of("java.net.SocketTimeoutException: Read timed out"))
                .isEqualTo(FailureNotice.TIMED_OUT);
    }

    /**
     * The transcript store holds the sentence, so reopening a conversation hands it back here. A
     * notice that did not survive that round trip would come back as UNKNOWN and quietly rewrite
     * what the reader was told the first time.
     */
    @Test
    @DisplayName("a notice read back out of the transcript is the notice that was written")
    void recognisesItsOwnSentences() {
        for (FailureNotice notice : FailureNotice.values()) {
            assertThat(FailureNotice.of(notice.sentence())).isEqualTo(notice);
        }
    }

    @Test
    @DisplayName("every notice tells the reader the turn failed and what to do about it")
    void everyNoticeIsUsable() {
        for (FailureNotice notice : FailureNotice.values()) {
            assertThat(notice.sentence()).isNotBlank().endsWith(".");
            assertThat(notice.sentence()).contains("Ask again");
        }
    }

    private static List<String> sentences() {
        return Arrays.stream(FailureNotice.values()).map(FailureNotice::sentence).toList();
    }
}
