package ai.javaclaw.chat.ui;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The shape a conversation id is allowed to have, and where new ones come from.
 *
 * <p>An id looks like a label and is not one. The chat store turns it into a file name, and the
 * sidebar hands one straight back to the agent every time a row is clicked - so an id is a value
 * that crosses two boundaries, and both of them are the kind that punish a string nobody checked.
 * This class is where that check lives, expressed as an allow-list: an id is either one of the
 * shapes we already know how to store, or it is refused. Sanitising is deliberately not offered.
 * Repairing an id produces a second id that addresses a different conversation, and a reader who
 * clicks one row and is shown another has been lied to more quietly than by an error.
 *
 * <p>The pattern is drawn from the ids the application already produces - {@code web},
 * {@code telegram-123456789} - and is narrow enough that nothing matching it can name a directory,
 * a parent, or a file extension.
 */
final class ConversationIds {

    /**
     * The channel this UI's conversations belong to, and the id the htmx chat still uses on its own.
     *
     * <p>The two interfaces no longer share one thread - this one opens a new chat and lists every
     * conversation, the classic chat's included, so the reader can walk into it rather than being
     * dropped into it. Every chat started here is prefixed with this name, which is what files them
     * all under one heading once a second channel exists to be told apart from.
     */
    static final String LIVE = "web";

    /**
     * Lower case, digits, underscore and dash, starting on an alphanumeric. No dot, so no extension
     * and no {@code ..}; no slash, so no directory. Capped well under any filesystem's name limit.
     */
    private static final Pattern SHAPE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Long enough that two chats started in the same second cannot collide. */
    private static final int SUFFIX_BYTES = 6;

    /**
     * Whether this value may be treated as a conversation id at all.
     *
     * @param id candidate id, possibly null
     * @return true only when the value matches the allow-listed shape
     */
    static boolean isWellFormed(String id) {
        return id != null && SHAPE.matcher(id).matches();
    }

    /**
     * Mints an id for a chat the reader has just started.
     *
     * <p>Random rather than sequential: the store is a flat folder shared with every other channel,
     * and a counter would have to be read from it before it could be incremented - a read that can
     * race, and a number that says how many chats exist to anyone who sees a URL.
     *
     * @return a fresh id in the web channel, always well formed
     */
    static String newConversationId() {
        byte[] bytes = new byte[SUFFIX_BYTES];
        RANDOM.nextBytes(bytes);
        return LIVE + "-" + HexFormat.of().formatHex(bytes);
    }

    private ConversationIds() {
    }
}
