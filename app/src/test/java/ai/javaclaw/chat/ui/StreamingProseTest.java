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

    @Test
    @DisplayName("an open fence keeps its backticks: inside it they are code, not markup")
    void leavesAnOpenFenceAsWritten() {
        StreamingProse prose = new StreamingProse();

        prose.absorb("Here:\n\n```java\nvar name = `x` + **y**;\n");

        // The paragraph above the fence settled at the blank line, so what is left is the fence and
        // its contents - which must arrive on screen as the characters the model typed.
        assertThat(tags(prose.tail())).containsOnly("#text");
        assertThat(text(prose.tail())).contains("`x` + **y**;");
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
