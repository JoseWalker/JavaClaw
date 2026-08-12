package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much mathematics this UI will claim to be able to typeset.
 *
 * <p>The interesting assertions here are the refusals. A partial LaTeX renderer that guesses at the
 * commands it does not know is worse than none at all: {@code \binom{n}{k}} silently drawn as
 * {@code nk} is an equation the reader has no way to tell is wrong, whereas the source they were
 * sent is at least true. So the contract is all or nothing per formula - every command in it is one
 * of the ones the renderer knows, or the whole formula stays the text the model wrote.
 *
 * <p>The other refusal is about dollars. {@code $} is a currency sign far more often than it is a
 * math delimiter, and a renderer that took "$5 and $10 for the JDK" for a formula would eat a
 * sentence and italicise it.
 */
class LatexTest {

    @Test
    @DisplayName("a formula becomes typeset elements rather than its own source")
    void rendersSimpleFormula() {
        Element math = render("E = mc^2");

        assertThat(math.getAttribute("class")).contains("jc-math");
        assertThat(math.getTextRecursively()).isEqualTo("E = mc2").doesNotContain("^");
        assertThat(tags(math)).contains("sup");
    }

    @Test
    @DisplayName("a subscript and a superscript on the same symbol are both drawn")
    void rendersBothScripts() {
        Element math = render("x_{i}^{2}");

        assertThat(tags(math)).contains("sub", "sup");
        assertThat(math.getTextRecursively()).isEqualTo("xi2");
    }

    @Test
    @DisplayName("a fraction is drawn as one, not as a slash")
    void rendersFraction() {
        Element math = render("\\frac{a}{b}");

        Element fraction = descendant(math, "jc-math__frac");
        assertThat(fraction.getTextRecursively()).isEqualTo("ab");
        assertThat(classes(fraction)).contains("jc-math__num", "jc-math__den");
    }

    @Test
    @DisplayName("a named symbol becomes the character it names")
    void rendersSymbols() {
        assertThat(render("\\alpha + \\beta \\times \\gamma").getTextRecursively())
                .isEqualTo("α + β × γ");
        assertThat(render("\\sum_{i=1}^{n} i").getTextRecursively()).startsWith("∑");
    }

    @Test
    @DisplayName("a command nobody here can draw refuses the whole formula")
    void refusesUnknownCommands() {
        // Every part of this one is supported except the first command, and that is enough: half a
        // binomial coefficient is not a binomial coefficient.
        assertThat(Latex.render("\\binom{n}{k} + 1", false)).isEmpty();
        assertThat(Latex.render("\\begin{matrix} a \\end{matrix}", true)).isEmpty();
    }

    @Test
    @DisplayName("an empty or oversized formula is refused rather than drawn")
    void refusesNonsense() {
        assertThat(Latex.render("   ", false)).isEmpty();
        assertThat(Latex.render("x".repeat(2000), false)).isEmpty();
    }

    @Test
    @DisplayName("display maths is marked as display, so it gets its own line")
    void marksDisplayMaths() {
        assertThat(render("a = b", true).getAttribute("class")).contains("jc-math--display");
        assertThat(render("a = b", false).getAttribute("class")).doesNotContain("jc-math--display");
    }

    @Test
    @DisplayName("a price is not a formula")
    void leavesCurrencyAlone() {
        String sentence = "It costs $5 and $10 more with support.";

        assertThat(text(Markdown.render(sentence))).isEqualTo(sentence);
    }

    @Test
    @DisplayName("inline maths inside a sentence is typeset where it stands")
    void rendersInlineMathsInProse() {
        Element paragraph = Markdown.render("Given $x^2$ we can solve it.").get(0);

        assertThat(paragraph.getTextRecursively()).isEqualTo("Given x2 we can solve it.");
        assertThat(descendant(paragraph, "jc-math")).isNotNull();
    }

    @Test
    @DisplayName("a display block on its own lines is a block, not a paragraph of dollars")
    void rendersDisplayBlockInProse() {
        List<Element> blocks = Markdown.render("""
                Then:

                $$
                a^2 + b^2 = c^2
                $$
                """);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).getAttribute("class")).contains("jc-math--display");
        assertThat(blocks.get(1).getTextRecursively()).doesNotContain("$");
    }

    @Test
    @DisplayName("a formula this renderer cannot draw is left as the source the model sent")
    void keepsUnsupportedSourceReadable() {
        String sentence = "Then $\\binom{n}{k}$ follows.";

        assertThat(text(Markdown.render(sentence))).isEqualTo(sentence);
    }

    @Test
    @DisplayName("dollars inside code are not maths")
    void leavesCodeAlone() {
        Element paragraph = Markdown.render("Run `echo $HOME` and $PATH stays.").get(0);

        assertThat(paragraph.getTextRecursively()).isEqualTo("Run echo $HOME and $PATH stays.");
    }

    @Test
    @DisplayName("nothing in a formula can become markup")
    void neverBuildsElementsFromTheFormula() {
        // Angle brackets are relational operators and stay allowed, so this is drawn rather than
        // refused - which makes it the case worth pinning. Every character comes back as a
        // character, and every element in the result was created by name from this renderer's own
        // vocabulary.
        Element math = render("<script>alert(1)</script>");

        assertThat(math.getTextRecursively()).isEqualTo("<script>alert(1)</script>");
        assertThat(tags(math)).containsOnly("i");
        assertThat(text(Markdown.render("$<b>x</b>$"))).contains("<b>x</b>");
    }

    // ------------------------------------------------------------------ helpers

    private static Element render(String source) {
        return render(source, false);
    }

    private static Element render(String source, boolean display) {
        Optional<Element> math = Latex.render(source, display);
        assertThat(math).as("[%s] should be renderable", source).isPresent();
        return math.orElseThrow();
    }

    /** The nearest element carrying a class name, the root itself included. */
    private static Element descendant(Element root, String className) {
        Element found = findIn(root, className);
        assertThat(found).as("an element classed .%s under <%s>", className, root.getTag()).isNotNull();
        return found;
    }

    private static Element findIn(Element root, String className) {
        if (hasClass(root, className)) {
            return root;
        }
        return root.getChildren()
                .filter(child -> !child.isTextNode())
                .map(child -> findIn(child, className))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasClass(Element element, String className) {
        String attribute = element.getAttribute("class");
        return attribute != null && List.of(attribute.split(" ")).contains(className);
    }

    /** Every class name on the element and everything under it, flattened. */
    private static List<String> classes(Element root) {
        List<String> names = new ArrayList<>();
        collectClasses(root, names);
        return names;
    }

    private static void collectClasses(Element root, List<String> into) {
        String attribute = root.getAttribute("class");
        if (attribute != null) {
            into.addAll(List.of(attribute.split(" ")));
        }
        root.getChildren().filter(child -> !child.isTextNode()).forEach(child -> collectClasses(child, into));
    }

    private static List<String> tags(Element root) {
        List<String> found = new ArrayList<>();
        collectTags(root, found);
        return found;
    }

    private static void collectTags(Element root, List<String> into) {
        root.getChildren().filter(child -> !child.isTextNode()).forEach(child -> {
            into.add(child.getTag());
            collectTags(child, into);
        });
    }

    private static String text(List<Element> blocks) {
        return blocks.stream().map(Element::getTextRecursively).reduce("", String::concat);
    }
}
