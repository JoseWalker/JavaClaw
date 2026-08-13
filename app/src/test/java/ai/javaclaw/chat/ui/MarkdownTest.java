package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the transcript is allowed to do with text that came out of a language model.
 *
 * <p>Two things are being pinned here. The first is that the markup the stylesheet is written for
 * actually arrives - a numbered list is a list, a code fence is a {@code <pre>} - because the
 * failure mode before this renderer existed was literal asterisks on screen. The second is that
 * nothing in an answer can become markup of its own choosing: the parser builds elements by name
 * and only ever sets text, and the one attribute it takes from the input is allow-listed.
 */
class MarkdownTest {

    @Test
    @DisplayName("emphasis, code spans and headings become elements rather than punctuation")
    void rendersInlineMarkup() {
        List<Element> blocks = Markdown.render("## Title\n\nA **bold** word and `code`.");

        assertThat(tags(blocks)).containsExactly("h2", "p");
        assertThat(tags(blocks.get(1).getChildren().toList())).contains("strong", "code");
        assertThat(text(blocks)).doesNotContain("**", "`");
    }

    /**
     * The conversation column describes each row with the opening of its first message, and it was
     * describing them with the source. A screen reader was reading "hash hash Java Ecosystem" and
     * "backtick backtick backtick java pub" aloud as the summary of a conversation - punctuation
     * that exists to be rendered, read out as words to the one reader who cannot see it rendered.
     * The same projection is what a tooltip wants, for the same reason.
     */
    @Test
    @DisplayName("a description of an answer reads as prose, not as its punctuation")
    void projectsMarkdownToPlainText() {
        String projected = Markdown.plainText("""
                ## Java Ecosystem

                A **bold** claim about `virtual threads` and [the docs](https://example.com).
                """);

        assertThat(projected).isEqualTo(
                "Java Ecosystem A bold claim about virtual threads and the docs.");
    }

    @Test
    @DisplayName("code blocks are left out of a description rather than read out a token at a time")
    void leavesCodeOutOfPlainText() {
        String projected = Markdown.plainText("""
                Here is how:

                ```java
                public static void main(String[] args) {}
                ```

                That is all.
                """);

        assertThat(projected).isEqualTo("Here is how: That is all.");
    }

    @Test
    @DisplayName("list and quote markers become the text they decorate")
    void flattensBlockMarkers() {
        String projected = Markdown.plainText("- First\n- Second\n\n> Quoted\n\n1. Numbered\n");

        assertThat(projected).isEqualTo("First Second Quoted Numbered");
    }

    @Test
    @DisplayName("a description of nothing is nothing, not a stray fragment")
    void plainTextOfNothing() {
        assertThat(Markdown.plainText(null)).isEmpty();
        assertThat(Markdown.plainText("   \n\n ")).isEmpty();
        // Every line was a fence or its contents, so there is no prose to project. Empty is the
        // honest answer; the caller falls back to the conversation's title.
        assertThat(Markdown.plainText("```java\nint x = 1;\n```")).isEmpty();
    }

    @Test
    @DisplayName("a numbered list is a list, with its items intact")
    void rendersOrderedList() {
        List<Element> blocks = Markdown.render("1. **Scalability**: millions of tasks\n2. Simplicity\n");

        assertThat(tags(blocks)).containsExactly("ol");
        assertThat(tags(blocks.get(0).getChildren().toList())).containsExactly("li", "li");
        assertThat(text(blocks)).contains("Scalability", "Simplicity");
    }

    @Test
    @DisplayName("an indented item nests inside the one above it")
    void nestsIndentedItems() {
        List<Element> blocks = Markdown.render("- Gradle\n  - Often used for Android\n- Maven\n");

        Element list = blocks.get(0);
        assertThat(tags(list.getChildren().toList())).containsExactly("li", "li");
        assertThat(tags(list.getChildren().findFirst().orElseThrow().getChildren().toList()))
                .contains("ul");
    }

    @Test
    @DisplayName("a fenced block keeps its text verbatim, even when the answer was cut short")
    void rendersUnterminatedCodeFence() {
        List<Element> blocks = Markdown.render("```java\nvar x = a ** b;\n");

        assertThat(tags(blocks)).containsExactly("figure");
        assertThat(text(blocks)).contains("var x = a ** b;");
    }

    @Test
    @DisplayName("a code block is labelled and comes with a way to take it away")
    void framesCodeBlocks() {
        Element figure = Markdown.render("```java\nvar x = 1;\n```").get(0);

        assertThat(figure.getAttribute("class")).isEqualTo("jc-code");
        Element head = child(figure, "figcaption");
        assertThat(child(head, "span").getTextRecursively()).isEqualTo("java");

        Element copy = child(head, "button");
        assertThat(copy.getAttribute("type")).isEqualTo("button");
        assertThat(copy.getAttribute("aria-label")).isEqualTo("Copy code");
        // The control carries no behaviour of its own: it is claimed by the one delegated listener
        // the transcript installs, which is what keeps a copy off the server entirely.
        assertThat(copy.getAttribute("data-jc-copy")).isEqualTo("code");
    }

    @Test
    @DisplayName("underscores inside an identifier are not emphasis")
    void leavesSnakeCaseAlone() {
        assertThat(text(Markdown.render("Call some_long_identifier now."))).contains("some_long_identifier");
    }

    @Test
    @DisplayName("an unclosed delimiter is printed, not treated as an opener")
    void leavesUnmatchedDelimitersAsText() {
        assertThat(text(Markdown.render("2 * 3 and a stray ** here"))).isEqualTo("2 * 3 and a stray ** here");
    }

    @Test
    @DisplayName("a link keeps its label but only an allow-listed scheme becomes an href")
    void allowListsLinkSchemes() {
        List<Element> safe = Markdown.render("See [OpenJDK](https://openjdk.org).");
        Element anchor = safe.get(0).getChildren()
                .filter(child -> "a".equals(tagOf(child))).findFirst().orElseThrow();
        assertThat(anchor.getAttribute("href")).isEqualTo("https://openjdk.org");
        assertThat(anchor.getAttribute("rel")).contains("noopener");

        List<Element> hostile = Markdown.render("Click [here](javascript:alert(1)).");
        assertThat(tags(hostile.get(0).getChildren().toList())).doesNotContain("a");
        assertThat(text(hostile)).contains("here");
    }

    @Test
    @DisplayName("markup in the answer is text, not markup")
    void neverBuildsElementsFromTheAnswer() {
        List<Element> blocks = Markdown.render("<script>alert(1)</script> and <b>bold</b>");

        assertThat(tags(blocks)).containsExactly("p");
        assertThat(tags(blocks.get(0).getChildren().toList())).doesNotContain("script", "b");
        assertThat(text(blocks)).contains("<script>alert(1)</script>");
    }

    @Test
    @DisplayName("nothing to render is nothing at all")
    void rendersNothingForEmptyInput() {
        assertThat(Markdown.render(null)).isEmpty();
        assertThat(Markdown.render("   \n  ")).isEmpty();
    }

    @Test
    @DisplayName("a pipe table becomes a table, with its header apart from its rows")
    void rendersTable() {
        List<Element> blocks = Markdown.render("""
                | Tool | Language |
                | --- | --- |
                | Gradle | Groovy |
                | Maven | XML |
                """);

        assertThat(tags(blocks)).containsExactly("div");
        Element table = onlyChild(blocks.get(0));
        assertThat(table.getTag()).isEqualTo("table");
        assertThat(tags(table.getChildren().toList())).containsExactly("thead", "tbody");

        Element headerRow = onlyChild(child(table, "thead"));
        assertThat(tags(headerRow.getChildren().toList())).containsExactly("th", "th");
        assertThat(headerRow.getTextRecursively()).contains("Tool", "Language");

        List<Element> bodyRows = child(table, "tbody").getChildren().toList();
        assertThat(tags(bodyRows)).containsExactly("tr", "tr");
        assertThat(tags(bodyRows.get(0).getChildren().toList())).containsExactly("td", "td");
        assertThat(text(blocks)).doesNotContain("|", "---");
    }

    @Test
    @DisplayName("a row of pipes with no delimiter under it is a paragraph, not a table")
    void refusesTableWithoutDelimiterRow() {
        List<Element> blocks = Markdown.render("| not | a table |\n| still | not |\n");

        assertThat(tags(blocks)).containsExactly("p");
    }

    @Test
    @DisplayName("a delimiter row that does not match the header is refused rather than guessed at")
    void refusesTableWithMismatchedColumnCount() {
        List<Element> blocks = Markdown.render("| a | b | c |\n| --- | --- |\n| 1 | 2 | 3 |\n");

        assertThat(tags(blocks)).containsExactly("p");
    }

    @Test
    @DisplayName("a cell carries inline markup, and an escaped pipe stays a pipe")
    void rendersCellContent() {
        List<Element> blocks = Markdown.render("""
                | Field | Note |
                | --- | --- |
                | **id** | a \\| b |
                """);

        List<Element> cells =
                onlyChild(child(onlyChild(blocks.get(0)), "tbody")).getChildren().toList();
        assertThat(tags(cells.get(0).getChildren().toList())).containsExactly("strong");
        assertThat(cells.get(1).getTextRecursively()).isEqualTo("a | b");
    }

    @Test
    @DisplayName("a table still arriving renders the rows it has")
    void rendersPartialTableWhileStreaming() {
        List<Element> blocks = Markdown.render("| Tool | Language |\n| --- | --- |\n");

        assertThat(tags(blocks)).containsExactly("div");
        assertThat(child(onlyChild(blocks.get(0)), "tbody").getChildCount()).isZero();
    }

    @Test
    @DisplayName("a fence names its language on the element, so it can be highlighted and labelled")
    void keepsCodeFenceLanguage() {
        Element code = codeOf(Markdown.render("```java\nvar x = 1;\n```").get(0));

        assertThat(code.getAttribute("class")).isEqualTo("language-java");
        assertThat(code.getAttribute("data-language")).isEqualTo("java");
    }

    @Test
    @DisplayName("an info string that is not a language name never reaches the class attribute")
    void refusesHostileFenceInfo() {
        Element code = codeOf(Markdown.render("```\" onload=\"alert(1)\nx\n```").get(0));

        assertThat(code.getAttribute("class")).isNull();
        assertThat(code.getAttribute("data-language")).isNull();
        assertThat(code.getTextRecursively()).contains("x");
    }

    @Test
    @DisplayName("a table directly under a sentence is still a table")
    void startsTableWithoutABlankLineAbove() {
        List<Element> blocks = Markdown.render("""
                Here is what I found:
                | Tool | Language |
                | --- | --- |
                | Gradle | Groovy |
                """);

        assertThat(tags(blocks)).containsExactly("p", "div");
    }

    /**
     * A table is the one block whose width is decided by its content rather than by the measure, so
     * it is the one block that can be asked to hold more than the column has room for. Measured on
     * the running build with a twelve-column table injected into a real answer: every column
     * collapsed to 45px and each heading wrapped to four lines, inside a container that reported no
     * overflow at all. Nothing was clipped and nothing scrolled - the table simply became
     * unreadable, which is the failure mode a still image cannot show.
     *
     * <p>The scroll belongs to a wrapper rather than to the table. Making the table itself the
     * scroll container costs it its row alignment - a table with {@code display: block} no longer
     * sizes its header and its body to the same columns - which is what the stylesheet's own note
     * on this rule warns about. A wrapper leaves the table a table.
     */
    @Test
    @DisplayName("a table is wrapped in a container that can scroll it, rather than squeezed")
    void wrapsTableInItsOwnScroller() {
        List<Element> blocks = Markdown.render("""
                | Tool | Language |
                | --- | --- |
                | Gradle | Groovy |
                """);

        Element wrapper = blocks.get(0);
        assertThat(wrapper.getTag()).isEqualTo("div");
        assertThat(wrapper.getAttribute("class")).isEqualTo("jc-table");
        assertThat(onlyChild(wrapper).getTag()).isEqualTo("table");
    }

    /** The {@code <code>} inside a code figure, which is two levels down from the block. */
    private static Element codeOf(Element figure) {
        return child(child(figure, "pre"), "code");
    }

    private static Element child(Element parent, String tag) {
        return parent.getChildren()
                .filter(candidate -> tag.equals(tagOf(candidate)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no <" + tag + "> under <" + tagOf(parent) + ">"));
    }

    private static Element onlyChild(Element parent) {
        assertThat(parent.getChildCount()).isEqualTo(1);
        return parent.getChild(0);
    }

    private static List<String> tags(List<Element> elements) {
        return elements.stream().map(MarkdownTest::tagOf).toList();
    }

    /** A text node has no tag, and asking one for its tag throws rather than answering. */
    private static String tagOf(Element element) {
        return element.isTextNode() ? "#text" : element.getTag();
    }

    private static String text(List<Element> elements) {
        return elements.stream().map(Element::getTextRecursively).reduce("", String::concat);
    }
}
