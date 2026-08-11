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

        assertThat(tags(blocks)).containsExactly("pre");
        assertThat(text(blocks)).contains("var x = a ** b;");
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
