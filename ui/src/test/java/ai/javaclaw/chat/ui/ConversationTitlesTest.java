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

    /** The width the sidebar really asks for, which is where the reported defects were photographed. */
    private static final int SIDEBAR = 38;

    /** Widths worth trying every corpus at: the floor, the sidebar's real width, and either side. */
    private static final int[] WIDTHS = {8, 12, 24, 38, 42, 120};

    /**
     * Below this the column is too narrow to offer any choice of cut.
     *
     * <p>The word-boundary property is asserted only at or above it. At eight or twelve columns the
     * label has room for the difference and almost nothing else, so the cut is forced to wherever the
     * difference is - mid-word if that is where it falls - and demanding a word boundary there would
     * be demanding a label that cannot exist.
     */
    private static final int NARROWEST_WITH_A_CHOICE = 24;

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

    /**
     * The other column the blind review photographed: thirteen rows reading "…21" through "(13) …21".
     *
     * <p>Genuinely identical threads, which is what a capture harness asking one question thirteen
     * times produces, and the case where an occurrence marker is the honest answer - there is nothing
     * to expose, because there is no difference. What was not honest was spending the subject to get
     * there: the elision cut every one of them down to the closing ordinal first, so the marker was
     * left carrying the whole of the row and thirteen conversations about Java features rendered as
     * thirteen bracketed counters.
     *
     * <p>Fails on the original at the first assertion, which returns "…21" and "(2) …21" upwards. The
     * marker is asserted too, because dropping it would be the opposite defect: thirteen rows that
     * really are alike must not render as thirteen rows that look like one row drawn thirteen times.
     */
    @Test
    @DisplayName("thirteen identical threads keep their subject and are numbered, not reduced to numbers")
    void numbersIdenticalThreadsWithoutSpendingTheSubject() {
        String asked = "List the 15 most significant features added to Java between version 8 "
                + "and version 21, one per line.";
        List<ConversationSummary> rows = IntStream.rangeClosed(1, 13)
                .mapToObj(index -> row("web-" + index, asked))
                .toList();

        Map<String, String> labels = ConversationTitles.forColumn(rows, SIDEBAR);

        assertThat(labels).hasSize(13);
        assertDistinguishable(labels.values(), SIDEBAR);
        assertThat(labels.values()).allSatisfy(label ->
                assertThat(label).as("%s is a counter, not a conversation", label).contains("features"));
        // Leading, because the column ellipsises in CSS on top of this budget and a trailing marker
        // is the first thing clipping takes off - which would put all thirteen rows back.
        assertThat(labels.get("web-13")).startsWith("(13) ");
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
                ordinals("Compare the memory footprint of a platform thread and a virtual thread ", 6),
                // The shapes the blind review filed: rows the column can draw whole, and a run whose
                // only difference is its closing word.
                List.of("History of the JVM", "History of the Java language",
                        "Markdown Build Tools Example", "Markdown Build Tools Comparison"),
                List.of("Java Features Versions 8 to 21", "Java Features Versions 8 to 25"),
                // A cluster with one long topic in it. The long row is what used to decide how deep
                // every other row was cut, which is the shape reported as fifteen rows of "(n) …21".
                theReportedRun());

        for (List<String> titles : corpora) {
            for (int width : WIDTHS) {
                Map<String, String> labels = forColumn(titles, width);

                assertThat(labels).as("one label per row for %s at width %d", titles, width)
                        .hasSize(titles.size());
                assertDistinguishable(labels.values(), width);
                if (width >= NARROWEST_WITH_A_CHOICE) {
                    for (int index = 0; index < titles.size(); index++) {
                        assertCutAtAWordBoundary(titles.get(index), labels.get("web-" + index));
                        assertKeepsMoreThanTheDifference(titles.get(index), labels.get("web-" + index));
                    }
                }
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

        // Wide enough to draw both ids whole. This used to come back as "…1" and "…2": unique,
        // fitting, and naming nothing - the column threw away twenty characters it was drawing
        // perfectly well, because the difference was past a scan budget rather than past the box.
        Map<String, String> wide = ConversationTitles.forColumn(rows, LIMIT);
        assertDistinguishable(wide.values(), LIMIT);
        assertThat(wide.get("web-1")).isEqualTo("build-000000000000001");
        assertThat(wide.get("web-2")).isEqualTo("build-000000000000002");

        // Narrow enough that the difference really is off the end of the row, which is the case the
        // character-level cut exists for. Both ids are one unbroken token, so there is no word
        // boundary to prefer and the cut falls between characters.
        Map<String, String> narrow = ConversationTitles.forColumn(rows, 12);
        assertDistinguishable(narrow.values(), 12);
        assertThat(narrow.get("web-1")).startsWith("…").endsWith("1");
        assertThat(narrow.get("web-2")).startsWith("…").endsWith("2");
    }

    // -------------------------------------------------- what the blind review filed

    /**
     * The reported shape, at the width it was photographed at.
     *
     * <p>Three critics and a behaviour audit reported the same thing: rows reading "…JVM" and
     * "…s 8 to 25" in a box the audit measured at 197px of scroll width in 197px of client width -
     * nothing clipped, nothing overflowing, and the name of the conversation removed anyway. The
     * elision was triggered by two rows agreeing over a sixteen-column scan budget, which is a
     * property of the reader's eye rather than of the column, and the column was drawing both rows in
     * full the whole time.
     *
     * <p>This fails on the original: "History of the JVM" and "History of the Java language" share
     * their first sixteen columns, so both were elided to their last word.
     */
    @Test
    @DisplayName("a title the column can draw in full is drawn in full")
    void leavesATitleTheColumnCanDraw() {
        List<ConversationSummary> rows = List.of(
                named("web-1", "History of the JVM"),
                named("web-2", "History of the Java language"),
                named("web-3", "Markdown Build Tools Example"),
                named("web-4", "Markdown Build Tools Comparison"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, SIDEBAR);

        assertThat(labels.values()).allSatisfy(label ->
                assertThat(label).as("%s was elided although the column can draw it", label)
                        .doesNotStartWith("…"));
        assertThat(labels.get("web-1")).isEqualTo("History of the JVM");
        assertThat(labels.get("web-2")).isEqualTo("History of the Java language");
        assertThat(labels.get("web-3")).isEqualTo("Markdown Build Tools Example");
        assertDistinguishable(labels.values(), SIDEBAR);
    }

    /**
     * The other pair the review photographed: "…s 8 to 25" from "Java Features Versions 8 to 25".
     *
     * <p>Two claims, and the second is what fails on the original. A cut opens on a word rather than
     * on the tail of one - which the original could violate, because where a whole-word cut left the
     * rows still alike it cut at the exact character of divergence instead. And a cut stops while the
     * label still names something: the original drove this pair to "…21" and "…25", spending three
     * words of subject to move two digits four columns to the left.
     */
    @Test
    @DisplayName("an elision opens on a word, and stops while the label still names something")
    void cutsBetweenWordsAndKeepsTheSubject() {
        List<ConversationSummary> rows = List.of(
                named("web-1", "Java Features Versions 8 to 21"),
                named("web-2", "Java Features Versions 8 to 25"));

        Map<String, String> labels = ConversationTitles.forColumn(rows, SIDEBAR);

        assertDistinguishable(labels.values(), SIDEBAR);
        assertCutAtAWordBoundary("Java Features Versions 8 to 21", labels.get("web-1"));
        assertCutAtAWordBoundary("Java Features Versions 8 to 25", labels.get("web-2"));
        assertThat(labels.values()).allSatisfy(label ->
                assertThat(label).as("%s is a pair of digits, not a name", label).contains("Versions"));
    }

    /**
     * Eighteen consecutive rows reading "(3) …21" through "(18) …21": every visible label was eight
     * or nine characters, and the only thing telling one row from another was a bracketed counter.
     * The elision had been driven all the way to the last word because that was the only place the
     * difference could be made to land inside a sixteen-column scan budget.
     *
     * <p>Fails on the original, which returns "…21" for seventeen of these and "…25" for the last.
     */
    @Test
    @DisplayName("a run of threads differing in their closing word keeps the words in front of it")
    void keepsTheSubjectWhenOnlyTheClosingWordDiffers() {
        String opening = "Compare the memory footprint of a platform thread and a virtual thread ";
        List<ConversationSummary> rows = new ArrayList<>();
        for (int index = 1; index <= 18; index++) {
            rows.add(named("web-" + index, opening + (index < 18 ? "21" : "25")));
        }

        Map<String, String> labels = ConversationTitles.forColumn(rows, SIDEBAR);

        assertDistinguishable(labels.values(), SIDEBAR);
        assertThat(labels.values()).allSatisfy(label -> {
            // A label is a name before it is a discriminator, and "…21" is not a name.
            assertThat(label).as("%s says nothing about the conversation", label).contains("thread");
            assertThat(label.length()).as("%s is a fragment, not a label", label)
                    .isGreaterThanOrEqualTo(13);
        });
    }

    /**
     * Fifteen consecutive rows of "(n) …21", photographed at 834x1112 with the drawer open.
     *
     * <p>The run is real traffic: forty-seven conversations in the store opened with the same
     * question, so their topics agree for fifty characters and part company on the release at the
     * end. What made the column unreadable was not the duplication - two threads that really did ask
     * the same thing have to be numbered, and {@link #numbersIdenticalThreadsWithoutSpendingTheSubject}
     * pins that - but that the elision spent every word of the subject first, leaving the bracketed
     * counter carrying the whole of the row.
     *
     * <p>The one long topic in the cluster is the mechanism, and it is why this is filed as a
     * property rather than as an example. The cut was floored at whatever made the <em>longest</em>
     * label fit the column, which on this cluster falls past the last space in the shared opening -
     * so the cut landed on the two digits that differ, and every short row in the cluster was reduced
     * to them. Nothing about that floor is about the reader: the column ellipsises anything that
     * overflows anyway.
     *
     * <p>Fails on the original at the sidebar's own width, where it returns "…21" for sixteen of
     * these rows and "…25" for the other two.
     */
    @Test
    @DisplayName("one long topic in a cluster does not cut every other row down to the digits")
    void keepsTheSubjectWhenOneTopicInTheClusterRunsLong() {
        List<String> titles = theReportedRun();

        Map<String, String> labels = forColumn(titles, SIDEBAR);

        assertDistinguishable(labels.values(), SIDEBAR);
        for (int index = 0; index < titles.size(); index++) {
            String label = labels.get("web-" + index);
            assertKeepsMoreThanTheDifference(titles.get(index), label);
            // Not merely "more than the digits": what the column is for is saying what the thread is
            // about, and every one of these threads is about a version range.
            assertThat(label).as("%s says nothing about the conversation", label).contains("version");
        }
        // The threads that really are identical are still numbered, because there is nothing else
        // honest to say about them - the marker is the fallback, not the label.
        assertThat(labels.get("web-1")).startsWith("(2) ");
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
     * <p>Distinctness is checked over the leading {@link ConversationTitles#DRAWN} characters rather
     * than over the whole label, because that is the part of a row the browser is certain to have
     * drawn: past it a wide label starts being clipped, and a difference in the clipped part is a
     * difference nobody has. It is computed here with a plain prefix rather than by calling back into
     * the class under test, so the assertion cannot agree with an implementation that is wrong.
     *
     * <p>It used to be checked over sixteen characters - what a reader takes in at a glance - and
     * that is what this file got wrong. Sixteen is a fine thing for a label to aim at and a ruinous
     * thing to require: requiring it made every pair of rows sharing a sixteen-character opening a
     * collision, including pairs the column was drawing in full, and the fix for the collision was to
     * delete the shared opening. The blind review photographed the result - eighteen rows whose whole
     * visible label was a bracketed counter and two digits.
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
        assertThat(labels.stream().map(label -> asDrawn(label, width)).toList())
                .as("what a reader sees of %s", labels)
                .doesNotHaveDuplicates();
    }

    /**
     * The part of a label the column is certain to have drawn.
     *
     * @param label a label the column would paint
     * @param width the character budget it was asked to fit, which caps the drawn part on a column
     *              narrower than the browser's clipping point
     */
    private static String asDrawn(String label, int width) {
        int drawn = Math.min(ConversationTitles.DRAWN, width);
        return label.substring(0, Math.min(drawn, label.length()));
    }

    /**
     * That an elided label opens on a word rather than on the tail of one.
     *
     * <p>Only where the title had a word boundary to offer in front of the cut: one unbroken token -
     * a build number, a pasted id - has none, and a cut between characters is then the only thing
     * left. Labels that were not elided are not this assertion's business.
     *
     * @param title the untruncated topic the label was made from
     * @param label the label the column would draw
     */
    private static void assertCutAtAWordBoundary(String title, String label) {
        if (label == null || !label.startsWith("…")) {
            return;
        }
        String kept = label.substring(1).replace("…", "");
        int at = title.indexOf(kept);
        assertThat(at).as("%s is not a run of %s", label, title).isGreaterThan(0);
        if (title.substring(0, at).contains(" ")) {
            assertThat(title.charAt(at - 1))
                    .as("%s opens mid-word in %s", label, title).isEqualTo(' ');
        }
    }

    /**
     * That an elision left the reader something to read besides the part that differs.
     *
     * <p>A label is a name before it is a discriminator, and "…21" is the discriminator with the name
     * removed. The check is deliberately about <em>words</em> rather than about a character count: a
     * count has to be tuned to the column, and the property the reader has is that the row still says
     * what the thread is about, which takes at least one word in front of the difference.
     *
     * <p>Titles with no whitespace in them - a pasted id, a build number - are exempt, for the same
     * reason {@link #assertCutAtAWordBoundary} exempts them: there is no word in the shared opening
     * to keep, so a cut between characters is the only thing on offer. Labels that were not elided
     * are not this assertion's business either.
     *
     * @param title the untruncated topic the label was made from
     * @param label the label the column would draw
     */
    private static void assertKeepsMoreThanTheDifference(String title, String label) {
        if (label == null || !label.startsWith("…") || !title.strip().contains(" ")) {
            return;
        }
        assertThat(label.substring(1).strip())
                .as("%s is what %s differs by, with the subject cut away", label, title)
                .contains(" ");
    }

    /**
     * The run the blind review photographed: threads that opened alike, and one topic that runs long.
     *
     * <p>Shared by the property sweep and by the regression test, because it is one corpus and two
     * questions about it - that it stays readable at every width, and that it stays readable at the
     * width it was photographed at.
     *
     * @return eighteen titles, sixteen of them identical
     */
    private static List<String> theReportedRun() {
        List<String> titles = new ArrayList<>(
                Collections.nCopies(16, "Java features added between version 8 and version 21"));
        titles.add("Java features added between version 8 and version 25");
        titles.add("Java features added between version 8 and version 25 and what each one changed for");
        return titles;
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
