package ai.javaclaw.chat.ui;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each conversation was last spoken to, as far as this process can honestly say.
 *
 * <p>A sidebar wants recency: the chat you were just in belongs at the top, and "2m" beside a title
 * is how a reader finds it again. The chat store records that time - its files carry an
 * {@code updatedAt} - but {@code ChatMemoryRepository}, which is the contract the UI is allowed to
 * depend on, does not expose it, and reaching past that contract into the file layout would trade a
 * missing feature for a hidden coupling.
 *
 * <p>So this records what the application itself observes: the moment a message is sent to a
 * conversation from this UI. That is a real time about a real event, and it is deliberately
 * incomplete - a conversation last used before this process started has no entry here, and shows no
 * time at all rather than a plausible one. Ordering follows the same rule: known times first,
 * newest first, and everything else after them.
 *
 * <p>Application-scoped rather than session-scoped, because two browser sessions looking at the
 * same store are looking at the same conversations.
 */
@Component
class ConversationActivity {

    /**
     * Spring builds the single application-scoped instance through this constructor. It is written
     * out rather than left implicit so the threading contract has somewhere to live: unlike the rest
     * of this package, instances of this class are touched from the Vaadin UI thread and from the
     * background threads that carry responses, which is why the map below is concurrent.
     */
    ConversationActivity() {
    }

    /**
     * Bound on how many conversations are remembered. Entries are cheap, but the map is keyed by a
     * value that ultimately arrives from outside, and an unbounded map keyed from outside is a leak
     * waiting for a reason. Well past any plausible number of conversations on one machine.
     */
    private static final int CAPACITY = 512;

    /**
     * Conversation id to the last moment this application saw it used. Concurrent because writes
     * arrive from whichever thread is sending a message while the sidebar reads from the UI thread;
     * the absence of an entry is meaningful and is never filled in with a guess.
     */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    /**
     * Notes that a conversation was just used. Ignores anything that is not a usable id.
     *
     * @param conversationId the conversation just used; null and malformed ids are ignored rather
     *     than recorded under a key nothing will ever ask about
     */
    void record(String conversationId) {
        recordAt(conversationId, Instant.now());
    }

    /**
     * Notes activity at an explicit time, so a caller replaying known events - and the tests - can
     * say when rather than only that.
     *
     * @param conversationId conversation that was used; refused unless well formed
     * @param when           the moment it was used
     */
    void recordAt(String conversationId, Instant when) {
        if (!ConversationIds.isWellFormed(conversationId) || when == null) {
            return;
        }
        if (lastActivity.size() >= CAPACITY && !lastActivity.containsKey(conversationId)) {
            evictOldest();
        }
        lastActivity.put(conversationId, when);
    }

    /**
     * When this conversation was last used, if this process saw it happen.
     *
     * @param conversationId conversation to look up
     * @return the recorded moment, or empty when there is nothing honest to report
     */
    Optional<Instant> lastActivity(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastActivity.get(conversationId));
    }

    /**
     * Drops the least recently used entry.
     *
     * <p>Not atomic with the put that follows it, and does not need to be: the worst outcome of a
     * race is one extra entry above the cap or one entry evicted early, and neither is a fact the
     * reader can see.
     */
    private void evictOldest() {
        lastActivity.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .map(Map.Entry::getKey)
                .ifPresent(lastActivity::remove);
    }
}
