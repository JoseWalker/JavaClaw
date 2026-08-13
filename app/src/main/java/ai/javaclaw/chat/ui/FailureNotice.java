package ai.javaclaw.chat.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a reader is told when a turn fails, as a closed set of sentences this application wrote.
 *
 * <h2>Why the provider's own message never reaches the page</h2>
 *
 * <p>It used to. A blind audit with the model refusing measured a turn whose entire text was the
 * upstream string - "429 Too Many Requests from POST http://localhost:11434/api/chat" - so the
 * transcript printed the inference endpoint, scheme, host, port and path included, to whoever was
 * looking at the screen. Nothing in this application had decided to publish that; it was published
 * because a string arrived and was drawn.
 *
 * <p>The fix is an allow-list rather than a scrub, and the difference matters. Stripping URLs out
 * of a message is a blacklist, and a blacklist has to be right about every shape of secret every
 * provider will ever put in an error - a file path, an API route, a key prefix, the name of a model
 * nobody is meant to know is configured. So nothing from upstream is repeated at all: the message
 * is classified into one of the constants here and the constant's own sentence is what the reader
 * sees. An unrecognised message classifies as {@link #UNKNOWN}, which promises nothing.
 *
 * <p>The full message is still logged where it belongs - {@code DefaultAgent} logs the exception
 * with its stack trace - so nothing diagnostic is lost, only relocated to the one place that is not
 * a web page.
 *
 * <h2>Why an enum and not a String</h2>
 *
 * <p>Because it is the type that makes the leak unrepresentable. {@code MessageTurn.fail} takes one
 * of these, so there is no signature anywhere in the transcript that a raw provider string fits
 * into, and a future caller cannot reintroduce the defect by forgetting to sanitise.
 */
enum FailureNotice {

    /**
     * The provider is throttling. Worth saying separately from every other failure, because it is
     * the one where waiting a moment and asking again is genuinely the right thing to do.
     */
    RATE_LIMITED("The model provider is taking no more requests just now. Ask again in a moment."),

    /**
     * Authentication or authorisation was refused. Named without saying which credential, which
     * provider, or what it was called: none of that helps the reader and all of it is a hint.
     */
    REFUSED_CREDENTIALS("The model provider refused this application's credentials. "
            + "Ask again once the configuration is fixed."),

    /** The configured model is not one the provider has, which is a configuration mistake. */
    NO_SUCH_MODEL("The configured model is not one the provider offers. "
            + "Ask again once the configuration is fixed."),

    /** The provider was reached and took too long, which is usually worth simply retrying. */
    TIMED_OUT("The model took too long to answer. Ask again."),

    /**
     * Nothing answered at the other end. Deliberately does not say where "the other end" is: on a
     * local provider the address is the reader's own machine, and on a hosted one it is not theirs
     * to know.
     */
    UNREACHABLE("JavaClaw could not reach the model. Ask again once it is running."),

    /** The provider answered, with a failure of its own. */
    PROVIDER_BROKE("The model provider could not answer this one. Ask again."),

    /**
     * Everything else, and the value every unrecognised message becomes. It claims no cause,
     * because a cause this application guessed at is a cause it would be stating on no evidence.
     */
    UNKNOWN("JavaClaw could not get an answer. Ask again.");

    /**
     * How much of an upstream message is examined.
     *
     * <p>The input is an exception message from a provider library and has no length limit worth
     * relying on - a deserialisation failure can carry the whole response body. Everything this
     * class looks for is in the opening words, and a regex is not run over an unbounded string that
     * arrived from outside.
     */
    private static final int MAX_EXAMINED = 500;

    /**
     * A status code at the front of the message, which is where every provider library this
     * application talks to puts one: "429 Too Many Requests from POST ...".
     */
    private static final Pattern LEADING_STATUS =
            Pattern.compile("^\\s*(?:HTTP/\\d(?:\\.\\d)?\\s+)?(\\d{3})\\b");

    /** A status code that names itself, for the libraries that write it out in words instead. */
    private static final Pattern LABELLED_STATUS =
            Pattern.compile("(?i)\\bstatus(?:\\s+code)?\\s*[:=]?\\s*(\\d{3})\\b");

    /**
     * Words that mean nothing answered. Checked before the timeout words, because "connect timed
     * out" is a connection that was never made and the reader's next move is to check the model is
     * running rather than to ask again.
     */
    private static final String[] UNREACHABLE_WORDS = {
            "connection refused", "connect timed out", "unknownhost", "unknown host",
            "no route to host", "connection reset", "failed to connect", "network is unreachable"};

    /** Words that mean the provider was reached and did not finish in time. */
    private static final String[] TIMEOUT_WORDS = {"timed out", "timeout"};

    private final String sentence;

    FailureNotice(String sentence) {
        this.sentence = sentence;
    }

    /**
     * What the reader is shown, and what the transcript stores.
     *
     * @return one complete sentence, never null and never blank
     */
    String sentence() {
        return sentence;
    }

    /**
     * Classifies whatever went wrong, without repeating any of it.
     *
     * <p>Deny by default in the strict sense: this returns {@link #UNKNOWN} unless the message
     * matches a shape that is known to mean something specific, and no branch of it ever copies a
     * character of the input into the result.
     *
     * <p>Also the way a stored failure is read back: a transcript holds the sentence, and a
     * sentence this class wrote maps back to the constant that wrote it. A transcript that has been
     * edited by hand to say something else maps to {@link #UNKNOWN} rather than being shown as
     * though this application had said it.
     *
     * @param upstream the provider's message, possibly null, blank, enormous or hostile
     * @return the notice to show; never null
     */
    static FailureNotice of(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            return UNKNOWN;
        }
        String bounded = upstream.length() > MAX_EXAMINED
                ? upstream.substring(0, MAX_EXAMINED)
                : upstream;
        for (FailureNotice known : values()) {
            if (known.sentence.equals(bounded.strip())) {
                return known;
            }
        }
        FailureNotice byStatus = fromStatus(bounded);
        return byStatus != null ? byStatus : fromWords(bounded.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads an HTTP status out of the message, where one is unambiguously being reported.
     *
     * <p>Only a leading or self-labelled code counts. Any three digits anywhere would read "Model
     * llama 405 could not be loaded" as a status and tell the reader a cause this application had
     * invented for them, which is a smaller lie than printing the endpoint and still a lie.
     *
     * @param message the bounded upstream message
     * @return the notice that status means, or null when the message reports no status
     */
    private static FailureNotice fromStatus(String message) {
        Matcher leading = LEADING_STATUS.matcher(message);
        Matcher labelled = LABELLED_STATUS.matcher(message);
        int status;
        if (leading.find()) {
            status = Integer.parseInt(leading.group(1));
        } else if (labelled.find()) {
            status = Integer.parseInt(labelled.group(1));
        } else {
            return null;
        }
        return switch (status) {
            case 401, 403 -> REFUSED_CREDENTIALS;
            case 404 -> NO_SUCH_MODEL;
            case 408, 504 -> TIMED_OUT;
            case 429 -> RATE_LIMITED;
            default -> status >= 400 && status < 600 ? PROVIDER_BROKE : UNKNOWN;
        };
    }

    /**
     * Reads a transport failure out of the message, for the failures that never got a status
     * because nothing at the other end ever answered.
     *
     * @param message the bounded upstream message, already lowercased
     * @return the notice those words mean, or {@link #UNKNOWN} when they mean nothing in particular
     */
    private static FailureNotice fromWords(String message) {
        for (String word : UNREACHABLE_WORDS) {
            if (message.contains(word)) {
                return UNREACHABLE;
            }
        }
        for (String word : TIMEOUT_WORDS) {
            if (message.contains(word)) {
                return TIMED_OUT;
            }
        }
        return UNKNOWN;
    }
}
