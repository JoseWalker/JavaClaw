package ai.javaclaw.chat.ui;

import java.util.List;
import java.util.Optional;

/**
 * The sidebar's window onto the conversations that exist.
 *
 * <p>Deliberately a port rather than a repository. The chat store is expected to change - today it
 * is a folder of YAML files reached through Spring AI's {@code ChatMemoryRepository}, tomorrow it
 * may be a table with real titles, folders and timestamps - and the layout should not have to change
 * with it. Everything the UI needs is expressed here in the UI's own vocabulary; the adapter is
 * responsible for the translation and for admitting which parts it cannot yet supply.
 *
 * <p>Read-only on purpose. Creating and renaming conversations means writing to the store, which
 * belongs with the view that owns a conversation's messages, not with the chrome around it.
 */
public interface ConversationDirectory {

    /**
     * Every conversation the sidebar should offer, newest first where the store can say.
     *
     * <p>Implementations must not throw for an unreachable or empty store: an empty sidebar is a
     * recoverable state, a failed render is not.
     *
     * @return an immutable list, possibly empty, never null
     */
    List<ConversationSummary> conversations();

    /**
     * Looks a single conversation up by id, for restoring a selection.
     *
     * @param id conversation id, possibly null or unknown
     * @return the matching row, or empty when there is none
     */
    Optional<ConversationSummary> find(String id);
}
