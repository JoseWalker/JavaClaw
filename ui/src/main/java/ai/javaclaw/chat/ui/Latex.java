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

    /**
     * The two named wide spaces. They sit apart from the table because their value is a space
     * character, and the table is read by splitting on spaces - putting them in it would make the
     * pair count odd and take the class initialiser down with it.
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

        /**
         * Builds the refusal with no message, no cause, no suppression and no stack trace.
         *
         * <p>All four are off deliberately. Nothing catches this to read it - the only handler turns
         * it into an empty {@link Optional} - and filling in a trace is what makes an exception
         * expensive. On a page of maths this is thrown per unsupported command, so the trace would
         * dominate the cost of deciding not to draw something.
         */
        Refused() {
            super(null, null, false, false);
        }
    }

    /** One pass over one formula. */
    private static final class Parser {

        /** The formula being read, already stripped and length-checked by {@link #render}. */
        private final String text;

        /**
         * How far into {@link #text} the parse has reached. A single cursor shared by every method
         * below rather than an index passed around: the grammar is read strictly left to right and
         * never backtracks, so two cursors could only ever disagree.
         */
        private int at;

        /**
         * Starts a parse at the beginning of one formula.
         *
         * <p>One instance per formula, and never reused: {@link #at} is consumed by the parse, so a
         * second call on the same parser would read from wherever the first one stopped.
         *
         * @param text the formula between its delimiters, without them, never null
         */
        Parser(String text) {
            this.text = text;
        }

        /**
         * Parses until the end of the formula or the closing brace of the group being read.
         *
         * @param parent the element the contents are appended to
         * @param depth current nesting, checked against {@link #MAX_DEPTH} before anything is read
         */
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

        /**
         * Reads the next thing in the formula, whatever kind of thing it turns out to be.
         *
         * <p>The one place the character classes are enumerated, and it ends in a refusal rather
         * than in a default that copies the character through. That is the whole safety property of
         * this file: a character nobody wrote a rule for is not drawn as itself, because a formula
         * rendered with one symbol silently missing is worse than the source printed plainly.
         *
         * @param parent the element the result is appended to
         * @param depth current nesting, checked against {@link #MAX_DEPTH} by the callers that recurse
         * @throws Refused when the character is not one this renderer has a rule for
         */
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
         *
         * @param parent the element the unit is appended to
         * @param depth current nesting
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

        /**
         * Reads a backslash and whatever follows it.
         *
         * <p>The name is taken as the longest run of letters, which is LaTeX's own rule and the
         * reason {@code \alpha2} is the symbol followed by a digit rather than an unknown command.
         * A backslash with no letters after it is punctuation, not a name, and goes to
         * {@link #punctuationCommand}.
         *
         * <p>Only the three commands that take arguments are switched on here; everything else is a
         * name to be looked up, which keeps the argument-reading logic in one place per shape.
         *
         * @param parent the element the result is appended to
         * @param depth current nesting
         * @throws Refused when the command is not one this renderer draws
         */
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

        /**
         * A backslash in front of something that is not a name: a literal, or a thin space.
         *
         * @param parent the element the literal or space is appended to
         */
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

        /**
         * Draws a command that is known by name rather than by shape.
         *
         * <p>The order of the tests is the order of decreasing structure: a command that wraps a
         * group has to be recognised before one that is only a symbol, because the group still has
         * to be consumed. An operator is set upright rather than as letters, which is the single
         * commonest way a naive renderer gives itself away - {@code \sin x} drawn as three italic
         * variables.
         *
         * <p>The symbol table is consulted last and its miss is a refusal, never a fallback to the
         * name itself: printing "notacommand" where a symbol was asked for is the failure mode this
         * whole file is arranged to avoid.
         *
         * @param parent the element the result is appended to
         * @param name the command name, letters only, already consumed from the input
         * @param depth current nesting
         * @throws Refused when the name is in none of the tables
         */
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

        /**
         * Reads one braced group and appends it inside a styled span.
         *
         * <p>The brace is required rather than optional. {@code \text} with no group is a malformed
         * formula, and accepting the next single character instead - which is what a lenient reader
         * would do - turns "\textbf" into a bold "b" followed by stray letters.
         *
         * @param parent the element the wrapper is appended to
         * @param className the stylesheet class that gives the group its treatment
         * @param depth current nesting; the group's contents are read one level deeper
         * @throws Refused when the group is missing or unterminated
         */
        private void wrapped(Element parent, String className, int depth) {
            Element wrapper = new Element("span");
            wrapper.setAttribute("class", className);
            skipSpaces();
            expect('{');
            flow(wrapper, depth + 1);
            expect('}');
            parent.appendChild(wrapper);
        }

        /**
         * Draws {@code \frac} and its two display variants as a stacked numerator and denominator.
         *
         * <p>The three spellings collapse to one drawing on purpose: {@code \dfrac} and {@code
         * \tfrac} differ from {@code \frac} only in size, and this renderer has one size. Pretending
         * to honour a distinction it cannot draw would be a worse lie than ignoring it.
         *
         * <p>Both arguments are read as braced groups, so {@code \frac12} is refused rather than
         * read as one-half - LaTeX allows the short form, but accepting it here would mean guessing
         * where the numerator ends in a formula this renderer may not fully understand anyway.
         *
         * @param parent the element the fraction is appended to
         * @param depth current nesting
         * @throws Refused when either argument is missing or unterminated
         */
        private void fraction(Element parent, int depth) {
            Element fraction = new Element("span");
            fraction.setAttribute("class", "jc-math__frac");
            fraction.appendChild(argument("jc-math__num", depth));
            fraction.appendChild(argument("jc-math__den", depth));
            parent.appendChild(fraction);
        }

        /**
         * Draws {@code \sqrt} as a radical sign beside its radicand.
         *
         * <p>The optional index of {@code \sqrt[3]{x}} is not read, so that formula is refused at
         * the bracket rather than drawn as a cube root without its three. A radical that silently
         * loses its index is a different number, which is the one kind of error a reader has no way
         * of noticing.
         *
         * @param parent the element the radical is appended to
         * @param depth current nesting
         * @throws Refused when the radicand is missing or unterminated
         */
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

        /**
         * One braced argument of a command, wrapped in the box the stylesheet stacks.
         *
         * @param className the stylesheet class that positions this part of the construct
         * @param depth current nesting; the argument's contents are read one level deeper
         * @return the wrapper, detached
         * @throws Refused when the argument is missing or unterminated
         */
        private Element argument(String className, int depth) {
            Element part = new Element("span");
            part.setAttribute("class", className);
            skipSpaces();
            expect('{');
            flow(part, depth + 1);
            expect('}');
            return part;
        }

        /**
         * Reads the delimiter that follows {@code \left} or {@code \right}.
         *
         * <p>The two are treated identically and the pairing is not checked, because this renderer
         * draws the character at its natural size and never sizes it to its contents - so a
         * mismatched pair produces the brackets that were written, which is what the source said.
         * The delimiter is allow-listed against {@link #DELIMITERS} rather than copied through, so
         * {@code \left} followed by an arbitrary character is a refusal.
         *
         * @param parent the element the delimiter is appended to
         * @throws Refused when what follows is not one of the delimiters this renderer accepts
         */
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

        /**
         * Reads a run of letters and sets it as a variable.
         *
         * <p>The whole run is taken in one element rather than one element per letter, which keeps
         * the DOM small on a formula full of names. That is a departure from strict LaTeX, where
         * {@code xy} is x times y and would be set with a thin space between them; the spacing is
         * lost, the meaning is not, and this renderer does not claim typographic fidelity.
         *
         * @param parent the element the variable is appended to
         */
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

        /**
         * Reads a run of digits and decimal points as a number.
         *
         * <p>Set as plain text rather than in an element, because a number is upright in maths and
         * upright is what the surrounding text already is - a wrapper would add a node per literal
         * for no visual difference.
         *
         * <p>The point is accepted anywhere in the run, so {@code 1.2.3} is read as one number. That
         * is not a valid number, but it is also not something a renderer can improve by splitting:
         * it draws exactly the characters that were written either way.
         *
         * @param parent the element the number is appended to
         */
        private void number(Element parent) {
            int end = at;
            while (end < text.length()
                    && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                end++;
            }
            parent.appendChild(Element.createText(text.substring(at, end)));
            at = end;
        }

        /**
         * Any run of whitespace is one space: LaTeX does not count them and neither does this.
         *
         * @param parent the element the single space is appended to
         */
        private void space(Element parent) {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            parent.appendChild(Element.createText(" "));
        }

        /**
         * Advances past whitespace without drawing anything.
         *
         * <p>The silent counterpart of {@link #space}, and the difference is where they are called
         * from. Between two atoms a space is a space and is drawn; between a command and its
         * argument it is only separation - {@code \frac {a}{b}} means what {@code \frac{a}{b}} does -
         * so drawing it would put a gap inside a fraction.
         */
        private void skipSpaces() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        /**
         * Consumes one required character, or refuses the whole formula.
         *
         * <p>Every structural brace in the grammar goes through here, which is what makes an
         * unterminated group a refusal rather than a partial drawing. Running off the end of the
         * input is the same failure as finding the wrong character and is treated as one, so no
         * caller has to bounds-check before asking.
         *
         * @param c the character the grammar requires here
         * @throws Refused when the input has ended or holds something else
         */
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
     *
     * @param table the text block, whitespace-separated
     * @return the command-to-character map, immutable
     * @throws IllegalStateException if the table does not hold an even number of words
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
     *
     * @return true when the run is worth handing to the parser; false is not a claim that it is not
     *     maths, only that this file will not guess
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

    /**
     * Not instantiable. The tables are shared and immutable and the parse state lives in
     * {@link Parser}, so an instance of this class would carry nothing.
     */
    private Latex() {
    }
}
