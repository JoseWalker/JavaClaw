package ai.javaclaw.chat.ui;

import com.vaadin.flow.server.VaadinSession;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The half-written questions, kept where a reload cannot reach them.
 *
 * <h2>Why anything is kept at all</h2>
 *
 * <p>An unsent draft used to live in exactly one place - the value of a {@code TextArea} inside a
 * component tree - and a reload destroys the whole of that tree. A blind review typed a sentence into
 * the composer, pressed reload, and came back to an empty field; the product this UI is measured
 * against restored the text verbatim. Switching conversations lost it just as completely and much
 * more often, because the view cleared the composer on every switch and kept no record of what it
 * had cleared. Neither is a small loss: the reader typed it, nothing told them it was disposable, and
 * there is no way to get it back.
 *
 * <h2>Why the session</h2>
 *
 * <p>It is the one thing on this side of the wire that outlives a page load, which is exactly the
 * span the defect covers. A draft is not conversation content - the store records exchanges, and an
 * unsent question is by definition not one - so writing it to disk would put text the reader never
 * sent into a record they can share, and would keep it after the browser that holds it is gone.
 * Reloading is what has to survive; being restored on another machine next week is not.
 *
 * <h2>Why it is keyed by conversation</h2>
 *
 * <p>Because a draft belongs to the thread it was written for. Keyed by anything coarser, a reader
 * who looks something up in another conversation comes back to that conversation's text sitting in
 * their composer under this conversation's title - which is worse than losing their own, because it
 * can be sent by accident.
 *
 * <h2>Boundary</h2>
 *
 * <p>Everything here is attacker-influenced by construction: the key is a conversation id that can
 * arrive from the address bar, and the value is whatever was typed. So the key is checked against the
 * same allow-list the store is protected by rather than trusted, the record is bounded in both the
 * number of conversations and the total characters it will hold, and nothing is ever logged - a draft
 * is the reader's own text and a log is a place it must not end up.
 *
 * <h2>Threading</h2>
 *
 * <p>Session attributes are guarded by the session lock, and every method here must therefore be
 * called on the Vaadin UI thread, which holds it. There is no synchronisation of the map itself
 * because there is no second thread that may legitimately reach it.
 */
final class ComposerDrafts {

    /**
     * Conversations a session will keep a draft for.
     *
     * <p>A ceiling rather than a considered number: a reader with unsent text in more than a handful
     * of threads is not a case worth spending unbounded session memory on, and the one draft that
     * matters - the one they are typing - is the one this can never evict.
     */
    private static final int MAX_CONVERSATIONS = 16;

    /**
     * Characters a session will hold across all its drafts.
     *
     * <p>The second bound, and the one that matters, because sixteen pasted files is a different
     * amount of memory from sixteen sentences. The most recently written draft is exempt: it is
     * already held once by the live composer in the same session, so keeping it costs a reference
     * rather than a copy, and truncating it would silently destroy the reader's own text - which is
     * the defect this class exists to fix, committed by its own safeguard.
     */
    private static final int MAX_CHARACTERS = 128 * 1024;

    /**
     * Not instantiable. Every draft belongs to a {@link com.vaadin.flow.server.VaadinSession}, which
     * is where the state actually lives, so an instance of this class would carry nothing.
     */
    private ComposerDrafts() {
    }

    /**
     * Files what is in the composer under the conversation it was written for.
     *
     * <p>A blank composer is not a draft and is recorded as an absence rather than as an empty
     * string. That is what makes sending a question forget it: the view clears the field, this hears
     * the change, and the record goes with it - so a reload after sending cannot hand the reader
     * their last question back primed to be sent a second time.
     *
     * <p>Silent on a null session and on an id that could not be a conversation. Both mean there is
     * nowhere legitimate to write, and inventing a place to write to is how a draft ends up filed
     * under a thread it does not belong to.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param session        the session to write into; null is tolerated and does nothing
     * @param conversationId the conversation the draft was written for
     * @param draft          the composer's current value; null and blank both erase the record
     */
    static void remember(VaadinSession session, String conversationId, String draft) {
        if (session == null || !ConversationIds.isWellFormed(conversationId)) {
            return;
        }
        Map<String, String> drafts = draftsOf(session).byConversation;
        // Removed before it is put back so a conversation being typed into moves to the end of the
        // map. Without this, insertion order would leave the thread the reader is actually working in
        // at the head, which is the end eviction takes from.
        drafts.remove(conversationId);
        if (draft == null || draft.isBlank()) {
            return;
        }
        drafts.put(conversationId, draft);
        evictOldest(drafts);
    }

    /**
     * What was left unsent in a conversation.
     *
     * @param session        the session to read from; null is tolerated
     * @param conversationId the conversation being opened
     * @return the draft, or an empty string when there is none - empty rather than null because
     *         every caller is about to put it into a field, and a field cannot hold null
     */
    static String recall(VaadinSession session, String conversationId) {
        if (session == null || !ConversationIds.isWellFormed(conversationId)) {
            return "";
        }
        String draft = draftsOf(session).byConversation.get(conversationId);
        return draft == null ? "" : draft;
    }

    /**
     * Drops the oldest drafts until the record is inside both of its bounds.
     *
     * <p>Never empties the map. The entry just written is the reader's current work and is worth more
     * than either bound: a single draft larger than {@link #MAX_CHARACTERS} is a paste the reader is
     * about to send, and evicting it would be this class deleting the text it exists to preserve.
     *
     * @param drafts the record, in order of least to most recently written
     */
    private static void evictOldest(Map<String, String> drafts) {
        int characters = drafts.values().stream().mapToInt(String::length).sum();
        Iterator<Map.Entry<String, String>> oldest = drafts.entrySet().iterator();
        while (drafts.size() > 1 && (drafts.size() > MAX_CONVERSATIONS || characters > MAX_CHARACTERS)) {
            characters -= oldest.next().getValue().length();
            oldest.remove();
        }
    }

    /**
     * The session's record, created on first use.
     *
     * <p>Held under its own type rather than under a string key, so nothing else in the session can
     * collide with it and nothing has to be cast back out of {@link Object} and checked.
     *
     * @param session the session, already known to be non-null
     * @return the record, never null and never replaced once created
     */
    private static Drafts draftsOf(VaadinSession session) {
        Drafts drafts = session.getAttribute(Drafts.class);
        if (drafts == null) {
            drafts = new Drafts();
            session.setAttribute(Drafts.class, drafts);
        }
        return drafts;
    }

    /**
     * One session's unsent text, keyed by conversation and ordered by when it was last written.
     *
     * <p>A named type rather than a bare map because it is what identifies the attribute, and
     * {@link Serializable} because a session may be passivated or replicated and an attribute that
     * cannot be written takes the whole session down with it.
     */
    private static final class Drafts implements Serializable {

        /**
         * Created empty on first use and never by anyone outside this file, so the implicit
         * constructor is written out only to carry that fact and the class invariant with it:
         * every mutation happens on the Vaadin UI thread, which is why the map needs no locking.
         */
        private Drafts() {
        }

        /** Conversation id to unsent text, least recently written first, which is eviction order. */
        private final Map<String, String> byConversation = new LinkedHashMap<>();
    }
}
