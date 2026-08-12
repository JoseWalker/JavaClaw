package ai.javaclaw.chat.ui;

import com.vaadin.flow.shared.Registration;

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

    /**
     * The turns of one conversation, oldest first, so a chosen row can actually be opened.
     *
     * <p>Without this the column would be a list of things that cannot be reached: a sidebar that
     * names conversations but leaves the reader looking at a different one is worse than no sidebar,
     * because the title above the transcript now disagrees with the transcript.
     *
     * <p>Implementations must refuse an id they do not recognise rather than passing it on, and must
     * not throw for a conversation that has never been stored - a chat started a moment ago is the
     * ordinary case, not an error.
     *
     * @param id conversation id, possibly null, unknown or ill formed
     * @return an immutable list, possibly empty, never null
     */
    List<ConversationMessage> transcript(String id);

    /**
     * Asks to be told when what {@link #conversations()} would answer has changed underneath the UI.
     *
     * <p>The column reads the directory once and draws it. Everything the directory learns after
     * that - a conversation acquiring a real name, a store that gained a row - would otherwise only
     * reach the reader on their next click, which is the point at which they have already given up
     * on the list. This is how a directory says "ask me again".
     *
     * <p>Defaulted to a registration that does nothing, because a directory backed by a fixed list
     * genuinely never changes and should not have to say so. Listeners may be called on any thread;
     * a UI listener is responsible for getting back onto its own.
     *
     * @param listener run after a change, never with any argument - the caller re-reads
     * @return the registration to remove the listener with; a component must remove it on detach
     */
    default Registration onChanged(Runnable listener) {
        return () -> {
        };
    }
}
