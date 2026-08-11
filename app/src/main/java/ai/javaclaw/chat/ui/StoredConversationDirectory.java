package ai.javaclaw.chat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Backs the sidebar with the conversations the agent actually has, plus clearly-marked samples.
 *
 * <p>The store is reached through Spring AI's {@link ChatMemoryRepository} rather than through the
 * concrete file-system repository, so this class depends on an interface the application already
 * treats as its chat-memory contract and on nothing internal to another module.
 *
 * <p>What that interface can supply is thin: a list of conversation ids and their messages. Titles
 * are therefore derived from the first thing the reader asked, and the folder is derived from the
 * channel encoded in the id ({@code web}, {@code telegram-123456}). Both are real, if inferred.
 *
 * <p>The derived title is not stable. Chat memory is a sliding window, so once a conversation runs
 * past it the earliest question is dropped from the store and the title moves to whatever is now
 * oldest. A conversation renaming itself as it grows is a real defect, and it is not one this
 * adapter can fix: it needs a title recorded once, which is the store's job.
 * Modification times, human-chosen titles, folders and tags are <em>not</em> recorded anywhere, so
 * rather than invent them per conversation this adapter appends a small set of rows marked
 * {@link ConversationSummary.Source#SAMPLE} and lets the sidebar say so out loud.
 */
@Component
class StoredConversationDirectory implements ConversationDirectory {

    private static final Logger log = LoggerFactory.getLogger(StoredConversationDirectory.class);

    /** Above this the title stops being a title and starts being the message. */
    private static final int TITLE_LENGTH = 42;
    private static final int PREVIEW_LENGTH = 160;

    private final ChatMemoryRepository repository;

    StoredConversationDirectory(ChatMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ConversationSummary> conversations() {
        List<ConversationSummary> rows = new ArrayList<>(storedConversations());
        rows.addAll(SampleConversations.rows());
        // Stored rows have no timestamp to sort by, so ordering is by group first and the store's
        // own order within it. Sorting samples ahead of real data would be an odd thing to teach.
        rows.sort(Comparator
                .comparingInt((ConversationSummary row) -> row.source() == ConversationSummary.Source.STORED ? 0 : 1)
                .thenComparing(row -> row.updatedAt().orElse(Instant.EPOCH), Comparator.reverseOrder()));
        return List.copyOf(rows);
    }

    @Override
    public Optional<ConversationSummary> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return conversations().stream().filter(row -> row.id().equals(id)).findFirst();
    }

    /**
     * Reads the store, tolerating a store that is missing, empty or broken.
     *
     * <p>The sidebar is chrome around the chat; it must not be the reason the page fails to render.
     * A failure here is logged once and degrades to an empty section rather than propagating.
     */
    private List<ConversationSummary> storedConversations() {
        List<String> ids;
        try {
            ids = repository.findConversationIds();
        } catch (RuntimeException e) {
            log.warn("Could not list conversations for the sidebar; showing none", e);
            return List.of();
        }
        List<ConversationSummary> rows = new ArrayList<>();
        for (String id : ids) {
            summarise(id).ifPresent(rows::add);
        }
        return rows;
    }

    private Optional<ConversationSummary> summarise(String id) {
        List<Message> messages;
        try {
            messages = repository.findByConversationId(id);
        } catch (RuntimeException e) {
            log.warn("Could not read conversation {} for the sidebar; skipping it", id, e);
            return Optional.empty();
        }

        String firstQuestion = firstText(messages, MessageType.USER);
        String lastAnything = lastText(messages);
        String title = firstQuestion.isBlank() ? "Untitled chat" : shorten(firstQuestion, TITLE_LENGTH);
        String preview = shorten(lastAnything.isBlank() ? firstQuestion : lastAnything, PREVIEW_LENGTH);

        return Optional.of(new ConversationSummary(
                id,
                title,
                preview,
                channelOf(id),
                List.of(),
                // The YAML frontmatter does carry updatedAt, but ChatMemoryRepository does not expose
                // it. Guessing a time is worse than showing none, so the row simply has no time.
                Optional.empty(),
                ConversationSummary.Source.STORED));
    }

    private static String firstText(List<Message> messages, MessageType type) {
        return messages.stream()
                .filter(message -> message.getMessageType() == type)
                .map(Message::getText)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String lastText(List<Message> messages) {
        String text = "";
        for (Message message : messages) {
            String candidate = message.getText();
            if (candidate != null && !candidate.isBlank() && message.getMessageType() != MessageType.SYSTEM) {
                text = candidate;
            }
        }
        return text;
    }

    /**
     * The channel a conversation belongs to, read off its id.
     *
     * <p>Conversation ids are channel names - {@code web}, {@code telegram-123456789} - so the part
     * before the first dash is genuine metadata rather than a guess, and it is the closest thing to
     * a folder the store has today.
     */
    private static String channelOf(String id) {
        int dash = id.indexOf('-');
        String channel = dash > 0 ? id.substring(0, dash) : id;
        if (channel.isBlank()) {
            return "Conversations";
        }
        return channel.substring(0, 1).toUpperCase(Locale.ROOT) + channel.substring(1);
    }

    /** Cuts at a word boundary where there is one nearby, so a title does not end mid-word. */
    private static String shorten(String text, int limit) {
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= limit) {
            return flat;
        }
        String cut = flat.substring(0, limit);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > limit / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }

    /**
     * Rows that describe nothing real.
     *
     * <p>They exist because folders, tags and modification times have no home in the store yet, and
     * a sidebar cannot be designed - or reviewed - against a single untitled row. Every one of them
     * is marked {@link ConversationSummary.Source#SAMPLE}, carries {@code data-source="sample"} in
     * the DOM, and is accounted for by the footnote under the list. They must be deleted, not
     * quietly promoted, the day the store can answer these questions itself.
     */
    private static final class SampleConversations {

        private static List<ConversationSummary> rows() {
            Instant now = Instant.now();
            return List.of(
                    sample("sample-modulith", "Splitting the app into Modulith modules",
                            "Where should the boundary between the chat module and the agent module sit?",
                            "Architecture", List.of("modulith", "boundaries"), now.minus(40, ChronoUnit.MINUTES)),
                    sample("sample-virtual-threads", "Virtual threads and the session lock",
                            "Explain why holding the Vaadin session lock across a blocking model call freezes the browser.",
                            "Architecture", List.of("concurrency", "vaadin"), now.minus(5, ChronoUnit.HOURS)),
                    sample("sample-provider-swap", "Swapping Ollama for Anthropic",
                            "What changes in configuration when the chat provider is switched at runtime?",
                            "Architecture", List.of("providers"), now.minus(2, ChronoUnit.DAYS)),
                    sample("sample-release-notes", "Draft release notes for 0.4",
                            "Summarise the merged pull requests since the last tag into user-facing notes.",
                            "Writing", List.of("release"), now.minus(26, ChronoUnit.HOURS)),
                    sample("sample-readme", "Rewrite the README opening",
                            "The first paragraph explains the build before it explains the product. Fix that.",
                            "Writing", List.of("docs"), now.minus(9, ChronoUnit.DAYS)),
                    sample("sample-gradle", "Gradle vs Maven for this build",
                            "Compare incremental build behaviour for a multi-module JVM project.",
                            "Scratch", List.of("build"), now.minus(3, ChronoUnit.HOURS)),
                    sample("sample-regex", "A regex for ISO-8601 durations",
                            "Match PT5M, P1DT2H and friends, rejecting the empty P.",
                            "Scratch", List.of("regex"), now.minus(11, ChronoUnit.DAYS)));
        }

        private static ConversationSummary sample(String id, String title, String preview, String group,
                                                  List<String> tags, Instant updatedAt) {
            return new ConversationSummary(id, title, preview, group, tags, Optional.of(updatedAt),
                    ConversationSummary.Source.SAMPLE);
        }

        private SampleConversations() {
        }
    }
}
