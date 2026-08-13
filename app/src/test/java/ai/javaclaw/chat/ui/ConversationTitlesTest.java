package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a conversation is called, and why no two rows read the same at a glance.
 *
 * <p>The column is roughly 230px of text. A title that echoes the opening message spends all of it
 * on the scaffolding people put around a request - "explain", "in one sentence", "keep it under 150
 * words" - and truncates before reaching the subject, which is the only part that tells one thread
 * from another. Rows then render byte-identical and the list stops being navigable.
 *
 * <p>So the two properties pinned here are the two the reader actually needs: a title says what the
 * thread is <em>about</em>, and no two rows on screen <em>read</em> the same.
 *
 * <p>The second one is deliberately not "no two labels are equal". Equality was the property this
 * class used to promise, and it is the wrong one: twenty labels sharing a twenty-nine character
 * opening and differing only in a trailing ordinal are all unequal, and all identical on screen. The
 * tests below are written as properties over whole sets rather than as examples, because the defect
 * was that the asserted property was too weak - an example would not have caught it, and would not
 * stop it coming back.
 */
class ConversationTitlesTest {

    private static final int LIMIT = 42;

    /** Widths worth trying every corpus at: the floor, the sidebar's real width, and either side. */
    private static final int[] WIDTHS = {8, 12, 24, 38, 42, 120};

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

    @Test
    @DisplayName("twenty rows sharing an opening are told apart by what differs, not by what does not")
    void separatesTwentyRowsThatShareAnOpening() {
        // The reported shape, exactly: a twenty-nine character opening every row has, and an ordinal
        // at the far right that is the only thing any of them does not share. Every one of these
        // labels used to be unequal to every other, and all twenty rendered as "How do I configure
        // the Spring…" with the ordinal past the ellipsis.
        String opening = "How do I configure the Spring ";
        List<ConversationSummary> rows = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> named("web-" + index, opening + index))
                .toList();

        Map<String, String> labels = ConversationTitles.forColumn(rows, LIMIT);

        assertThat(labels).hasSize(20);
        assertDistinguishable(labels.values(), LIMIT);
        // Not merely "somewhere in the string": the ordinal has to be inside the part of the row a
        // reader scanning twenty of them actually sees.
        assertThat(labels.get("web-17")).contains("17");
        assertThat(labels.get("web-17").indexOf("17")).isLessThan(ConversationTitles.GLANCE);
    }

    @Test
    @DisplayName("whatever the column is asked to draw, no two rows read the same at a glance")
    void everySetIsDistinguishableAtEveryWidth() {
        List<List<String>> corpora = List.of(
                // The reported shape, and the same shape with the shared run one character either
                // side of the glance - the boundary is where an off-by-one would hide.
                ordinals("How do I configure the Spring ", 20),
                ordinals("x".repeat(ConversationTitles.GLANCE - 1) + " ", 4),
                ordinals("x".repeat(ConversationTitles.GLANCE) + " ", 4),
                ordinals("x".repeat(ConversationTitles.GLANCE + 1) + " ", 4),
                // Identical to the last character: there is nothing to expose, only somewhere to
                // put a marker.
                Collections.nCopies(5, "Virtual threads and the Vaadin session lock"),
                // Differing only inside one long unbroken token, where a word boundary is no help.
                List.of("build-000000000000001", "build-000000000000002", "build-000000000000003"),
                // Genuinely different threads, which must come through untouched.
                List.of("Alpha", "Beta", "Gamma"),
                List.of("Vaadin session locking"),
                // Shorter than the column, and one a strict prefix of the next.
                List.of("Java 8", "Java 9", "Java 21", "Java"),
                // Blank is not a name. A row has to be called something.
                Arrays.asList("", "   ", "Real topic"),
                // Longer than the column on every row, sharing everything but the last word.
                ordinals("Compare the memory footprint of a platform thread and a virtual thread ", 6));

        for (List<String> titles : corpora) {
            for (int width : WIDTHS) {
                Map<String, String> labels = forColumn(titles, width);

                assertThat(labels).as("one label per row for %s at width %d", titles, width)
                        .hasSize(titles.size());
                assertDistinguishable(labels.values(), width);
            }
        }
    }

    @Test
    @DisplayName("titles that differ only in a long numeric tail put the tail where it can be read")
    void separatesALongNumericTail() {
        // No spaces to elide at, so a whole-word elision cannot move the difference into view. The
        // cut has to fall where the strings actually diverge.
        List<ConversationSummary> rows = List.of(
                named("web-1", "build-000000000000001"),
                named("web-2", "build-000000000000002"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, LIMIT);

        assertDistinguishable(labels.values(), LIMIT);
        assertThat(labels.get("web-1")).isEqualTo("…1");
        assertThat(labels.get("web-2")).isEqualTo("…2");
    }

    @Test
    @DisplayName("a label is cut between characters, not through one")
    void respectsCharacterBoundaries() {
        // Surrogate pairs and combining marks are where "character count" and "what is drawn" come
        // apart: cutting by code unit produces a lone surrogate, which the browser draws as a
        // replacement box, or an accent orphaned onto the ellipsis. The accented row below is stored
        // decomposed - a letter and a combining mark, two chars drawn as one character - and the
        // ideographic row is twelve characters drawn twenty-four columns wide.
        List<ConversationSummary> rows = List.of(
                named("web-1", "🧵".repeat(30) + " one"),
                named("web-2", "🧵".repeat(30) + " two"),
                named("web-3", "é".repeat(30) + " three"),
                named("web-4", "仕様の確認をお願いします"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, 38);

        assertDistinguishable(labels.values(), 38);
        assertThat(labels.values()).allSatisfy(label -> {
            // A well-formed pair reads back as one code point above the surrogate range; only a half
            // that lost its partner is still a surrogate here.
            assertThat(label.codePoints().noneMatch(codePoint ->
                    codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE))
                    .as("no half of a surrogate pair survives the cut in %s", label).isTrue();
            assertThat(Character.getType(label.codePointAt(0)))
                    .as("no label begins with an orphaned combining mark: %s", label)
                    .isNotEqualTo((int) Character.NON_SPACING_MARK);
        });
    }

    @Test
    @DisplayName("a row is called something even when the store had nothing to call it")
    void namesARowWithNoTitle() {
        Map<String, String> labels = forColumn(Arrays.asList("", "   "), LIMIT);

        assertDistinguishable(labels.values(), LIMIT);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The property the column depends on, asserted the way the reader experiences it.
     *
     * <p>Distinctness is checked over the leading {@link ConversationTitles#GLANCE} characters rather
     * than over the whole label, because that is the part of a row that is on screen and scanned. It
     * is computed here with a plain prefix rather than by calling back into the class under test, so
     * the assertion cannot agree with an implementation that is wrong.
     *
     * @param labels the labels a column would paint
     * @param width  the character budget those labels were asked to fit
     */
    private static void assertDistinguishable(Collection<String> labels, int width) {
        assertThat(labels).allSatisfy(label -> {
            assertThat(label).as("a blank row names nothing").isNotBlank();
            assertThat(label.length()).as("%s overflows a %d character column", label, width)
                    .isLessThanOrEqualTo(width);
        });
        assertThat(labels.stream().map(ConversationTitlesTest::atAGlance).toList())
                .as("what a reader sees of %s", labels)
                .doesNotHaveDuplicates();
    }

    /** The leading characters of a label: what is read before the eye moves to the next row. */
    private static String atAGlance(String label) {
        return label.substring(0, Math.min(ConversationTitles.GLANCE, label.length()));
    }

    /** {@code count} titles that share {@code opening} and differ only in a trailing ordinal. */
    private static List<String> ordinals(String opening, int count) {
        List<String> titles = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            titles.add(opening + index);
        }
        return titles;
    }

    /** Labels for these titles, one row each, taken exactly as given rather than through a topic. */
    private static Map<String, String> forColumn(List<String> titles, int width) {
        List<ConversationSummary> rows = new ArrayList<>();
        for (int index = 0; index < titles.size(); index++) {
            rows.add(named("web-" + index, titles.get(index)));
        }
        return ConversationTitles.forColumn(rows, width);
    }

    /** A row whose title is derived from the opening message, as the directory builds one. */
    private static ConversationSummary row(String id, String title) {
        return new ConversationSummary(id, ConversationTitles.topic(title), title, "Web", List.of(),
                Optional.empty(), ConversationSummary.Source.STORED);
    }

    /** A row carrying exactly this title, for the cases where the topic derivation is not the subject. */
    private static ConversationSummary named(String id, String title) {
        return new ConversationSummary(id, title, title, "Web", List.of(),
                Optional.empty(), ConversationSummary.Source.STORED);
    }
}
