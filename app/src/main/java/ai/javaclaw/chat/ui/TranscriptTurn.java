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
 * @param id      minted by the store, opaque to the view, stable for as long as the turn exists
 * @param role    who said it
 * @param text    what was said, as it was recorded - markdown for the assistant, plain for the
 *                reader, and this application's own notice for a turn that failed
 * @param outcome whether this turn is something that was said or something that went wrong
 */
public record TranscriptTurn(String id, ConversationMessage.Role role, String text, Outcome outcome) {

    /**
     * Whether a recorded turn holds an answer or a failure.
     *
     * <h2>Why this is on the turn rather than inferred from its text</h2>
     *
     * <p>A failed turn used to be recorded as nothing at all, so reopening a conversation showed
     * the question alone - a question that appears never to have been asked, sitting above the next
     * one. Recording it as an ordinary assistant turn would be worse: the transcript is what a
     * rewind writes back into the model's history, and the model would then be carrying this
     * application's apology as something it had said.
     *
     * <p>So the outcome travels with the turn, and every consumer decides for itself. The view
     * draws a failure as a failure; {@code StoredTranscripts} leaves it out of the model's history.
     * Reading it back out of the text would be a second implementation of what a failure is, and
     * one that a model quoting the notice could fool.
     */
    public enum Outcome {

        /** Something the reader or the assistant actually said. */
        SPOKEN,

        /**
         * A turn that never produced an answer. Its text is one of {@link FailureNotice}'s
         * sentences - never anything a provider wrote.
         */
        FAILED
    }

    /**
     * Refuses a turn that is missing any of the four things a turn is.
     *
     * <p>Every one of them is dereferenced by something that draws or files the turn, and a null
     * arriving here would surface several frames away from whatever failed to supply it.
     */
    public TranscriptTurn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * A turn that was spoken, which is all but one of them.
     *
     * <p>Here so that the ordinary case reads as what it is and so that adding the outcome did not
     * put a {@code SPOKEN} at every call site that has nothing to say about it.
     *
     * @param id   minted by the store
     * @param role who said it
     * @param text what was said
     */
    public TranscriptTurn(String id, ConversationMessage.Role role, String text) {
        this(id, role, text, Outcome.SPOKEN);
    }

    /**
     * Whether this turn is a failure rather than something that was said.
     *
     * @return true when the transcript recorded a turn that never produced an answer
     */
    public boolean failed() {
        return outcome == Outcome.FAILED;
    }
}
