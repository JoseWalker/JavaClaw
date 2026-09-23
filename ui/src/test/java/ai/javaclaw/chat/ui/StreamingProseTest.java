package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much of a half-arrived answer may be turned into markup.
 *
 * <p>An answer is rendered while it is still being written, so the question this pins down is not
 * what the markup should be - {@link MarkdownTest} covers that - but <em>when</em> it is safe to
 * commit to it. A block that has been rendered and appended can no longer be revised by a later
 * token, so settling one early is not a cosmetic mistake: it freezes a half-read table or an
 * unfinished sentence into the transcript as though the model had meant it.
 *
 * <p>The rule being tested is therefore deliberately pessimistic. Text stays plain until nothing
 * that can still arrive could change how it parses.
 */
class StreamingProseTest {

    @Test
    @DisplayName("a sentence still being written settles nothing and stays in the tail")
    void holdsAnUnfinishedParagraph() {
        StreamingProse prose = new StreamingProse();

        assertThat(prose.absorb("The three things that matter")).isEmpty();
        assertThat(prose.tailText()).isEqualTo("The three things that matter");
    }

    @Test
    @DisplayName("a blank line closes the paragraph above it, which is then final")
    void settlesParagraphAtBlankLine() {
        StreamingProse prose = new StreamingProse();
        prose.absorb("First point.\n");

        List<Element> settled = prose.absorb("\nSecond");

        assertThat(tags(settled)).containsExactly("p");
        assertThat(settled.get(0).getTextRecursively()).isEqualTo("First point.");
        assertThat(prose.tailText()).isEqualTo("Second");
    }

    @Test
    @DisplayName("a blank line inside an open fence settles nothing - the fence owns it")
    void holdsBlankLinesInsideAnOpenFence() {
        StreamingProse prose = new StreamingProse();

        List<Element> settled = prose.absorb("```java\nvar a = 1;\n\nvar b = 2;\n");

        assertThat(settled).isEmpty();
        assertThat(prose.tailText()).contains("var b = 2;");
    }

    @Test
    @DisplayName("a fence settles once it is closed")
    void settlesClosedFence() {
        StreamingProse prose = new StreamingProse();
        prose.absorb("```java\nvar a = 1;\n```\n");

        List<Element> settled = prose.absorb("\nAnd then");

        assertThat(tags(settled)).containsExactly("figure");
        assertThat(settled.get(0).getTextRecursively()).contains("var a = 1;");
    }

    @Test
    @DisplayName("a table does not settle while another row could still arrive")
    void holdsATableThatCouldStillGrow() {
        StreamingProse prose = new StreamingProse();

        List<Element> settled = prose.absorb("| Tool | Language |\n| --- | --- |\n| Gradle | Groovy |\n");

        assertThat(settled).isEmpty();
    }

    @Test
    @DisplayName("nothing is rendered twice: what settled is not repeated when the answer ends")
    void neverEmitsTheSameBlockTwice() {
        StreamingProse prose = new StreamingProse();
        List<Element> first = prose.absorb("Alpha.\n\nBeta.");

        List<Element> rest = prose.finish();

        assertThat(tags(first)).containsExactly("p");
        assertThat(first.get(0).getTextRecursively()).isEqualTo("Alpha.");
        assertThat(tags(rest)).containsExactly("p");
        assertThat(rest.get(0).getTextRecursively()).isEqualTo("Beta.");
        assertThat(prose.tailText()).isEmpty();
    }

    // ------------------------------------------------------- the tail as typeset

    /**
     * The defect these cover, stated once.
     *
     * <p>An answer of one item per line - which is what an agent produces whenever it is asked for
     * a list - contains no blank line until it ends, so nothing settles and the whole answer is the
     * tail for the whole turn. While the tail was drawn as its own source, that meant the reader
     * watched an entire answer arrive as {@code **Module System**} and backticked identifiers, and
     * then watched it reflow into type at the last moment. Block structure genuinely cannot be
     * decided early; inline structure can, because no inline construct in this grammar spans a line.
     */
    @Test
    @DisplayName("a bolded line is typeset while it is still the tail, not printed as its asterisks")
    void typesetsAnUnsettledLine() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("**Module System** partitions the JDK.");

        assertThat(tags(prose.tail())).contains("strong");
        assertThat(text(prose.tail())).isEqualTo("Module System partitions the JDK.");
    }

    @Test
    @DisplayName("a one-per-line list is typeset line by line, though it never reaches a blank line")
    void typesetsAListThatNeverSettles() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("**Records** carry data.\n**Sealed types** close a hierarchy.\n**Virtual");

        assertThat(prose.absorb("")).isEmpty();
        assertThat(tags(prose.tail())).containsSubsequence("strong", "strong");
        // The run still being typed is the one exception, and it is one run rather than nine: its
        // closing delimiter has not arrived, so there is nothing yet to say it was emphasis.
        assertThat(text(prose.tail())).isEqualTo(
                "Records carry data.\nSealed types close a hierarchy.\n**Virtual");
    }

    @Test
    @DisplayName("a code span in the tail is code, not backticks")
    void typesetsACodeSpanInTheTail() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Call `Thread.ofVirtual()` to start one");

        assertThat(tags(prose.tail())).contains("code");
        assertThat(text(prose.tail())).doesNotContain("`");
    }

    /**
     * The fence half of the healing defect, and the reason the old assertion here has been rewritten
     * rather than deleted.
     *
     * <p>What this test used to pin was that the tail of an open fence is one run of plain text.
     * That is true of its <em>contents</em> and it was being applied to the delimiter as well: an
     * audit of the running build found {@code ```bash} on screen as a line of body prose with the
     * snippet under it in the 15px proportional font, {@code pre} count zero, for 430ms and again
     * for 420ms in one answer. The reference product opens the code panel on the opening fence and
     * never renders the delimiter at all.
     *
     * <p>The property that mattered is kept and is asserted below: a backtick pair and a
     * {@code **} run inside the fence are somebody's source, not markup. What changes is where
     * they are drawn - inside a code block, which is what the model said they were.
     *
     * <p>Fails on the original at the first assertion: the tail was a text node carrying the
     * literal fence line.
     */
    @Test
    @DisplayName("an open fence is drawn as code from the fence line, not as a line of backticks")
    void healsAnOpenFenceInTheTail() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Here:\n\n```java\nvar name = `x` + **y**;\n");

        assertThat(tags(prose.tail())).containsExactly("figure");
        assertThat(deepTags(prose.tail())).contains("pre", "code");
        // The delimiter is pure syntax and carries nothing the reader is meant to see.
        assertThat(text(prose.tail())).doesNotContain("```");
        // Inside the fence nothing is markup, which is what this test always existed to say.
        assertThat(text(prose.tail())).contains("var name = `x` + **y**;");
        assertThat(deepTags(prose.tail())).doesNotContain("strong", "em");
    }

    @Test
    @DisplayName("a fence that has not named a language still opens the block it opened")
    void healsAnOpenFenceWithNoLanguage() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("```\nplain\n");

        assertThat(tags(prose.tail())).containsExactly("figure");
        assertThat(text(prose.tail())).doesNotContain("```").contains("plain");
    }

    /**
     * The table half, and the worse of the two.
     *
     * <p>A delimiter row is pure syntax: {@code | :--- | :--- |} says which way the columns line up
     * and carries no content whatsoever. Measured on the running build, a 12-row table showed its
     * pipes as paragraph text for 1.81s and a 20-row one for 4.27s, the delimiter row included. The
     * reference never showed a delimiter row at all.
     *
     * <p>Fails on the original: the tail was typeset with the inline grammar only, which has no
     * notion of a row, so every pipe reached the screen.
     */
    @Test
    @DisplayName("a table in the tail is a table, and its delimiter row is never on screen")
    void healsATableInTheTail() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("| Feature | JDK |\n| :--- | :--- |\n| Records | 16 |\n");

        assertThat(deepTags(prose.tail())).contains("table", "thead", "tbody", "th", "td");
        assertThat(text(prose.tail())).doesNotContain("|", ":---");
        assertThat(text(prose.tail())).contains("Feature", "Records");
    }

    @Test
    @DisplayName("a lead-in sentence above a table is still prose, and the table under it is a table")
    void healsATableUnderItsLeadIn() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Here is what I found:\n| Feature | JDK |\n| --- | --- |\n| Records | 16 |\n");

        assertThat(tags(prose.tail())).containsSubsequence("#text", "div");
        assertThat(text(prose.tail())).contains("Here is what I found:").doesNotContain("|");
    }

    @Test
    @DisplayName("a delimiter row still being typed is held back rather than printed as prose")
    void holdsBackAHalfTypedDelimiterRow() {
        StreamingProse prose = new StreamingProse();

        // The header has landed and the delimiter row is two characters in. There is no table yet -
        // the row that would prove one is incomplete - and what is on screen must not be its syntax.
        prose.absorb("| Feature | JDK |\n| :-");

        assertThat(text(prose.tail())).doesNotContain(":-");
        assertThat(text(prose.tail())).contains("Feature");
    }

    @Test
    @DisplayName("a row of pipes that never becomes a table is still shown as the text it is")
    void keepsProseThatMerelyContainsPipes() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Use a | b to pipe, then read on");

        assertThat(text(prose.tail())).isEqualTo("Use a | b to pipe, then read on");
    }

    @Test
    @DisplayName("prose after a closed fence is typeset again, though neither part settled")
    void resumesTypesettingAfterAFenceCloses() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("```\nliteral **text**\n```\nand **then** prose");

        assertThat(text(prose.tail())).contains("literal **text**").contains("and then prose");
        assertThat(tags(prose.tail())).contains("strong");
    }

    /**
     * The tail is model output on its way into the document, so it is held to the same allow-list as
     * a finished answer rather than to a looser one because it is temporary.
     */
    @Test
    @DisplayName("a javascript: target in the tail is its label, never an href")
    void refusesADangerousTargetInTheTail() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Read [the docs](javascript:alert(1)) for more");

        assertThat(tags(prose.tail())).doesNotContain("a");
        assertThat(text(prose.tail())).contains("the docs").doesNotContain("javascript");
    }

    @Test
    @DisplayName("what has settled is not in the tail as well")
    void keepsTheTailBehindTheSettledBoundary() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("**First** point.\n\n**Second");

        assertThat(text(prose.tail())).isEqualTo("**Second");
    }

    @Test
    @DisplayName("an answer cut off mid-block still renders what it had")
    void finishesAnUnterminatedBlock() {
        StreamingProse prose = new StreamingProse();
        prose.absorb("```java\nvar x = 1;\n");

        assertThat(tags(prose.finish())).containsExactly("figure");
    }

    @Test
    @DisplayName("the whole answer, streamed one character at a time, renders as if it arrived whole")
    void matchesWholeRenderWhenStreamedByCharacter() {
        String answer = """
                Here is what I found:

                | Tool | Language |
                | --- | --- |
                | Gradle | Groovy |

                ```java
                var x = 1;
                ```

                That is all.
                """;

        StreamingProse prose = new StreamingProse();
        StringBuilder streamed = new StringBuilder();
        for (String character : answer.split("")) {
            prose.absorb(character).forEach(block -> streamed.append(tagOf(block)));
        }
        prose.finish().forEach(block -> streamed.append(tagOf(block)));

        String whole = Markdown.render(answer).stream()
                .map(StreamingProseTest::tagOf).reduce("", String::concat);
        assertThat(streamed.toString()).isEqualTo(whole);
    }

    private static List<String> tags(List<Element> elements) {
        return elements.stream().map(StreamingProseTest::tagOf).toList();
    }

    /** Every tag in the tail, however deep - a code block and a table are both several levels. */
    private static List<String> deepTags(List<Element> elements) {
        List<Element> found = new java.util.ArrayList<>();
        elements.forEach(element -> collect(element, found));
        return found.stream().map(StreamingProseTest::tagOf).toList();
    }

    private static void collect(Element element, List<Element> into) {
        into.add(element);
        if (!element.isTextNode()) {
            element.getChildren().forEach(child -> collect(child, into));
        }
    }

    /**
     * What the reader would see, with the markup taken back out again. Asserting on this rather
     * than on the tree is what makes "no asterisks reach the screen" a testable sentence.
     */
    private static String text(List<Element> elements) {
        return elements.stream()
                .map(element -> element.isTextNode() ? element.getText() : element.getTextRecursively())
                .reduce("", String::concat);
    }

    private static String tagOf(Element element) {
        return element.isTextNode() ? "#text" : element.getTag();
    }
}
