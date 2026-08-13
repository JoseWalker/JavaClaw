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

    // ------------------------------------------------------------------ GFM

    /**
     * The one inline construct in this grammar whose absence inverts a sentence.
     *
     * <p>Emphasis that fails to render costs a reader some typography. A retraction that fails to
     * render costs them the meaning: "~~Implicit locking of all fields~~" is the model saying that
     * is <em>not</em> how it works, and printed as its own tildes it reads as the affirmative claim
     * with punctuation round it. The stylesheet has painted {@code .jc-prose del} the whole time;
     * nothing ever produced one.
     *
     * <p>Fails on the original: {@code inline} dispatched on backslashes, backticks, {@code *},
     * {@code _}, {@code [} and the maths delimiters, and on nothing else, so the tildes fell
     * through to the plain-text buffer.
     */
    @Test
    @DisplayName("a struck-through run is struck through, not printed as its tildes")
    void rendersStrikethrough() {
        List<Element> blocks = Markdown.render("A ~~short~~ long paragraph.");

        assertThat(deepTags(blocks)).contains("del");
        assertThat(text(blocks)).isEqualTo("A short long paragraph.");
    }

    @Test
    @DisplayName("a lone tilde is a tilde, and an escaped pair is the characters themselves")
    void leavesUnpairedTildesAlone() {
        assertThat(text(Markdown.render("Roughly ~5 seconds"))).isEqualTo("Roughly ~5 seconds");
        assertThat(deepTags(Markdown.render("Roughly ~5 seconds"))).doesNotContain("del");
        // An opener with no closer is the characters it is, exactly as an unmatched ** is.
        assertThat(text(Markdown.render("A ~~stray opener"))).isEqualTo("A ~~stray opener");
        assertThat(text(Markdown.render("Literal \\~~text\\~~"))).isEqualTo("Literal ~~text~~");
        // Inside a code span the tildes are somebody's shell command, not markup.
        assertThat(text(Markdown.render("Run `rm ~~/tmp`"))).isEqualTo("Run rm ~~/tmp");
        assertThat(deepTags(Markdown.render("Run `rm ~~/tmp`"))).doesNotContain("del");
    }

    /**
     * A checklist is the shape an agent answers a "what is left to do" question in, and the boxes
     * are the answer: whether an item is done is carried by {@code [x]} against {@code [ ]} and by
     * nothing else in the line. Printed as literal brackets it is a list of items in an unknown
     * state, and the difference between done and not done is two spaces of punctuation.
     *
     * <p>Fails on the original: the bullet path rendered the item's text as it stood, so the DOM
     * held no {@code input} at all and the reader read "[x] done".
     */
    @Test
    @DisplayName("a task list draws its boxes, in the state the answer put them in")
    void rendersTaskLists() {
        List<Element> blocks = Markdown.render("- [x] done\n- [ ] todo\n");

        List<Element> boxes = descendants(blocks).filter(node -> "input".equals(tagOf(node))).toList();
        assertThat(boxes).hasSize(2);
        assertThat(boxes.get(0).getAttribute("type")).isEqualTo("checkbox");
        assertThat(boxes.get(0).hasAttribute("checked")).isTrue();
        assertThat(boxes.get(1).hasAttribute("checked")).isFalse();
        // A box the reader can tick would be an edit of the answer that nothing would record.
        assertThat(boxes).allSatisfy(box -> assertThat(box.hasAttribute("disabled")).isTrue());
        assertThat(text(blocks)).isEqualTo("donetodo");
    }

    @Test
    @DisplayName("a bracket that is not a task box stays the text it was")
    void leavesOrdinaryBracketsInItemsAlone() {
        assertThat(text(Markdown.render("- [note] see below\n")))
                .isEqualTo("[note] see below");
        assertThat(deepTags(Markdown.render("- [note] see below\n"))).doesNotContain("input");
        // An ordered item is not a task list in GFM, and inventing one here would silently rewrite
        // a numbered step whose text happens to open with a bracket.
        assertThat(deepTags(Markdown.render("1. [x] done\n"))).doesNotContain("input");
    }

    /**
     * A model writes most of its URLs bare, because in a chat window that is what a person does.
     *
     * <p>Fails on the original: only {@code [label](target)} produced an anchor, so an answer whose
     * whole point was "read this" left the reader a string to select and copy by hand - and the
     * stylesheet's link treatment never appeared on the one thing on screen that was a link.
     */
    @Test
    @DisplayName("a bare URL becomes the link it plainly is")
    void linksBareUrls() {
        List<Element> blocks = Markdown.render("Read https://example.com/docs for more.");

        Element anchor = anchor(blocks);
        assertThat(anchor.getAttribute("href")).isEqualTo("https://example.com/docs");
        assertThat(anchor.getAttribute("rel")).contains("noopener");
        assertThat(anchor.getTextRecursively()).isEqualTo("https://example.com/docs");
        // Nothing is added to or taken from what the model wrote.
        assertThat(text(blocks)).isEqualTo("Read https://example.com/docs for more.");
    }

    @Test
    @DisplayName("the sentence's own punctuation stays outside the link")
    void keepsTrailingPunctuationOutOfABareUrl() {
        assertThat(anchor(Markdown.render("See https://example.com.")).getAttribute("href"))
                .isEqualTo("https://example.com");
        assertThat(anchor(Markdown.render("See (https://example.com), then stop."))
                .getAttribute("href")).isEqualTo("https://example.com");
        // A closing bracket the URL itself opened belongs to the URL.
        assertThat(anchor(Markdown.render("See https://example.com/a_(b) now")).getAttribute("href"))
                .isEqualTo("https://example.com/a_(b)");
    }

    @Test
    @DisplayName("only the schemes an explicit link may carry are autolinked at all")
    void allowListsBareUrlSchemes() {
        // The same allow-list the bracket form is held to, applied to the form that has no brackets.
        assertThat(deepTags(Markdown.render("Try javascript:alert(1) now"))).doesNotContain("a");
        assertThat(deepTags(Markdown.render("Try data:text/html,<b>x</b> now"))).doesNotContain("a");
        assertThat(deepTags(Markdown.render("Try file:///etc/passwd now"))).doesNotContain("a");
        assertThat(text(Markdown.render("Try javascript:alert(1) now")))
                .isEqualTo("Try javascript:alert(1) now");
    }

    @Test
    @DisplayName("a URL inside a code span or a link label does not become a second link")
    void leavesUrlsThatAreAlreadySpokenFor() {
        assertThat(deepTags(Markdown.render("Call `curl https://example.com` first")))
                .doesNotContain("a");
        // An anchor inside an anchor is not markup a browser can make sense of, and the label is
        // already inside the link the model wrote.
        List<Element> nested = Markdown.render("[docs at https://example.com](https://example.org)");
        assertThat(deepTags(nested).stream().filter("a"::equals).toList()).hasSize(1);
        assertThat(anchor(nested).getAttribute("href")).isEqualTo("https://example.org");
    }

    /** The one anchor in a rendered answer. */
    private static Element anchor(List<Element> blocks) {
        return descendants(blocks)
                .filter(node -> "a".equals(tagOf(node)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing in this answer became a link"));
    }

    /** Every tag in the tree, however deep, which is where the inline constructs live. */
    private static List<String> deepTags(List<Element> blocks) {
        return descendants(blocks).map(MarkdownTest::tagOf).toList();
    }

    private static java.util.stream.Stream<Element> descendants(List<Element> blocks) {
        List<Element> found = new java.util.ArrayList<>();
        blocks.forEach(block -> collect(block, found));
        return found.stream();
    }

    private static void collect(Element element, List<Element> into) {
        into.add(element);
        element.getChildren().forEach(child -> collect(child, into));
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
