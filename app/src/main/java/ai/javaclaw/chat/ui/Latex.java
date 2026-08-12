package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * As much LaTeX as this UI can typeset honestly, and not one command more.
 *
 * <p>An agent asked about anything quantitative answers in LaTeX, because that is what its training
 * data is written in. Rendered as plain text, {@code \frac{\partial f}{\partial x}} is not an
 * equation, it is a line of punctuation - so the choice is not between good maths and this, it is
 * between this and none.
 *
 * <h2>Why it is written here</h2>
 *
 * <p>KaTeX and MathJax are JavaScript packages, and this UI has no frontend build to load one into.
 * What is here is a small typesetter over HTML: variables in italic, scripts as {@code <sup>} and
 * {@code <sub>}, fractions and radicals as boxes the stylesheet stacks, and a table of named
 * symbols that become the characters they name.
 *
 * <h2>All of a formula, or none of it</h2>
 *
 * <p>The rule that matters is the refusal. Every command in a formula is one this file knows, or
 * {@link #render} returns nothing at all and the caller prints the source the model sent. Rendering
 * the parts it understands and dropping the rest would produce an equation that is wrong in a way
 * the reader cannot see: {@code \binom{n}{k}} drawn as {@code nk} says something false with
 * confidence, while the source at least says something true.
 *
 * <p>The ceiling is therefore worth stating plainly. There is no support for environments -
 * matrices, aligned systems, cases - for limits set above and below an operator (they are drawn
 * beside it), or for line breaks inside a formula. Each of those refuses the formula rather than
 * approximating it.
 *
 * <h2>Why it cannot inject markup</h2>
 *
 * <p>Every element is created by tag name from a constant in this file, every class name is a
 * constant here, and every character of the formula reaches the page through
 * {@link Element#setText}. A formula is model output and is treated as such throughout: the parser
 * has a depth ceiling, a length ceiling, and an allow-list of characters, and anything outside them
 * is a refusal rather than a best effort.
 */
final class Latex {

    /**
     * Ceiling on a formula. Past this it is not an equation a reader is going to take in, and a
     * parser driven by model output needs a bound that does not depend on the model being sensible.
     */
    private static final int MAX_LENGTH = 1000;

    /** Ceiling on nesting. A fraction inside a root inside a script is real; forty levels is not. */
    private static final int MAX_DEPTH = 8;

    /** The characters that mean themselves. Anything not here, and not a command, is a refusal. */
    private static final String PUNCTUATION = "+-*/=<>(),.[]|!:;'?%\"";

    /**
     * The named symbols, as pairs of command and character.
     *
     * <p>An allow-list, and the reason a formula can be refused at all. Written as a table rather
     * than as a switch so that adding a symbol is one line and cannot change how anything else is
     * parsed.
     */
    private static final Map<String, String> SYMBOLS = symbols("""
            alpha α beta β gamma γ delta δ epsilon ε varepsilon ε zeta ζ eta η
            theta θ vartheta ϑ iota ι kappa κ lambda λ mu μ nu ν xi ξ
            pi π rho ρ sigma σ tau τ upsilon υ phi φ varphi φ chi χ
            psi ψ omega ω Gamma Γ Delta Δ Theta Θ Lambda Λ Xi Ξ
            Pi Π Sigma Σ Upsilon Υ Phi Φ Psi Ψ Omega Ω
            times × div ÷ cdot · pm ± mp ∓ ast ∗ star ⋆ circ ∘
            bullet • oplus ⊕ otimes ⊗ leq ≤ le ≤ geq ≥ ge ≥
            neq ≠ ne ≠ approx ≈ equiv ≡ sim ∼ simeq ≃ cong ≅ propto ∝
            ll ≪ gg ≫ in ∈ notin ∉ subset ⊂ subseteq ⊆ supset ⊃ supseteq ⊇
            cup ∪ cap ∩ emptyset ∅ setminus ∖ forall ∀ exists ∃ neg ¬ land ∧
            lor ∨ sum ∑ prod ∏ int ∫ iint ∬ oint ∮ bigcup ⋃ bigcap ⋂
            to → rightarrow → longrightarrow ⟶ leftarrow ← Rightarrow ⇒ Leftarrow ⇐ leftrightarrow ↔
            Leftrightarrow ⇔ mapsto ↦ implies ⇒ iff ⇔ infty ∞ partial ∂ nabla ∇
            ldots … dots … cdots ⋯ vdots ⋮ ddots ⋱ prime ′ degree ° angle ∠
            perp ⊥ parallel ∥ hbar ℏ ell ℓ Re ℜ Im ℑ aleph ℵ therefore ∴
            because ∵ dagger † percent % colon :
            """);

    /*
     * The two named wide spaces. They sit apart from the table because their value is a space
     * character, and the table is read by splitting on spaces.
     */
    private static final Map<String, String> WIDE_SPACES =
            Map.of("quad", "\u2003", "qquad", "\u2003\u2003");

    /**
     * Commands that set their name upright, the way an operator name is set in print. {@code sin x}
     * is a function applied to a variable, and drawing the s, i and n as three italic variables is
     * the single commonest way a naive renderer gives itself away.
     */
    private static final Set<String> OPERATORS = Set.of(
            "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh",
            "tanh", "log", "ln", "lg", "exp", "lim", "limsup", "liminf", "max", "min", "sup", "inf",
            "det", "dim", "ker", "deg", "gcd", "arg", "mod", "bmod", "pmod");

    /** Commands that wrap one group and set it upright, as running text rather than as symbols. */
    private static final Set<String> UPRIGHT_GROUPS = Set.of("text", "mathrm", "textrm", "mbox", "operatorname");

    /** Commands that set one group bold. */
    private static final Set<String> BOLD_GROUPS = Set.of("mathbf", "textbf", "bm", "boldsymbol");

    /** Escapes that stand for one literal character rather than for a symbol. */
    private static final Map<String, String> LITERALS = Map.of(
            "{", "{", "}", "}", "$", "$", "%", "%", "&", "&", "#", "#", "_", "_");

    /** Spacing commands, all of them drawn as one thin space or as nothing at all. */
    private static final Map<String, String> SPACES = Map.of(
            ",", " ", ";", " ", ":", " ", " ", " ", "!", "");

    /** Delimiters {@code \left} and {@code \right} may size. A dot means "no delimiter here". */
    private static final String DELIMITERS = "()[]{}|./";

    /**
     * Typesets one formula.
     *
     * @param source  the formula between its delimiters, without them
     * @param display whether it stands on its own line rather than inside a sentence
     * @return the typeset formula, or empty when any part of it is beyond this renderer - in which
     *         case the caller must show the source unchanged rather than a partial equation
     */
    static Optional<Element> render(String source, boolean display) {
        if (source == null) {
            return Optional.empty();
        }
        String formula = source.strip();
        if (formula.isEmpty() || formula.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Element root = new Element(display ? "div" : "span");
        root.setAttribute("class", display ? "jc-math jc-math--display" : "jc-math");
        try {
            Parser parser = new Parser(formula);
            parser.flow(root, 0);
            parser.requireEnd();
        } catch (Refused refused) {
            return Optional.empty();
        }
        return root.getChildCount() == 0 ? Optional.empty() : Optional.of(root);
    }

    /**
     * Thrown the moment the formula contains something this file cannot draw.
     *
     * <p>Carries no stack trace: it is a decision, not a fault, and it is thrown often enough on a
     * page of maths that filling in a trace would be the expensive part of parsing.
     */
    private static final class Refused extends RuntimeException {

        Refused() {
            super(null, null, false, false);
        }
    }

    /** One pass over one formula. */
    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        /** Parses until the end of the formula or the closing brace of the group being read. */
        void flow(Element parent, int depth) {
            if (depth > MAX_DEPTH) {
                throw new Refused();
            }
            while (at < text.length() && text.charAt(at) != '}') {
                atom(parent, depth);
            }
        }

        /** A stray closing brace is a malformed formula, not something to draw. */
        void requireEnd() {
            if (at != text.length()) {
                throw new Refused();
            }
        }

        private void atom(Element parent, int depth) {
            char c = text.charAt(at);
            if (c == '\\') {
                command(parent, depth);
            } else if (c == '{') {
                at++;
                flow(parent, depth + 1);
                expect('}');
            } else if (c == '^' || c == '_') {
                at++;
                Element script = new Element(c == '^' ? "sup" : "sub");
                unit(script, depth + 1);
                parent.appendChild(script);
            } else if (Character.isLetter(c)) {
                variable(parent);
            } else if (Character.isDigit(c)) {
                number(parent);
            } else if (Character.isWhitespace(c)) {
                space(parent);
            } else if (PUNCTUATION.indexOf(c) >= 0) {
                parent.appendChild(Element.createText(String.valueOf(c)));
                at++;
            } else {
                throw new Refused();
            }
        }

        /**
         * Reads what a script or a command argument applies to: a braced group, a command, or
         * exactly one character. This is where {@code x^2} and {@code x^{2n}} become the same shape.
         */
        private void unit(Element parent, int depth) {
            if (depth > MAX_DEPTH || at >= text.length()) {
                throw new Refused();
            }
            char c = text.charAt(at);
            if (c == '{') {
                at++;
                flow(parent, depth + 1);
                expect('}');
                return;
            }
            if (c == '\\') {
                command(parent, depth + 1);
                return;
            }
            if (Character.isLetterOrDigit(c) || PUNCTUATION.indexOf(c) >= 0) {
                atom(parent, depth + 1);
                return;
            }
            throw new Refused();
        }

        private void command(Element parent, int depth) {
            int from = at + 1;
            int end = from;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            if (end == from) {
                punctuationCommand(parent);
                return;
            }
            String name = text.substring(from, end);
            at = end;
            switch (name) {
                case "frac", "dfrac", "tfrac" -> fraction(parent, depth);
                case "sqrt" -> root(parent, depth);
                case "left", "right" -> delimiter(parent);
                default -> named(parent, name, depth);
            }
        }

        /** A backslash in front of something that is not a name: a literal, or a thin space. */
        private void punctuationCommand(Element parent) {
            if (at + 1 >= text.length()) {
                throw new Refused();
            }
            String escaped = String.valueOf(text.charAt(at + 1));
            String literal = LITERALS.get(escaped);
            String space = SPACES.get(escaped);
            if (literal == null && space == null) {
                // Includes the one worth naming: \\ is a line break, and a formula that wanted to be
                // two lines is a formula this renderer should not be drawing at all.
                throw new Refused();
            }
            at += 2;
            parent.appendChild(Element.createText(literal != null ? literal : space));
        }

        private void named(Element parent, String name, int depth) {
            if (UPRIGHT_GROUPS.contains(name)) {
                wrapped(parent, "jc-math__text", depth);
                return;
            }
            if (BOLD_GROUPS.contains(name)) {
                wrapped(parent, "jc-math__bold", depth);
                return;
            }
            if (OPERATORS.contains(name)) {
                Element operator = new Element("span");
                operator.setAttribute("class", "jc-math__fn");
                operator.setText(name);
                parent.appendChild(operator);
                return;
            }
            String symbol = SYMBOLS.getOrDefault(name, WIDE_SPACES.get(name));
            if (symbol == null) {
                throw new Refused();
            }
            parent.appendChild(Element.createText(symbol));
        }

        private void wrapped(Element parent, String className, int depth) {
            Element wrapper = new Element("span");
            wrapper.setAttribute("class", className);
            skipSpaces();
            expect('{');
            flow(wrapper, depth + 1);
            expect('}');
            parent.appendChild(wrapper);
        }

        private void fraction(Element parent, int depth) {
            Element fraction = new Element("span");
            fraction.setAttribute("class", "jc-math__frac");
            fraction.appendChild(argument("jc-math__num", depth));
            fraction.appendChild(argument("jc-math__den", depth));
            parent.appendChild(fraction);
        }

        private void root(Element parent, int depth) {
            Element radical = new Element("span");
            radical.setAttribute("class", "jc-math__sqrt");
            Element sign = new Element("span");
            sign.setAttribute("class", "jc-math__radical");
            sign.setText("√");
            radical.appendChild(sign);
            radical.appendChild(argument("jc-math__radicand", depth));
            parent.appendChild(radical);
        }

        /** One braced argument of a command, wrapped in the box the stylesheet stacks. */
        private Element argument(String className, int depth) {
            Element part = new Element("span");
            part.setAttribute("class", className);
            skipSpaces();
            expect('{');
            flow(part, depth + 1);
            expect('}');
            return part;
        }

        private void delimiter(Element parent) {
            skipSpaces();
            if (at >= text.length() || DELIMITERS.indexOf(text.charAt(at)) < 0) {
                throw new Refused();
            }
            char c = text.charAt(at);
            at++;
            // A dot is LaTeX for "this side has no delimiter", which is drawn as nothing at all.
            if (c != '.') {
                parent.appendChild(Element.createText(String.valueOf(c)));
            }
        }

        private void variable(Element parent) {
            int end = at;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            Element variable = new Element("i");
            variable.setAttribute("class", "jc-math__var");
            variable.setText(text.substring(at, end));
            at = end;
            parent.appendChild(variable);
        }

        private void number(Element parent) {
            int end = at;
            while (end < text.length()
                    && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                end++;
            }
            parent.appendChild(Element.createText(text.substring(at, end)));
            at = end;
        }

        /** Any run of whitespace is one space: LaTeX does not count them and neither does this. */
        private void space(Element parent) {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            parent.appendChild(Element.createText(" "));
        }

        private void skipSpaces() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private void expect(char c) {
            if (at >= text.length() || text.charAt(at) != c) {
                throw new Refused();
            }
            at++;
        }
    }

    /**
     * Reads the symbol table: whitespace-separated pairs of command name and the character it
     * stands for. An odd number of words is a typo in the table above, and it fails here at class
     * initialisation rather than silently shifting every pair after it.
     */
    private static Map<String, String> symbols(String table) {
        String[] words = table.strip().split("\\s+");
        if (words.length % 2 != 0) {
            throw new IllegalStateException("The LaTeX symbol table is not a list of pairs");
        }
        Map<String, String> symbols = new HashMap<>();
        for (int i = 0; i < words.length; i += 2) {
            symbols.put(words[i], words[i + 1]);
        }
        return Map.copyOf(symbols);
    }

    /**
     * Whether a run of text between dollars is plausibly maths at all.
     *
     * <p>A dollar sign is a currency symbol far more often than it is a delimiter, and "$5 and $10
     * more" is a sentence, not a formula. So a formula has to look like one: it names a command, it
     * raises or lowers something, it is a single unbroken token, or it contains an operator. Prose
     * with a price in it does none of those.
     *
     * @param formula the text between the delimiters
     */
    static boolean plausible(String formula) {
        if (formula == null || formula.isBlank() || formula.length() > 200) {
            return false;
        }
        if (formula.indexOf('\\') >= 0 || formula.indexOf('^') >= 0 || formula.indexOf('_') >= 0) {
            return true;
        }
        if (formula.chars().noneMatch(Character::isWhitespace)) {
            return true;
        }
        return formula.chars().anyMatch(c -> "+-=<>*/".indexOf(c) >= 0);
    }

    private Latex() {
    }
}
