package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a code block is allowed to look like, and what it must never become.
 *
 * <p>A highlighter is a parser pointed at the most hostile text on the page: whatever the model
 * chose to put between two fences. Two properties matter more than any colour it picks. The first
 * is that it is total - every character of the source comes out the other side, in order, so a
 * reader can still copy the snippet and run it. The second is that it cannot mint markup: every
 * class name is a constant chosen here, and every scrap of the source is a text node.
 *
 * <p>The third thing pinned below is the one a highlighter usually gets wrong: a keyword inside a
 * string is not a keyword, and a quote inside a comment does not open one.
 */
class HighlightTest {

    @Test
    @DisplayName("java keywords, strings, numbers and comments are told apart")
    void scansJava() {
        List<Highlight.Token> tokens = Highlight.scan("""
                // a note
                var count = 42;
                String name = "Ada";
                """, "java");

        assertThat(kindOf(tokens, "// a note")).isEqualTo(Highlight.Kind.COMMENT);
        assertThat(kindOf(tokens, "var")).isEqualTo(Highlight.Kind.KEYWORD);
        assertThat(kindOf(tokens, "42")).isEqualTo(Highlight.Kind.NUMBER);
        assertThat(kindOf(tokens, "\"Ada\"")).isEqualTo(Highlight.Kind.STRING);
        assertThat(kindOf(tokens, "String")).isEqualTo(Highlight.Kind.TYPE);
    }

    @Test
    @DisplayName("a call is marked as one, so the shape of the code reads at a glance")
    void marksCalls() {
        List<Highlight.Token> tokens = Highlight.scan("start(name);", "java");

        assertThat(kindOf(tokens, "start")).isEqualTo(Highlight.Kind.FUNCTION);
        assertThat(kindOf(tokens, "name")).isEqualTo(Highlight.Kind.PLAIN);
    }

    @Test
    @DisplayName("an annotation is one token, not a stray at-sign beside a type")
    void marksAnnotations() {
        assertThat(kindOf(Highlight.scan("@Override\nvoid run() {}", "java"), "@Override"))
                .isEqualTo(Highlight.Kind.META);
    }

    @Test
    @DisplayName("a keyword inside a string is part of the string")
    void doesNotLookInsideStrings() {
        List<Highlight.Token> tokens = Highlight.scan("var s = \"class // not a comment\";", "java");

        assertThat(kindOf(tokens, "\"class // not a comment\"")).isEqualTo(Highlight.Kind.STRING);
        assertThat(tokens).noneMatch(token -> token.kind() == Highlight.Kind.COMMENT);
    }

    @Test
    @DisplayName("an escaped quote does not close the string it is inside")
    void honoursStringEscapes() {
        assertThat(kindOf(Highlight.scan("\"a \\\" b\" + c", "java"), "\"a \\\" b\""))
                .isEqualTo(Highlight.Kind.STRING);
    }

    @Test
    @DisplayName("a block comment that never closes still ends at the end of the source")
    void toleratesUnterminatedComment() {
        List<Highlight.Token> tokens = Highlight.scan("/* cut short", "java");

        assertThat(tokens).singleElement().satisfies(token -> {
            assertThat(token.kind()).isEqualTo(Highlight.Kind.COMMENT);
            assertThat(token.text()).isEqualTo("/* cut short");
        });
    }

    @Test
    @DisplayName("python is read with its own comment and string rules")
    void scansPython() {
        List<Highlight.Token> tokens = Highlight.scan("""
                # note
                def run(x):
                    return \"\"\"doc
                    string\"\"\"
                """, "python");

        assertThat(kindOf(tokens, "# note")).isEqualTo(Highlight.Kind.COMMENT);
        assertThat(kindOf(tokens, "def")).isEqualTo(Highlight.Kind.KEYWORD);
        assertThat(tokens).anyMatch(token -> token.kind() == Highlight.Kind.STRING
                && token.text().contains("doc") && token.text().contains("string"));
    }

    @Test
    @DisplayName("sql keywords are recognised whatever case they were written in")
    void scansSqlCaseInsensitively() {
        List<Highlight.Token> tokens = Highlight.scan("select id from t -- all\n", "sql");

        assertThat(kindOf(tokens, "select")).isEqualTo(Highlight.Kind.KEYWORD);
        assertThat(kindOf(tokens, "-- all")).isEqualTo(Highlight.Kind.COMMENT);
    }

    @Test
    @DisplayName("a json property is told apart from the value beside it")
    void marksJsonProperties() {
        List<Highlight.Token> tokens = Highlight.scan("{\"name\": \"Ada\", \"age\": 36}", "json");

        assertThat(kindOf(tokens, "\"name\"")).isEqualTo(Highlight.Kind.TYPE);
        assertThat(kindOf(tokens, "\"Ada\"")).isEqualTo(Highlight.Kind.STRING);
    }

    @Test
    @DisplayName("a language nobody here can read is left exactly as it was written")
    void leavesUnknownLanguagesAlone() {
        String source = "IDENTIFICATION DIVISION.\nPROGRAM-ID. HELLO.\n";

        assertThat(Highlight.scan(source, "cobol"))
                .singleElement()
                .isEqualTo(new Highlight.Token(Highlight.Kind.PLAIN, source));
        assertThat(Highlight.scan(source, null))
                .singleElement()
                .isEqualTo(new Highlight.Token(Highlight.Kind.PLAIN, source));
    }

    @Test
    @DisplayName("every character of the source survives, in the order it was written")
    void losesNothing() {
        String source = """
                /* header */
                public class A {          // trailing
                    String s = "a\\"b";   /* mid */ int n = 0x1F;
                }
                """;

        assertThat(joined(Highlight.scan(source, "java"))).isEqualTo(source);
        assertThat(joined(Highlight.scan(source, "python"))).isEqualTo(source);
        assertThat(joined(Highlight.scan(source, "json"))).isEqualTo(source);
    }

    @Test
    @DisplayName("one element per source line, carrying no newline of its own")
    void drawsOneElementPerLine() {
        List<Element> lines = Highlight.lines("a\n\nb\n", "java");

        // The trailing newline closes the last line rather than opening an empty one.
        assertThat(lines).hasSize(3);
        assertThat(lines).allMatch(line -> "jc-code__line".equals(line.getAttribute("class")));
        // No line carries a break of its own: the elements are what separates them, which is what
        // lets the copy control rebuild the snippet by joining them.
        assertThat(lines).noneMatch(line -> line.getTextRecursively().contains("\n"));
        assertThat(lines.get(1).getTextRecursively()).isEmpty();
    }

    @Test
    @DisplayName("a token that spans lines is cut at the line, not carried across it")
    void splitsTokensAtLineEnds() {
        List<Element> lines = Highlight.lines("/* one\n   two */", "java");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getTextRecursively()).isEqualTo("/* one");
        assertThat(lines.get(1).getTextRecursively()).isEqualTo("   two */");
        assertThat(classesIn(lines.get(1))).contains("jc-tok jc-tok--comment");
    }

    @Test
    @DisplayName("markup in the source is text, and every class name comes from this file")
    void neverBuildsElementsFromTheSource() {
        List<Element> lines = Highlight.lines("var x = \"</span><script>\";", "java");

        assertThat(lines.get(0).getTextRecursively()).contains("</span><script>");
        assertThat(tagsIn(lines.get(0))).containsOnly("span");
        assertThat(classesIn(lines.get(0))).allMatch(name -> name.startsWith("jc-tok jc-tok--"));
    }

    private static Highlight.Kind kindOf(List<Highlight.Token> tokens, String text) {
        return tokens.stream()
                .filter(token -> token.text().equals(text))
                .map(Highlight.Token::kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no token reads exactly [" + text + "] in " + tokens));
    }

    private static String joined(List<Highlight.Token> tokens) {
        return tokens.stream().map(Highlight.Token::text).reduce("", String::concat);
    }

    private static List<String> tagsIn(Element line) {
        return line.getChildren().filter(child -> !child.isTextNode()).map(Element::getTag).toList();
    }

    private static List<String> classesIn(Element line) {
        return line.getChildren()
                .filter(child -> !child.isTextNode())
                .map(child -> child.getAttribute("class"))
                .toList();
    }
}
