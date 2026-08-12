package ai.javaclaw.chat.ui;

import java.util.Objects;

/**
 * One turn of the transcript this UI owns, carrying the one thing {@link ConversationMessage}
 * deliberately does not: an identity.
 *
 * <p>Nothing in the transcript could be acted on without it. "Answer this again" and "I meant to
 * ask something else" are both instructions about a particular turn, and neither of the two obvious
 * substitutes survives being used: a position in a list is invalidated by the very operations that
 * need to name a turn, and the text is not unique, because a reader may ask the same question
 * twice and regularly does when the first answer was wrong.
 *
 * @param id    minted by the store, opaque to the view, stable for as long as the turn exists
 * @param role  who said it
 * @param text  what was said, as it was recorded - markdown for the assistant, plain for the reader
 */
public record TranscriptTurn(String id, ConversationMessage.Role role, String text) {

    public TranscriptTurn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }
}
