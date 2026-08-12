package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Colours a fenced code block, without a bundler and without a library.
 *
 * <p>Every product this one is measured against highlights code, and all of them do it with a
 * JavaScript package - highlight.js or Shiki - loaded through a frontend build this UI deliberately
 * does not have. So the block arrived here as one flat run of grey, which is the one place in an
 * answer where structure is the content: a reader skimming a snippet is looking for the string
 * literal and the call, and finding them is exactly what colour is for.
 *
 * <h2>What it is, and what it is not</h2>
 *
 * <p>A lexer, not a parser. It knows where a comment starts, where a string ends, what a number
 * looks like and which words a language reserves - and nothing else. It cannot tell a type from a
 * variable except by the shape of the name, and it does not try: the useful signal is comment,
 * string, number, keyword, call, and those five are decidable by scanning left to right.
 *
 * <p>One dialect serves a family of languages. Java and Kotlin do not agree on their keyword lists
 * and neither do C and Go, so a word reserved in one and ordinary in another is tinted as a keyword
 * in both. The cost is a wrongly coloured identifier in a snippet that is still perfectly readable;
 * the alternative is fifteen keyword tables, each of them wrong in its own way after the next
 * language release.
 *
 * <h2>Deny by default</h2>
 *
 * <p>A language this file has no dialect for is returned as a single plain run rather than run
 * through the nearest-looking scanner: guessing that COBOL is C-shaped produces confident nonsense,
 * and grey code is not a defect. The same rule governs every doubtful case below - a quote with no
 * closing quote on its line is a stray apostrophe, not a string that swallows the rest of the file.
 *
 * <h2>Why it cannot inject markup</h2>
 *
 * <p>The source is model output. Every class name it can produce is a constant in {@link Kind},
 * every scrap of the source is placed with {@link Element#setText} or {@link Element#createText},
 * and the scanner is total: the concatenation of the tokens is the input, character for character,
 * which is the property {@code HighlightTest} pins and the reason a reader can still copy the
 * snippet and run it.
 */
final class Highlight {

    /**
     * What a run of source is, as far as this file can tell.
     *
     * <p>The class name is built here rather than at the call site so that the set of names the
     * stylesheet has to know about is closed, and so that nothing derived from the source can ever
     * reach a class attribute.
     */
    enum Kind {

        /** Ordinary source: emitted as a text node, with no element of its own. */
        PLAIN(null),
        COMMENT("comment"),
        STRING("string"),
        NUMBER("number"),
        KEYWORD("keyword"),
        /** A name the shape of a type, or a property key in a data format. */
        TYPE("type"),
        /** A name immediately followed by an opening parenthesis. */
        FUNCTION("function"),
        /** An annotation or decorator, at-sign included. */
        META("meta");

        private final String modifier;

        Kind(String modifier) {
            this.modifier = modifier;
        }

        /** The class the stylesheet paints, or null when this run needs no element at all. */
        String className() {
            return modifier == null ? null : "jc-tok jc-tok--" + modifier;
        }
    }

    /**
     * One run of source and what it was found to be.
     *
     * @param kind what the run is
     * @param text the run exactly as it was written, delimiters included
     */
    record Token(Kind kind, String text) {
    }

    /**
     * The lexical rules of one family of languages.
     *
     * @param lineComments     prefixes that comment out the rest of the line
     * @param blockOpen        opener of a comment that spans lines, or null when the family has none
     * @param blockClose       its closer, meaningless without an opener
     * @param quotes           characters that may open a string
     * @param tripleQuotes     whether three of a quote character open a string that spans lines
     * @param escapes          whether a backslash hides the next character inside a string
     * @param keywords         the reserved words, already lowercased when {@code foldCase}
     * @param types            words painted as types rather than as keywords, typically primitives
     * @param foldCase         whether a keyword may be written in any case, as in SQL
     * @param typesByCase      whether a capitalised identifier is a type, as in the JVM languages
     * @param annotations      whether {@code @name} is one token
     * @param propertyKeys     whether a string followed by a colon is a property name
     */
    private record Dialect(List<String> lineComments,
                           String blockOpen,
                           String blockClose,
                           String quotes,
                           boolean tripleQuotes,
                           boolean escapes,
                           Set<String> keywords,
                           Set<String> types,
                           boolean foldCase,
                           boolean typesByCase,
                           boolean annotations,
                           boolean propertyKeys) {
    }

    /**
     * Words reserved somewhere in the C family. Shared deliberately - see the note on dialects in
     * the class comment - and pruned of the ones that are ordinary identifiers too often to tint:
     * {@code of}, {@code value}, {@code get} and {@code set} were all in here and all wrong more
     * often than right.
     */
    private static final Set<String> C_FAMILY = words("""
            abstract assert async await break case catch class const continue default defer delete
            do else enum export extends final finally for function goto if implements import
            instanceof interface let module namespace new package private protected public readonly
            record return sealed static struct super switch synchronized template this throw throws
            transient try typedef typeof union using var virtual volatile while yield
            false null nil none true undefined
            """);

    private static final Set<String> C_TYPES = words("""
            bool boolean byte char double float int long number short signed string unsigned void
            """);

    private static final Dialect JVM = cFamily(merge(C_FAMILY, words("""
            companion constructor data expect fun in init inline internal is lateinit object open
            operator out override permits reified suspend tailrec val when where
            """)));

    private static final Dialect SCRIPTING = cFamily(merge(C_FAMILY, words("""
            declare fn from impl in is loop match mod move mut pub ref self trait unsafe use where
            chan defer fallthrough func go map range select
            """)));

    private static final Dialect PYTHON = new Dialect(
            List.of("#"), null, null, "\"'", true, true,
            words("""
                    and as assert async await break class continue def del elif else except finally
                    for from global if import in is lambda nonlocal not or pass raise return try
                    while with yield False None True match case self cls
                    """),
            Set.of(), false, true, true, false);

    private static final Dialect RUBY = new Dialect(
            List.of("#"), "=begin", "=end", "\"'", false, true,
            words("""
                    and begin break case class def do else elsif end ensure false for if in module
                    next nil not or raise require rescue return self then true unless until when
                    while yield attr_accessor
                    """),
            Set.of(), false, true, false, false);

    private static final Dialect SHELL = new Dialect(
            List.of("#"), null, null, "\"'", false, true,
            words("""
                    case cd do done elif else esac exec exit export fi for function if in local
                    read return set shift source then unset until while
                    """),
            Set.of(), false, false, false, false);

    private static final Dialect SQL = new Dialect(
            List.of("--"), "/*", "*/", "'\"", false, false,
            words("""
                    all alter and as asc between by case check column constraint create cross
                    default delete desc distinct drop else end exists foreign from full group
                    having if in index inner insert into is join key left like limit not null
                    offset on or order outer primary references right select set table then top
                    union unique update using values view when where with
                    """),
            words("bigint boolean char date decimal int integer numeric text timestamp varchar"),
            true, false, false, false);

    /** JSON, YAML and the rest of the formats that carry values rather than statements. */
    private static final Dialect DATA = new Dialect(
            List.of("#"), null, null, "\"'", false, true,
            words("false no null none true yes on off"),
            Set.of(), false, false, false, true);

    private static final Dialect STYLES = new Dialect(
            List.of(), "/*", "*/", "\"'", false, true,
            words("and from important media import include keyframes mixin not or supports to"),
            Set.of(), false, false, false, false);

    /**
     * Which dialect reads which fence label.
     *
     * <p>An allow-list, and the whole of the deny-by-default rule: a label that is not a key here
     * gets no scanner at all. New entries are cheap, wrong ones are not.
     */
    private static final Map<String, Dialect> DIALECTS = dialects();

    private static Map<String, Dialect> dialects() {
        Map<String, Dialect> byName = new HashMap<>();
        for (String name : "java kotlin kt scala groovy gradle dart".split(" ")) {
            byName.put(name, JVM);
        }
        for (String name : ("javascript js jsx mjs cjs typescript ts tsx c cpp c++ h hpp cs csharp"
                + " go golang rust rs swift php").split(" ")) {
            byName.put(name, SCRIPTING);
        }
        for (String name : "python py python3".split(" ")) {
            byName.put(name, PYTHON);
        }
        byName.put("ruby", RUBY);
        byName.put("rb", RUBY);
        for (String name : "bash sh shell zsh console".split(" ")) {
            byName.put(name, SHELL);
        }
        byName.put("sql", SQL);
        for (String name : "json json5 yaml yml toml jsonc".split(" ")) {
            byName.put(name, DATA);
        }
        for (String name : "css scss sass less".split(" ")) {
            byName.put(name, STYLES);
        }
        return Map.copyOf(byName);
    }

    /**
     * Splits a snippet into the runs a stylesheet can paint.
     *
     * @param code     the block's text, exactly as the model wrote it
     * @param language the fence's language name, already known to be a plain word, or null
     * @return the runs in order; concatenating their text reproduces {@code code} exactly
     */
    static List<Token> scan(String code, String language) {
        if (code == null || code.isEmpty()) {
            return List.of();
        }
        Dialect dialect = language == null ? null : DIALECTS.get(language.toLowerCase(Locale.ROOT));
        if (dialect == null) {
            return List.of(new Token(Kind.PLAIN, code));
        }
        return new Scanner(code, dialect).run();
    }

    /**
     * Draws a snippet as one element per source line.
     *
     * <p>A line is an element rather than a newline character for two reasons. The stylesheet needs
     * a box per line to hang a line number on, and the copy control needs to rebuild the snippet
     * from the DOM: it joins these elements with newlines, which is why none of them may contain a
     * newline of its own.
     *
     * @param code     the block's text
     * @param language the fence's language name, or null
     * @return one detached {@code .jc-code__line} per line, never empty
     */
    static List<Element> lines(String code, String language) {
        List<Element> lines = new ArrayList<>();
        Element current = line();
        for (Token token : scan(code, language)) {
            String[] parts = token.text().split("\n", -1);
            for (int part = 0; part < parts.length; part++) {
                if (part > 0) {
                    lines.add(current);
                    current = line();
                }
                append(current, token.kind(), parts[part]);
            }
        }
        lines.add(current);
        // A snippet almost always ends with a newline, and that newline closes the last line rather
        // than opening an empty one under it. Blank lines inside the snippet are the reader's own
        // spacing and are kept.
        Element last = lines.get(lines.size() - 1);
        if (lines.size() > 1 && last.getChildCount() == 0) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static Element line() {
        Element line = new Element("span");
        line.setAttribute("class", "jc-code__line");
        return line;
    }

    private static void append(Element line, Kind kind, String text) {
        if (text.isEmpty()) {
            return;
        }
        String className = kind.className();
        if (className == null) {
            line.appendChild(Element.createText(text));
            return;
        }
        Element run = new Element("span");
        run.setAttribute("class", className);
        run.setText(text);
        line.appendChild(run);
    }

    // ------------------------------------------------------------------ the scan

    /** One pass over one snippet. Holds the cursor so the rules below can read like rules. */
    private static final class Scanner {

        private final String code;
        private final Dialect dialect;
        private final List<Token> tokens = new ArrayList<>();
        private final StringBuilder plain = new StringBuilder();
        private int at;

        Scanner(String code, Dialect dialect) {
            this.code = code;
            this.dialect = dialect;
        }

        List<Token> run() {
            while (at < code.length()) {
                if (lineComment() || blockComment() || string() || annotation() || number() || word()) {
                    continue;
                }
                plain.append(code.charAt(at));
                at++;
            }
            flush();
            return List.copyOf(tokens);
        }

        private boolean lineComment() {
            for (String prefix : dialect.lineComments()) {
                if (code.startsWith(prefix, at)) {
                    int end = code.indexOf('\n', at);
                    take(Kind.COMMENT, end < 0 ? code.length() : end);
                    return true;
                }
            }
            return false;
        }

        private boolean blockComment() {
            if (dialect.blockOpen() == null || !code.startsWith(dialect.blockOpen(), at)) {
                return false;
            }
            int end = code.indexOf(dialect.blockClose(), at + dialect.blockOpen().length());
            // An unterminated block comment is what a cut-off answer leaves behind: it runs to the
            // end of the snippet rather than being refused, which is what the compiler would say too.
            take(Kind.COMMENT, end < 0 ? code.length() : end + dialect.blockClose().length());
            return true;
        }

        private boolean string() {
            char quote = code.charAt(at);
            if (dialect.quotes().indexOf(quote) < 0) {
                return false;
            }
            String triple = String.valueOf(new char[]{quote, quote, quote});
            if (dialect.tripleQuotes() && code.startsWith(triple, at)) {
                int end = code.indexOf(triple, at + 3);
                return string(end < 0 ? code.length() : end + 3);
            }
            int i = at + 1;
            while (i < code.length()) {
                char c = code.charAt(i);
                if (c == '\n') {
                    // No closing quote on this line. An apostrophe in a sentence is far commoner in
                    // a snippet than a string that spans lines, and mistaking one for the other
                    // tints the whole rest of the block.
                    return false;
                }
                if (dialect.escapes() && c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    return string(i + 1);
                }
                i++;
            }
            return false;
        }

        /** Takes the string that ends at {@code end}, and decides whether it is a property key. */
        private boolean string(int end) {
            Kind kind = dialect.propertyKeys() && followedByColon(end) ? Kind.TYPE : Kind.STRING;
            take(kind, end);
            return true;
        }

        private boolean followedByColon(int end) {
            int i = end;
            while (i < code.length() && (code.charAt(i) == ' ' || code.charAt(i) == '\t')) {
                i++;
            }
            return i < code.length() && code.charAt(i) == ':';
        }

        private boolean annotation() {
            if (!dialect.annotations() || code.charAt(at) != '@') {
                return false;
            }
            int end = at + 1;
            while (end < code.length() && isWordCharacter(code.charAt(end))) {
                end++;
            }
            if (end == at + 1) {
                return false;
            }
            take(Kind.META, end);
            return true;
        }

        private boolean number() {
            char c = code.charAt(at);
            if (!Character.isDigit(c)) {
                return false;
            }
            // A digit that continues a name is part of the name: utf8 is not a number.
            if (at > 0 && isWordCharacter(code.charAt(at - 1))) {
                return false;
            }
            int end = at;
            while (end < code.length()) {
                char digit = code.charAt(end);
                boolean exponent = (digit == '+' || digit == '-') && end > at
                        && "eE".indexOf(code.charAt(end - 1)) >= 0;
                if (isWordCharacter(digit) || digit == '.' || exponent) {
                    end++;
                    continue;
                }
                break;
            }
            take(Kind.NUMBER, end);
            return true;
        }

        private boolean word() {
            if (!isWordStart(code.charAt(at))) {
                return false;
            }
            int end = at;
            while (end < code.length() && isWordCharacter(code.charAt(end))) {
                end++;
            }
            take(classify(code.substring(at, end), end), end);
            return true;
        }

        private Kind classify(String word, int end) {
            String probe = dialect.foldCase() ? word.toLowerCase(Locale.ROOT) : word;
            if (dialect.keywords().contains(probe)) {
                return Kind.KEYWORD;
            }
            if (dialect.types().contains(probe)) {
                return Kind.TYPE;
            }
            if (dialect.typesByCase() && Character.isUpperCase(word.charAt(0))) {
                return Kind.TYPE;
            }
            // A name against an opening parenthesis is being called or declared. No whitespace is
            // allowed between the two, so a keyword-shaped construct cannot be caught by this.
            return end < code.length() && code.charAt(end) == '(' ? Kind.FUNCTION : Kind.PLAIN;
        }

        /** Closes the run that ends at {@code end} and moves the cursor onto it. */
        private void take(Kind kind, int end) {
            flush();
            tokens.add(new Token(kind, code.substring(at, end)));
            at = end;
        }

        private void flush() {
            if (plain.isEmpty()) {
                return;
            }
            tokens.add(new Token(Kind.PLAIN, plain.toString()));
            plain.setLength(0);
        }
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    // ------------------------------------------------------------------ dialects

    /** A C-shaped dialect: slashes for comments, three quote characters, capitalised types. */
    private static Dialect cFamily(Set<String> keywords) {
        return new Dialect(List.of("//"), "/*", "*/", "\"'`", true, true,
                keywords, C_TYPES, false, true, true, false);
    }

    private static Set<String> words(String list) {
        return Set.of(list.strip().split("\\s+"));
    }

    private static Set<String> merge(Set<String> first, Set<String> second) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }

    private Highlight() {
    }
}
