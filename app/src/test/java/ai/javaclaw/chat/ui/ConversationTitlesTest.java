package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a conversation is called, and why two of them are never called the same thing.
 *
 * <p>The column is roughly 230px of text. A title that echoes the opening message spends all of it
 * on the scaffolding people put around a request - "explain", "in one sentence", "keep it under 150
 * words" - and truncates before reaching the subject, which is the only part that tells one thread
 * from another. Rows then render byte-identical and the list stops being navigable.
 *
 * <p>So the two properties pinned here are the two the reader actually needs: a title says what the
 * thread is <em>about</em>, and no two rows on screen say the same thing.
 */
class ConversationTitlesTest {

    private static final int LIMIT = 42;

    @Test
    @DisplayName("a request verb is scaffolding, not the subject")
    void dropsTheLeadingRequestVerb() {
        assertThat(ConversationTitles.topic("Explain virtual threads in exactly two sentences."))
                .isEqualTo("Virtual threads");
        assertThat(ConversationTitles.topic("Summarise the Modulith verification rules"))
                .isEqualTo("Modulith verification rules");
        assertThat(ConversationTitles.topic("Can you please compare Gradle and Maven?"))
                .isEqualTo("Gradle and Maven");
    }

    @Test
    @DisplayName("an opening format directive is dropped, so the question survives truncation")
    void dropsTheOpeningFormatDirective() {
        assertThat(ConversationTitles.topic("In one sentence, why does Testcontainers need Docker?"))
                .isEqualTo("Why does Testcontainers need Docker");
        assertThat(ConversationTitles.topic("In markdown: a level-2 heading and a table"))
                .isEqualTo("Level-2 heading and a table");
    }

    @Test
    @DisplayName("a trailing style instruction is dropped too - it describes the answer, not the topic")
    void dropsTheTrailingStyleInstruction() {
        assertThat(ConversationTitles.topic(
                "List the 15 most significant features added to Java between version 8 and version 21, "
                        + "one per line, each starting with a bold name followed by one sentence."))
                .isEqualTo("15 most significant features added to Java between version 8 and version 21");
        assertThat(ConversationTitles.topic("Describe the composer. Keep it under 150 words."))
                .isEqualTo("Composer");
    }

    @Test
    @DisplayName("a message that is already a topic is left alone")
    void leavesATopicAlone() {
        assertThat(ConversationTitles.topic("Comparing build tools")).isEqualTo("Comparing build tools");
        assertThat(ConversationTitles.topic("Vaadin session locking")).isEqualTo("Vaadin session locking");
    }

    @Test
    @DisplayName("stripping never empties a title, however little is left to strip")
    void neverReturnsNothing() {
        // Every one of these is entirely scaffolding. Stripping to nothing and rendering a blank row
        // would be worse than the echo this class exists to remove.
        assertThat(ConversationTitles.topic("Explain")).isNotBlank();
        assertThat(ConversationTitles.topic("In one sentence,")).isNotBlank();
        assertThat(ConversationTitles.topic("   ")).isNotBlank();
        assertThat(ConversationTitles.topic(null)).isNotBlank();
    }

    @Test
    @DisplayName("a title is bounded, whatever arrives in the first message")
    void boundsTheTitle() {
        String flood = "Explain " + "supercalifragilistic ".repeat(500);

        String topic = ConversationTitles.topic(flood);

        assertThat(topic.length()).isLessThanOrEqualTo(ConversationTitles.MAX_TITLE);
        assertThat(topic).doesNotContain("\n");
    }

    @Test
    @DisplayName("newlines and runs of space collapse, so a title cannot become two lines")
    void flattensWhitespace() {
        assertThat(ConversationTitles.topic("Explain\n\n  virtual\tthreads")).isEqualTo("Virtual threads");
    }

    // ------------------------------------------------------------------ the column

    @Test
    @DisplayName("no two visible titles are equal after truncation, even when the threads opened alike")
    void labelsAreDistinctAfterTruncation() {
        // The two long ones are byte-identical at the source: the capture harness really does ask the
        // same question twice, and before this the column drew two rows a reader could not tell apart.
        List<ConversationSummary> rows = List.of(
                row("web-1", "List the 15 most significant features added to Java between version 8 "
                        + "and version 21, one per line."),
                row("web-2", "List the 15 most significant features added to Java between version 8 "
                        + "and version 21, one per line."),
                row("web-3", "In one sentence, what is a Vaadin session?"),
                row("web-4", "In one sentence, what is a sealed interface?"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, LIMIT);

        assertThat(labels).hasSize(4);
        assertThat(labels.values()).doesNotHaveDuplicates();
        assertThat(labels.values()).allSatisfy(label -> assertThat(label).isNotBlank());
        // Distinct is necessary and not sufficient. Eliding what two identical threads share leaves
        // the last word of a sentence they both end with - "…21" and "…21 (2)" - which passes a
        // duplicate check and tells the reader nothing. Identical threads keep their name and are
        // separated by the marker alone.
        assertThat(labels.get("web-1")).startsWith("15 most significant features");
        // The marker leads. The column ellipsises in CSS on top of this truncation, and a trailing
        // marker is the first thing that clipping takes off - which would leave two rows identical
        // again, with a test still passing.
        assertThat(labels.get("web-2")).startsWith("(2) 15 most significant features");
    }

    @Test
    @DisplayName("a shared opening phrase is elided, so the differing half is what the column shows")
    void elidesASharedOpeningPhrase() {
        List<ConversationSummary> rows = List.of(
                row("web-1", "Virtual threads and the Vaadin session lock in a streaming view"),
                row("web-2", "Virtual threads and the Vaadin session lock in a scheduled job"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, LIMIT);

        assertThat(labels.values()).doesNotHaveDuplicates();
        // The part that separates them has to be inside the column's measure, not past its ellipsis.
        assertThat(labels.get("web-1")).contains("streaming view");
        assertThat(labels.get("web-2")).contains("scheduled job");
    }

    @Test
    @DisplayName("labels fit the column, so the browser's ellipsis cannot undo the disambiguation")
    void labelsFitTheColumn() {
        List<ConversationSummary> rows = List.of(
                row("web-1", "Explain virtual threads in exactly two sentences."),
                row("web-2", "Explain virtual threads in exactly two sentences."),
                row("web-3", "List the 15 most significant features added to Java between 8 and 21."));

        Map<String, String> labels = ConversationTitles.forColumn(rows, LIMIT);

        assertThat(labels.values()).allSatisfy(label -> assertThat(label.length()).isLessThanOrEqualTo(LIMIT));
        assertThat(labels.values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an empty column is an empty map, not a failure")
    void toleratesNothingToLabel() {
        assertThat(ConversationTitles.forColumn(List.of(), LIMIT)).isEmpty();
    }

    private static ConversationSummary row(String id, String title) {
        return new ConversationSummary(id, ConversationTitles.topic(title), title, "Web", List.of(),
                Optional.empty(), ConversationSummary.Source.STORED);
    }
}
