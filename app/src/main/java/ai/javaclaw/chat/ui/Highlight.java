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
        /** A comment, line or block, including its delimiters. */
        COMMENT("comment"),

        /** A string literal, including its quotes, because losing them would change the source. */
        STRING("string"),

        /** A numeric literal, in whatever base or suffix the language spells it with. */
        NUMBER("number"),

        /** A reserved word of the dialect. Distinguished from {@link #TYPE} for the tint alone. */
        KEYWORD("keyword"),
        /** A name the shape of a type, or a property key in a data format. */
        TYPE("type"),
        /** A name immediately followed by an opening parenthesis. */
        FUNCTION("function"),
        /** An annotation or decorator, at-sign included. */
        META("meta");

        /**
         * The stylesheet's name for this kind, or null for a run that needs no element at all.
         * Stored as the bare modifier rather than the whole class list so the naming convention
         * lives in exactly one place - {@link #className()} - and cannot drift between constants.
         */
        private final String modifier;

        /**
         * Binds a kind to its stylesheet modifier. Called only by the constants above.
         *
         * @param modifier the modifier segment, or null for {@link #PLAIN}, which is emitted as a
         *     bare text node because wrapping ordinary source would double the DOM of a code block
         */
        Kind(String modifier) {
            this.modifier = modifier;
        }

        /**
         * The class the stylesheet paints, or null when this run needs no element at all.
         *
         * @return the whole class list for this kind, or null for {@link #PLAIN}, which the caller must
         *     read as "emit a bare text node" rather than as a missing class
         */
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

    /**
     * Primitive names shared across the C family, tinted as types rather than as keywords. Kept
     * apart from {@link #C_FAMILY} only so the two get different colours; the scanner treats a hit
     * in either set the same way otherwise.
     */
    private static final Set<String> C_TYPES = words("""
            bool boolean byte char double float int long number short signed string unsigned void
            """);

    /**
     * Java, Kotlin, Scala and the rest that run on the JVM, plus Dart, which reads the same way.
     * One dialect for the family because the alternative is eight nearly identical tables, and a
     * keyword the reader's language does not have is tinted rather than wrong - the failure mode is
     * a word that looks reserved and is not, which is far cheaper than a missing scanner.
     */
    private static final Dialect JVM = cFamily(merge(C_FAMILY, words("""
            companion constructor data expect fun in init inline internal is lateinit object open
            operator out override permits reified suspend tailrec val when where
            """)));

    /**
     * Rust, Go and the C-descended scripting languages, on the same reasoning as {@link #JVM}. They
     * are grouped separately because their extra keywords would otherwise tint half the identifiers
     * in a Java snippet.
     */
    private static final Dialect SCRIPTING = cFamily(merge(C_FAMILY, words("""
            declare fn from impl in is loop match mod move mut pub ref self trait unsafe use where
            chan defer fallthrough func go map range select
            """)));

    /**
     * Python. Its own dialect rather than a C-family variant because it has none of the family's
     * shape: no braces to key off, triple quotes that span lines, and a decorator syntax.
     */
    private static final Dialect PYTHON = new Dialect(
            List.of("#"), null, null, "\"'", true, true,
            words("""
                    and as assert async await break class continue def del elif else except finally
                    for from global if import in is lambda nonlocal not or pass raise return try
                    while with yield False None True match case self cls
                    """),
            Set.of(), false, true, true, false);

    /**
     * Ruby. The only dialect here whose block comment is line-oriented rather than delimited, which
     * is why {@code =begin} and {@code =end} are given as the pair rather than treated specially.
     */
    private static final Dialect RUBY = new Dialect(
            List.of("#"), "=begin", "=end", "\"'", false, true,
            words("""
                    and begin break case class def do else elsif end ensure false for if in module
                    next nil not or raise require rescue return self then true unless until when
                    while yield attr_accessor
                    """),
            Set.of(), false, true, false, false);

    /**
     * Shell. Deliberately does not tint capitalised words as types: a shell script is full of
     * environment variables in capitals, and painting {@code PATH} as a type is wrong on every line.
     */
    private static final Dialect SHELL = new Dialect(
            List.of("#"), null, null, "\"'", false, true,
            words("""
                    case cd do done elif else esac exec exit export fi for function if in local
                    read return set shift source then unset until while
                    """),
            Set.of(), false, false, false, false);

    /**
     * SQL. The one dialect that folds case, because {@code SELECT} and {@code select} are the same
     * word and both spellings are written; and the one where a backslash does not escape, since a
     * quote inside a string is doubled rather than escaped.
     */
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

    /**
     * CSS and its preprocessors. No line comment at all: {@code //} is valid in Sass and not in CSS,
     * and tinting the rest of a line as a comment when it is a URL's double slash is the worse of
     * the two mistakes.
     */
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

    /**
     * Builds the fence-label to dialect table once, at class initialisation.
     *
     * <p>A method rather than a chain of {@code Map.of} calls because the mapping is many-to-one -
     * seven labels reach {@link #JVM} - and writing each pair out would be seven places for a typo
     * to hide. Built into a mutable map and copied, so the result is immutable and shared.
     *
     * @return every label this file will scan, mapped to the dialect that reads it; a label absent
     *     from the result gets no scanner at all, which is the deny-by-default rule
     */
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

    /**
     * One empty source line, with the class the stylesheet hangs a line number on.
     *
     * @return a detached span; the caller fills it and adds it to the list
     */
    private static Element line() {
        Element line = new Element("span");
        line.setAttribute("class", "jc-code__line");
        return line;
    }

    /**
     * Appends one run of a line, wrapping it only when the kind needs an element.
     *
     * <p>Plain source goes in as a bare text node rather than a span. Ordinary code is most of a
     * snippet, so wrapping it would roughly double the DOM of every code block on the page for a
     * class the stylesheet does nothing with.
     *
     * <p>Empty runs are dropped, which is what keeps a line split at a newline from gaining an empty
     * span at each end; the text always goes through {@link Element#setText} or
     * {@link Element#createText}, both of which escape, so a snippet containing markup shows it.
     *
     * @param line the line element being filled
     * @param kind what this run was found to be
     * @param text the run, which by construction contains no newline
     */
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

        /** The snippet being scanned, exactly as the model wrote it. Never modified. */
        private final String code;

        /** The rules being applied. Fixed for the whole scan; a snippet has one language. */
        private final Dialect dialect;

        /** Runs found so far, in source order. Concatenating their text reproduces {@link #code}. */
        private final List<Token> tokens = new ArrayList<>();

        /**
         * Characters that matched no rule, held until something else is about to be emitted.
         * Buffering them is what makes ordinary source one run rather than one per character; it is
         * committed by {@link #flush()}, which every rule calls before taking its own run.
         */
        private final StringBuilder plain = new StringBuilder();

        /**
         * How far into {@link #code} the scan has reached. One cursor shared by every rule, because
         * the scan is strictly left to right and never backtracks - a rule that cannot match returns
         * false without having moved it.
         */
        private int at;

        /**
         * Starts a scan over one snippet.
         *
         * <p>One instance per snippet and never reused: {@link #at} and {@link #tokens} are consumed
         * by {@link #run()}, so a second call would append to the first call's result.
         *
         * @param code the snippet, never null and never empty - {@link #scan} guards both
         * @param dialect the rules to read it with, never null; a snippet with no known dialect is
         *     returned as one plain run without ever reaching here
         */
        Scanner(String code, Dialect dialect) {
            this.code = code;
            this.dialect = dialect;
        }

        /**
         * Runs the scan to the end of the snippet.
         *
         * <p>The rules are tried in a fixed order and the order is the grammar: a comment before a
         * string, so a quote inside a comment is not a string; a string before everything else, so a
         * keyword inside one is not a keyword; a number before a word, so a digit is not an
         * identifier's first character.
         *
         * <p>There is no failure path. A character that matches no rule is buffered as plain source
         * and the cursor advances, which is what guarantees the scan terminates and that the output
         * always reproduces the input exactly.
         *
         * @return the runs in order, immutable; empty only for an empty snippet
         */
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

        /**
         * Takes a line comment if one starts here.
         *
         * <p>Runs to the newline rather than including it, so the line splitter in {@link #lines}
         * still sees the break as a break: a comment run that swallowed its newline would merge two
         * source lines into one element.
         *
         * @return true when a comment was taken and the cursor moved
         */
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

        /**
         * Takes a block comment if one starts here, terminated or not.
         *
         * <p>An unterminated block runs to the end of the snippet rather than being declined. That
         * is what a compiler would say about the same text, and it is the shape a stopped answer
         * leaves behind - declining would tint the remainder as code the reader is not looking at.
         *
         * @return true when a comment was taken and the cursor moved
         */
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

        /**
         * Takes a string literal if one starts and <em>ends</em> here.
         *
         * <p>Unlike a block comment, an unterminated single-line string is declined outright. An
         * apostrophe in a comment or in prose is far commoner in a snippet than a string that runs
         * off the end of a line, and mistaking one for the other tints the whole rest of the block -
         * so the asymmetry between this rule and {@link #blockComment()} is deliberate.
         *
         * <p>Triple quotes are the exception and do span lines, because in the languages that have
         * them that is the only thing they are for.
         *
         * @return true when a complete string was taken and the cursor moved
         */
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

        /**
         * Takes the string that ends at {@code end}, and decides whether it is a property key.
         *
         * @param end index just past the closing quote
         * @return always true; the boolean exists so the caller can chain this with the other rules
         */
        private boolean string(int end) {
            Kind kind = dialect.propertyKeys() && followedByColon(end) ? Kind.TYPE : Kind.STRING;
            take(kind, end);
            return true;
        }

        /**
         * Whether the next non-blank character after a string is a colon.
         *
         * <p>This is what tells a JSON or YAML key from a value, which is the one distinction that
         * makes a data format readable at a glance. Only spaces and tabs are skipped, never a
         * newline: a key and its colon are on the same line, and scanning past the break would make
         * a bare string at the end of a list a key because the next line happened to have one.
         *
         * @param end index just past the closing quote
         * @return true when a colon follows on the same line
         */
        private boolean followedByColon(int end) {
            int i = end;
            while (i < code.length() && (code.charAt(i) == ' ' || code.charAt(i) == '\t')) {
                i++;
            }
            return i < code.length() && code.charAt(i) == ':';
        }

        /**
         * Takes an annotation or decorator, at-sign included.
         *
         * <p>A bare at-sign with no name after it is declined rather than taken as an empty
         * annotation - it is an operator in several of these languages and an email address in a
         * comment in all of them.
         *
         * @return true when an annotation was taken and the cursor moved
         */
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

        /**
         * Takes a numeric literal, in whatever base, suffix or exponent the language spells it with.
         *
         * <p>Word characters are consumed as part of the number, which is what makes {@code 0x1F},
         * {@code 100L} and {@code 1_000} single runs instead of a digit followed by an identifier.
         * The price is that a malformed literal is still tinted as a number, which is the right way
         * round: this is a highlighter, not a compiler.
         *
         * <p>Declined when the previous character is a word character, so the 8 in {@code utf8} is
         * part of the name rather than a number stuck to the end of one.
         *
         * @return true when a number was taken and the cursor moved
         */
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

        /**
         * Takes an identifier-shaped run and hands it to {@link #classify} to be named.
         *
         * <p>The run is always taken, even when it classifies as {@link Kind#PLAIN}. Taking it as
         * one token rather than letting the plain buffer eat it character by character is what makes
         * the following character available to {@code classify} as a lookahead - which is how a
         * function call is told from a bare name.
         *
         * @return true when a word was taken and the cursor moved
         */
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

        /**
         * Decides what an identifier-shaped run actually is.
         *
         * <p>The order is from certain to speculative: a reserved word is one by definition, a
         * declared type name likewise, a capitalised name is a convention, and a name against an
         * opening parenthesis is an inference. Falling through to {@link Kind#PLAIN} rather than
         * guessing further is deliberate - an untinted identifier is invisible, a wrongly tinted one
         * is a claim about code the reader is trying to read.
         *
         * <p>No whitespace is allowed between the name and the parenthesis, which is what keeps
         * {@code if (x)} from being read as a call.
         *
         * @param word the run's text
         * @param end index just past the run, used as a one-character lookahead
         * @return the kind, never null
         */
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

        /**
         * Closes the run that ends at {@code end} and moves the cursor onto it.
         *
         * @param kind what the run was found to be
         * @param end index just past the run
         */
        private void take(Kind kind, int end) {
            flush();
            tokens.add(new Token(kind, code.substring(at, end)));
            at = end;
        }

        /**
         * Commits the buffered plain source as one run, and empties the buffer.
         *
         * <p>Called by {@link #take} before every tinted run and once at the end of the scan, which
         * together are what guarantee the output is in source order: a rule that appended its own
         * token without flushing first would emit it ahead of the text before it.
         */
        private void flush() {
            if (plain.isEmpty()) {
                return;
            }
            tokens.add(new Token(Kind.PLAIN, plain.toString()));
            plain.setLength(0);
        }
    }

    /**
     * Whether a character may begin an identifier.
     *
     * <p>Separate from {@link #isWordCharacter} by exactly one class: a digit may continue a name
     * and may not start one, which is what lets {@link Scanner#number()} and
     * {@link Scanner#word()} be tried in either order without disagreeing.
     *
     * <p>Underscore and dollar are included because they are legal name characters across all of
     * these languages, and {@code $} in particular opens generated names in the JVM ones.
     *
     * @param c one character of the snippet
     * @return true when an identifier may start here
     */
    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    /**
     * Whether a character may continue an identifier. Adds digits to {@link #isWordStart}.
     *
     * @param c one character of the snippet
     * @return true when the character is part of the name in progress
     */
    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    // ------------------------------------------------------------------ dialects

    /**
     * A C-shaped dialect: slashes for comments, three quote characters, capitalised types.
     *
     * @param keywords the reserved words of the particular language, already merged
     * @return a dialect sharing the family's comment, quote and casing rules
     */
    private static Dialect cFamily(Set<String> keywords) {
        return new Dialect(List.of("//"), "/*", "*/", "\"'`", true, true,
                keywords, C_TYPES, false, true, true, false);
    }

    /**
     * Reads a keyword table written as a text block into a set.
     *
     * <p>The tables are written as prose so they can be read and edited as prose; splitting on
     * whitespace here is what lets them keep their line breaks and indentation. {@link Set#of} is
     * used deliberately rather than a tolerant collector: a duplicated word throws at class
     * initialisation, which is the earliest and loudest place a copy-paste in a table can surface.
     *
     * @param list a whitespace-separated table, with surrounding blank lines allowed
     * @return the words, immutable
     * @throws IllegalArgumentException if the table contains the same word twice
     */
    private static Set<String> words(String list) {
        return Set.of(list.strip().split("\\s+"));
    }

    /**
     * Unions two keyword sets, tolerating the overlap {@link #words} would reject.
     *
     * <p>The dialect tables genuinely share words - {@code in} and {@code is} are in the C family
     * and in the extensions layered on it - so the union has to accept a duplicate where the
     * individual tables must not. Ordered internally only so the result is stable to read while
     * debugging; nothing depends on the order.
     *
     * @param first the base table
     * @param second the extension
     * @return the union, immutable
     */
    private static Set<String> merge(Set<String> first, Set<String> second) {
        Set<String> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }

    /**
     * Not instantiable. The dialect tables are shared and immutable and the scan state lives in
     * {@link Scanner}, so an instance of this class would carry nothing.
     */
    private Highlight() {
    }
}
