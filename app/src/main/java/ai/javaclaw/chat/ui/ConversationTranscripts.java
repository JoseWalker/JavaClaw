package ai.javaclaw.chat.ui;

import java.util.List;
import java.util.Optional;

/**
 * The record of what was actually said, as distinct from what the model still needs to hear.
 *
 * <h2>Why this is not the chat store</h2>
 *
 * <p>The agent's memory is a sliding window: {@code MessageWindowChatMemory} keeps the last twenty
 * messages and hands them to the model, and the repository underneath it is written back with
 * exactly what the window kept. So the twenty-first exchange does not merely fall out of the
 * prompt - it is deleted from disk, and the conversation the reader had is gone. That is a defect
 * in the layer below and it is not this UI's to fix; what this UI can do is stop treating a context
 * window as a transcript. A window is sized for a model's attention and a transcript is sized for a
 * person's memory, and the two numbers have never been the same.
 *
 * <p>Hence a second, UI-owned record that keeps every turn, and a port rather than a class so the
 * view is not written against a folder of files. The view asks for turns and is given turns; where
 * they live, and what has to be written to keep the model's context agreeing with them, is the
 * adapter's problem.
 *
 * <p>Implementations must not throw for a conversation that has never been stored, an id of the
 * wrong shape, or a store that cannot be reached. A chat window is not a database client: the
 * failure mode here is a transcript that is shorter than it should be, never a page that will not
 * render.
 */
public interface ConversationTranscripts {

    /**
     * Every turn of one conversation, oldest first.
     *
     * @param id conversation id, possibly null, unknown or ill formed
     * @return an immutable list, possibly empty, never null
     */
    List<TranscriptTurn> turns(String id);

    /**
     * Records a turn that has just happened.
     *
     * <p>Returning the turn rather than nothing is what lets the view act on it afterwards: the id
     * minted here is the handle for regenerating and editing. An empty result therefore means more
     * than "not saved" - it means this turn cannot be acted on, and the view must not offer
     * controls that would address a turn the store has no record of.
     *
     * @param id   conversation id, possibly null, unknown or ill formed
     * @param role who said it
     * @param text what was said; blank text is not a turn and is refused
     * @return the recorded turn, or empty when nothing was recorded
     */
    Optional<TranscriptTurn> append(String id, ConversationMessage.Role role, String text);

    /**
     * Takes the conversation back to just before a turn, dropping that turn and everything after.
     *
     * <p>This is the whole of what regenerating and editing need, and it deliberately does both
     * halves at once. Dropping the turns from the transcript alone would leave the model still
     * holding the answer the reader just rejected, and it would carry that answer into the next
     * reply - so the context the agent reads is rewritten in the same operation. Either both move
     * or neither does; a transcript and a context window that disagree about what was said produce
     * a reply nobody can account for.
     *
     * @param id     conversation id, possibly null, unknown or ill formed
     * @param turnId the turn to rewind to, which is itself dropped
     * @return the surviving turns, oldest first, or empty when the conversation was not rewound at
     *         all - an unknown turn, an id of the wrong shape, or a store that refused the write
     */
    Optional<List<TranscriptTurn>> rewindTo(String id, String turnId);
}
