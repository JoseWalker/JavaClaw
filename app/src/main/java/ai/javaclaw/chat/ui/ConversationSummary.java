package ai.javaclaw.chat.ui;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What the sidebar needs to know about one conversation.
 *
 * <p>This is the UI's own view of a conversation, not the storage model. The chat store is expected
 * to change, so nothing here mirrors a file layout or a table: the sidebar asks
 * {@link ConversationDirectory} for these and never learns where they came from.
 *
 * <p>{@link Source} is part of the record on purpose. Some of what a sidebar wants - folders, tags,
 * a modification time - is not recorded anywhere yet, and a UI that quietly invents it teaches the
 * reader to trust data that does not exist. Every row carries its provenance instead, and the
 * sidebar prints a footnote when any of it is a sample.
 *
 * @param id       conversation id as the agent knows it; also the row's stable identity
 * @param title    one line, already shortened for a narrow column
 * @param preview  longer excerpt used as the row's tooltip and as extra search text
 * @param group    folder-like heading the row is filed under
 * @param tags     free-form labels; searched, whether or not they are painted
 * @param updatedAt when the conversation last changed, when the store can say
 * @param source   whether this row describes stored data or is a placeholder
 */
public record ConversationSummary(
        String id,
        String title,
        String preview,
        String group,
        List<String> tags,
        Optional<Instant> updatedAt,
        Source source) {

    /** Where a row's contents come from, so the UI can be honest about placeholder data. */
    public enum Source {

        /** Backed by a conversation that really exists in the chat store. */
        STORED,

        /** Fabricated to exercise the layout while the real metadata does not exist yet. */
        SAMPLE,

        /** A chat the reader has started in this session but has not sent anything to yet. */
        DRAFT
    }

    public ConversationSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(source, "source");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be blank");
        }
        // Defensive copy: a row handed to the sidebar must not change under it afterwards.
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
    }

    /**
     * Whether this row should survive the sidebar's search box.
     *
     * <p>Title, excerpt and tags all count. Matching the excerpt is what makes the box useful for
     * "the one where I asked about virtual threads", which is how conversations are actually
     * remembered; matching tags is what makes the labels worth carrying at all.
     *
     * @param query raw text from the search field; blank matches everything
     * @return true when the row should stay visible
     */
    boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return contains(title, needle)
                || contains(preview, needle)
                || contains(group, needle)
                || tags.stream().anyMatch(tag -> contains(tag, needle));
    }

    private static boolean contains(String haystack, String lowercaseNeedle) {
        return haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }
}
