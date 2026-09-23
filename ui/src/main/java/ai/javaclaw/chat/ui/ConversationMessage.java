package ai.javaclaw.chat.ui;

import java.util.Objects;

/**
 * One turn of a stored conversation, in the only two shapes a transcript can show.
 *
 * <p>Deliberately not Spring AI's {@code Message}. The transcript draws exactly two things - what
 * the reader asked and what the assistant answered - and a view that took the store's own message
 * type would have to decide, on every render, what to do with a system prompt or a tool call it was
 * never designed to paint. Narrowing here rather than in the view means the decision is made once,
 * by the adapter that knows what those other kinds are.
 *
 * @param role who said it
 * @param text what was said, as it was stored
 */
public record ConversationMessage(Role role, String text) {

    /** The two speakers a transcript has room for. */
    public enum Role {

        /** The reader. Their text is shown as they wrote it and is never parsed as markup. */
        USER,

        /** The assistant. Its text is markdown, and is rendered as such once the turn is closed. */
        ASSISTANT
    }

    /**
     * Rejects a half-built turn at construction instead of letting it reach the transcript.
     *
     * <p>Both components are dereferenced while painting - the role picks the bubble style, the text
     * is fed to the markdown parser - so a null here would surface as a render failure in the middle
     * of a conversation, with nothing left to say which record was malformed or where it came from.
     * Failing at the boundary keeps the stack trace pointing at the adapter that built it.
     *
     * @throws NullPointerException if either the role or the text is null; an empty text is legal,
     *     because an assistant turn is created empty and filled token by token as the answer streams
     */
    public ConversationMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }
}
