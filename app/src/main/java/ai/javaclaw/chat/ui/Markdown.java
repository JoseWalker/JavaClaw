package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an answer into the markup the transcript is typeset for.
 *
 * <p>The stylesheet carries a full treatment for prose - headings, lists, inline code, code blocks,
 * quotes, rules and links - and until this existed nothing on the page ever used it: answers printed
 * their literal asterisks and backticks, so a numbered list arrived as text that was not a list.
 *
 * <h2>Why it is written here</h2>
 *
 * <p>A markdown library would be a new dependency on a build this UI is not allowed to change, and
 * a client-side renderer would be a Node package. What an agent emits is a small, well-known subset,
 * so that subset is parsed here: fenced code, headings, bullet and numbered lists with one level of
 * nesting, block quotes, thematic breaks, tables, paragraphs, and inline emphasis, code spans,
 * links and maths. Anything it does not recognise stays as the text it was - the failure mode is a
 * plain paragraph, never a swallowed sentence.
 *
 * <p>Two of those have a file of their own, because each is a small language rather than a piece of
 * punctuation: {@link Highlight} decides what the inside of a code block means, and {@link Latex}
 * decides whether a run of dollars is a formula this UI can honestly draw. Both answer "no" by
 * default, and this parser prints the source when they do.
 *
 * <h2>Why it cannot inject markup</h2>
 *
 * <p>Nothing here parses or forwards HTML. Every element is constructed by name and every scrap of
 * text goes through {@link Element#setText} or {@link Element#createText}, both of which escape, so
 * a model that emits {@code <script>} produces those characters on screen. Link targets are the one
 * attribute taken from the input and are allow-listed by scheme, so {@code javascript:} and
 * {@code data:} URLs are rendered as their label and never become an href.
 */
final class Markdown {

    /**
     * Ceiling on nesting. Emphasis inside a link inside a list item is real; a hundred levels of it
     * is a malformed answer, and recursion driven by untrusted text needs a floor to stand on.
     */
    private static final int MAX_DEPTH = 6;

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)\\s*(\\S*).*$");

    /**
     * What a language name is allowed to look like. The info string after a fence is model output,
     * and it is the only thing on this page that would become part of a class attribute, so it is
     * matched against a shape rather than escaped: anything else is not a language and is dropped.
     */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9][a-z0-9+#._-]{0,19}");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(\\s*([^)\\s]*)(?:\\s+\"[^\"]*\")?\\s*\\)");

    /** The cell shape of a table's second row - the one that makes the first row a header. */
    private static final Pattern DELIMITER_CELL = Pattern.compile(":?-+:?");

    /**
     * The delimiter GFM strikes text through with, and it is two characters in every case.
     *
     * <p>A single tilde is a home directory, an approximation or a shell glob far more often than
     * it is markup, so it is not a delimiter here at all.
     */
    private static final String STRIKE = "~~";

    /**
     * The box that opens a task-list item.
     *
     * <p>Anchored and space-terminated, so {@code [note] see below} stays the text it is: the
     * bracket form appears in ordinary prose, and turning one into a control the reader cannot
     * operate would be worse than printing it.
     */
    private static final Pattern TASK_BOX = Pattern.compile("^\\[([ xX])]\\s+(.*)$");

    /**
     * The schemes a URL written without brackets may carry.
     *
     * <p>The same three {@link #safeHref} allows an explicit link, and deliberately not a superset:
     * a bare {@code javascript:} run in an answer is exactly the shape an injection arrives in, and
     * autolinking is the one place where the model, rather than the reader, chose the target.
     */
    private static final String[] AUTOLINK_SCHEMES = {"https://", "http://", "mailto:"};

    /** Punctuation that ends the sentence a URL is sitting in rather than the URL. */
    private static final String URL_TRAILERS = ".,;:!?'\"";

    /**
     * Ceiling on a table's width. A row of pipes is cheap for a model to emit and expensive to turn
     * into elements, and past this many columns nothing readable comes out anyway. Beyond it the
     * lines are not a table and fall through to the paragraph they look like.
     */
    private static final int MAX_TABLE_COLUMNS = 32;

    /**
     * How much of a message {@link #plainText} will look at.
     *
     * <p>A description is a line in a column and the opening of a tooltip; nothing past the first
     * few hundred characters can reach either. The ceiling is here rather than at the call site
     * because the input is model output of unbounded length and this runs once per visible row on
     * every keystroke in the search box.
     */
    private static final int MAX_DESCRIBED = 600;

    /** Runs of whitespace in a description, which is written on one line whatever it arrived as. */
    private static final Pattern DESCRIBED_WHITESPACE = Pattern.compile("\\s+");

    /** Punctuation a backslash may hide from the parser, as in CommonMark. */
    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!>~|";

    /** Named on the code block's own control, where a screen reader and a hovering pointer find it. */
    private static final String COPY_CODE_LABEL = "Copy code";

    /** The delimiter pairs that open maths inside a sentence, longest first so {@code $$} wins. */
    private static final String[][] INLINE_MATH = {{"$$", "$$"}, {"\\(", "\\)"}};

    /** The delimiter pairs that open maths on a line of its own. */
    private static final String[][] DISPLAY_MATH = {{"$$", "$$"}, {"\\[", "\\]"}};

    /**
     * Renders markdown into detached elements, ready to be appended to a {@code .jc-prose} container.
     *
     * @param markdown raw answer text, possibly null, blank or half-finished
     * @return the block elements, in order; empty when there is nothing to show
     */
    static List<Element> render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String normalised = markdown.replace("\r\n", "\n").replace('\r', '\n');
        return blocks(List.of(normalised.split("\n", -1)), 0);
    }

    /**
     * Projects markdown to the prose inside it, for the places that describe an answer rather than
     * showing it.
     *
     * <h2>Why this exists</h2>
     *
     * <p>The conversation column describes each row with the opening of its first message, and the
     * tooltip does the same at more length. Both were handed the source, so a screen reader read
     * "hash hash Java Ecosystem" and "backtick backtick backtick java pub" out loud as the summary
     * of a conversation - punctuation whose entire purpose is to be rendered, read as words to the
     * one reader who never sees it rendered.
     *
     * <p>It is a projection of the same grammar {@link #render} parses, using the same patterns, so
     * the two cannot drift into disagreeing about what a heading is. What it drops is what a
     * description has no use for: a fenced block is code, and reading three lines of a method
     * signature aloud tells nobody what a conversation is about. A message that is nothing but code
     * therefore projects to nothing, and the caller falls back to the conversation's title - which
     * is the honest answer rather than a fabricated one.
     *
     * <p>Bounded before any pattern runs over it. This is called once per visible row on every
     * keystroke in the search box, and the input is model output of unbounded length.
     *
     * @param markdown raw message text, possibly null or blank
     * @return one line of prose with single spaces between words, empty when there is no prose in
     *         the input at all
     */
    static String plainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String bounded = markdown.length() > MAX_DESCRIBED
                ? markdown.substring(0, MAX_DESCRIBED)
                : markdown;
        String normalised = bounded.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder prose = new StringBuilder();
        boolean insideFence = false;
        for (String line : normalised.split("\n", -1)) {
            if (FENCE.matcher(line).matches()) {
                insideFence = !insideFence;
                continue;
            }
            if (insideFence || THEMATIC_BREAK.matcher(line).matches()) {
                continue;
            }
            String written = undecorate(line);
            if (written.isBlank()) {
                continue;
            }
            if (!prose.isEmpty()) {
                prose.append(' ');
            }
            prose.append(written);
        }
        return prose.toString();
    }

    /**
     * One line with its block marker removed and its inline markup resolved to text.
     *
     * <p>The inline half is delegated to the renderer rather than stripped by hand: emphasis, code
     * spans, links and maths all have rules about what counts as a delimiter, and a second
     * implementation of those rules is a second set of them to get wrong. A link comes out as its
     * label, which is what a reader would have seen.
     *
     * @param line one line of a message, already known not to be inside a fence
     * @return the line's text, with runs of whitespace collapsed
     */
    private static String undecorate(String line) {
        String stripped = line;
        Matcher heading = HEADING.matcher(stripped);
        if (heading.matches()) {
            stripped = heading.group(2);
        }
        Matcher quote = QUOTE.matcher(stripped);
        if (quote.matches()) {
            stripped = quote.group(1);
        }
        Matcher bullet = BULLET.matcher(stripped);
        if (bullet.matches()) {
            stripped = bullet.group(3);
            // A task box is punctuation that exists to be drawn, and this projection is read aloud:
            // without this a screen reader hears "left bracket x right bracket" as the opening of
            // the conversation it is describing.
            Matcher box = TASK_BOX.matcher(stripped);
            if (box.matches()) {
                stripped = box.group(2);
            }
        }
        Matcher ordered = ORDERED.matcher(stripped);
        if (ordered.matches()) {
            stripped = ordered.group(3);
        }
        Element holder = new Element("span");
        inline(stripped, holder, 1);
        return DESCRIBED_WHITESPACE.matcher(holder.getTextRecursively()).replaceAll(" ").strip();
    }

    /**
     * Renders a run of text with the inline half of the grammar only - emphasis, code spans and
     * links - and no block construct at all.
     *
     * <p>This is for the part of an answer that has not finished arriving. Block structure cannot be
     * decided from a half-written answer, because the next token can still turn a paragraph into a
     * table or add a row to one; that is {@link StreamingProse}'s whole subject and it is not
     * relaxed here. Inline structure is different in kind: no inline construct in this grammar
     * spans a line, so a delimiter pair that has closed on a line means the same thing whatever
     * arrives after it, and holding it back buys nothing but a screen of asterisks.
     *
     * <p>Line breaks come back as the newline characters they were rather than as {@code <br>}. The
     * unfinished part of an answer is drawn under {@code white-space: pre-wrap}, where a break
     * element and the newline beside it would both print.
     *
     * <p>Same guarantees as {@link #render}: nothing is parsed as HTML, every scrap of text is set
     * through the escaping API, and a link target is allow-listed by scheme or is not a target.
     *
     * @param text a run of an answer, possibly null, empty or ending mid-word
     * @return detached nodes in order, ready to be appended to a {@code .jc-prose} container
     */
    static List<Element> inlineFlow(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        Element holder = new Element("span");
        String[] lines = normalised.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                holder.appendChild(Element.createText("\n"));
            }
            inline(lines[i], holder, 1);
        }
        List<Element> nodes = holder.getChildren().toList();
        // Detached before they are handed out, so the caller appends nodes that belong to nobody
        // rather than silently moving them out of a parent it was never given.
        holder.removeAllChildren();
        return nodes;
    }

    // ------------------------------------------------------------------ blocks

    private static List<Element> blocks(List<String> lines, int depth) {
        List<Element> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                i++;
                continue;
            }
            if (FENCE.matcher(line).matches()) {
                i = codeBlock(lines, i, out);
            } else if (THEMATIC_BREAK.matcher(line).matches()) {
                out.add(new Element("hr"));
                i++;
            } else if (HEADING.matcher(line).matches()) {
                i = heading(lines, i, out, depth);
            } else if (QUOTE.matcher(line).matches()) {
                i = quote(lines, i, out, depth);
            } else if (itemMarker(line) != null) {
                i = list(lines, i, out, depth);
            } else if (opensTable(lines, i)) {
                i = table(lines, i, out, depth);
            } else {
                // Maths is asked before prose and answers for itself: a formula this UI cannot draw
                // reports that it consumed nothing, and the lines are the paragraph they look like.
                int math = displayMath(lines, i, out);
                i = math > i ? math : paragraph(lines, i, out, depth);
            }
        }
        return out;
    }

    private static int heading(List<String> lines, int start, List<Element> out, int depth) {
        Matcher matcher = HEADING.matcher(lines.get(start));
        if (!matcher.matches()) {
            return start + 1;
        }
        // The stylesheet scales three levels; deeper headings share the smallest rather than
        // inheriting body size and losing their rank entirely.
        Element heading = new Element("h" + Math.min(3, matcher.group(1).length()));
        inline(matcher.group(2), heading, depth + 1);
        out.add(heading);
        return start + 1;
    }

    /**
     * Reads a fenced block, tolerating one that never closes - which is exactly what a stopped
     * answer leaves behind.
     */
    private static int codeBlock(List<String> lines, int start, List<Element> out) {
        String fence = lines.get(start).strip().startsWith("~~~") ? "~~~" : "```";
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        while (i < lines.size() && !lines.get(i).stripLeading().startsWith(fence)) {
            code.append(lines.get(i)).append('\n');
            i++;
        }
        out.add(codeFigure(code.toString(), languageOf(lines.get(start))));
        return i < lines.size() ? i + 1 : i;
    }

    /**
     * A code block with the two things a reader wants from one: to know what language they are
     * looking at, and to take it away.
     *
     * <p>Drawn as a figure with a caption because that is what it is - a labelled, self-contained
     * lump in the middle of prose - and because the label has to sit outside the scrolling region:
     * a language name that scrolls sideways with line 40 of the snippet is a label that is not
     * there when it is wanted.
     *
     * <p>The copy control carries no behaviour of its own. It is marked for the single delegated
     * listener the transcript installs, so a hundred code blocks in a conversation cost one listener
     * and no server round trip when one is pressed.
     *
     * @param code     the block's text, exactly as it was written
     * @param language the language named on the fence, already allow-listed by shape, or null
     */
    private static Element codeFigure(String code, String language) {
        Element block = new Element("code");
        if (language != null) {
            // The class is the convention a highlighter is looked up by; the data attribute is what
            // the stylesheet and the harness read, so the reader is told what they are looking at.
            block.setAttribute("class", "language-" + language);
            block.setAttribute("data-language", language);
        }
        Highlight.lines(code, language).forEach(block::appendChild);

        Element pre = new Element("pre");
        pre.appendChild(block);

        Element figure = new Element("figure");
        figure.setAttribute("class", "jc-code");
        figure.appendChild(codeHead(language), pre);
        return figure;
    }

    private static Element codeHead(String language) {
        Element name = new Element("span");
        name.setAttribute("class", "jc-code__lang");
        // A fence with no info string still gets a label, so the bar is the same height on every
        // block and the copy control does not move between one block and the next.
        name.setText(language == null ? "code" : language);

        Element head = new Element("figcaption");
        head.setAttribute("class", "jc-code__head");
        head.appendChild(name, copyControl());
        return head;
    }

    /**
     * The copy control, as markup only.
     *
     * <p>Both states are drawn into the button and the stylesheet shows one of them, which is how
     * the confirmation costs nothing at the moment it is needed: the tick is already on the page
     * before the reader presses anything.
     */
    private static Element copyControl() {
        Element button = new Element("button");
        button.setAttribute("class", "jc-code__copy");
        button.setAttribute("type", "button");
        // The handle the transcript's delegated listener claims. Nothing else marks itself with it.
        button.setAttribute("data-jc-copy", "code");
        button.setAttribute("aria-label", COPY_CODE_LABEL);
        button.setAttribute("title", COPY_CODE_LABEL);
        button.appendChild(
                glyph(Icons.COPY, "jc-code__icon"),
                glyph(Icons.CHECK, "jc-code__icon jc-code__icon--done"),
                caption("Copy", "jc-code__copy-text"),
                caption("Copied", "jc-code__copy-text jc-code__copy-text--done"));
        return button;
    }

    private static Element glyph(String svg, String className) {
        Element glyph = Icons.glyph(svg).getElement();
        glyph.setAttribute("class", "jc-glyph " + className);
        return glyph;
    }

    private static Element caption(String text, String className) {
        Element caption = new Element("span");
        caption.setAttribute("class", className);
        caption.setText(text);
        return caption;
    }

    /**
     * The language named on a fence, if it named one this UI will repeat.
     *
     * @param fence the opening fence line
     * @return a lowercase language name known to be safe as a class name, or null when the fence
     *         carried no info string or carried something that is not a language name
     */
    private static String languageOf(String fence) {
        Matcher matcher = FENCE.matcher(fence);
        if (!matcher.matches()) {
            return null;
        }
        String info = matcher.group(2).toLowerCase(Locale.ROOT);
        return LANGUAGE.matcher(info).matches() ? info : null;
    }

    /**
     * Reads a formula that stands on its own lines.
     *
     * <p>Display maths is the one block whose delimiters carry no other meaning, so it is read here
     * rather than left to the inline pass: {@code $$} on a line by itself opens a formula that runs
     * over as many lines as it needs, and a paragraph parser would have joined those lines with
     * line breaks and handed the result to a renderer that cannot see it was ever one formula.
     *
     * <p>Nothing is consumed unless a formula actually comes out. An unclosed run of dollars, or one
     * holding a command {@link Latex} will not draw, leaves the lines untouched for the paragraph
     * they otherwise are - which is what keeps the source readable instead of swallowed.
     *
     * @return the line to continue from, or {@code start} when this is not display maths
     */
    private static int displayMath(List<String> lines, int start, List<Element> out) {
        String opener = lines.get(start).strip();
        for (String[] pair : DISPLAY_MATH) {
            if (!opener.startsWith(pair[0])) {
                continue;
            }
            int consumed = displayMath(lines, start, out, pair[0], pair[1]);
            if (consumed > start) {
                return consumed;
            }
        }
        return start;
    }

    private static int displayMath(List<String> lines, int start, List<Element> out,
                                   String open, String close) {
        StringBuilder formula = new StringBuilder();
        String head = lines.get(start).strip().substring(open.length());
        int end = -1;

        if (head.endsWith(close) && head.length() >= close.length()) {
            formula.append(head, 0, head.length() - close.length());
            end = start + 1;
        } else {
            formula.append(head);
            for (int i = start + 1; i < lines.size() && end < 0; i++) {
                String line = lines.get(i).strip();
                if (line.endsWith(close)) {
                    formula.append(' ').append(line, 0, line.length() - close.length());
                    end = i + 1;
                } else if (line.isEmpty()) {
                    // A blank line ends a block everywhere else in this grammar, and a formula that
                    // has not closed by then is not going to.
                    return start;
                } else {
                    formula.append(' ').append(line);
                }
            }
        }
        if (end < 0) {
            return start;
        }
        Optional<Element> math = Latex.render(formula.toString(), true);
        math.ifPresent(out::add);
        return math.isPresent() ? end : start;
    }

    private static int quote(List<String> lines, int start, List<Element> out, int depth) {
        List<String> quoted = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            Matcher matcher = QUOTE.matcher(lines.get(i));
            if (!matcher.matches()) {
                break;
            }
            quoted.add(matcher.group(1));
            i++;
        }
        Element quote = new Element("blockquote");
        if (depth < MAX_DEPTH) {
            blocks(quoted, depth + 1).forEach(quote::appendChild);
        } else {
            quote.setText(String.join("\n", quoted));
        }
        out.add(quote);
        return i;
    }

    /**
     * Whether a table starts on this line.
     *
     * <p>A row of pipes is only a table when the line under it is a delimiter row with exactly the
     * same number of cells. Anything less is a sentence that happens to contain pipes, and is left
     * to the paragraph it is: guessing here would eat a line of prose and print it as a one-cell
     * table.
     */
    /**
     * Where a table starting on this line ends, for a caller that has to typeset one in the middle
     * of text it is otherwise treating as prose.
     *
     * <p>Exists for {@link StreamingProse}, which needs the table's extent and not its markup: the
     * unfinished part of an answer is drawn as flow, and the rows that have landed have to be cut
     * out of that flow and given to this parser. Answering with an extent rather than exposing the
     * row grammar keeps one definition of what a table is.
     *
     * @param lines the lines being read, never null
     * @param at    the line a table might start on
     * @return the line just past the table, or {@code at} when no table starts here
     */
    static int tableEnd(List<String> lines, int at) {
        if (at < 0 || at >= lines.size() || !opensTable(lines, at)) {
            return at;
        }
        int i = at + 2;
        while (i < lines.size() && !tableCells(lines.get(i)).isEmpty()) {
            i++;
        }
        return i;
    }

    private static boolean opensTable(List<String> lines, int at) {
        if (at + 1 >= lines.size()) {
            return false;
        }
        List<String> header = tableCells(lines.get(at));
        if (header.isEmpty() || header.size() > MAX_TABLE_COLUMNS) {
            return false;
        }
        List<String> delimiters = tableCells(lines.get(at + 1));
        return delimiters.size() == header.size()
                && delimiters.stream().allMatch(cell -> DELIMITER_CELL.matcher(cell.strip()).matches());
    }

    /**
     * Reads a table: the header, the delimiter row that is never shown, and every row under it.
     *
     * <p>Stops at the first line that is not a row, which is also what a table still arriving from
     * the model looks like - the rows that have landed are rendered, and the next token continues
     * the table on the following pass.
     *
     * <p>The table is handed back inside a wrapper, because it is the one block in an answer whose
     * width answers to its own content rather than to the measure, and a model asked for a
     * comparison will happily return twelve columns. Without somewhere to scroll, those columns
     * collapse - measured at 45px each on the running build, every heading wrapped to four lines,
     * with no overflow reported anywhere. The scroll is the wrapper's rather than the table's on
     * purpose: a table made into its own scroll container stops sizing its header and its body to
     * the same columns, which trades a readable table for a scrollable misaligned one.
     */
    private static int table(List<String> lines, int start, List<Element> out, int depth) {
        List<String> headers = tableCells(lines.get(start));
        Element table = new Element("table");

        Element head = new Element("thead");
        head.appendChild(tableRow("th", headers, headers.size(), depth));
        table.appendChild(head);

        Element body = new Element("tbody");
        int i = start + 2;
        while (i < lines.size()) {
            List<String> cells = tableCells(lines.get(i));
            if (cells.isEmpty()) {
                break;
            }
            body.appendChild(tableRow("td", cells, headers.size(), depth));
            i++;
        }
        table.appendChild(body);

        // The class is a literal, never anything derived from the answer, so the wrapper cannot
        // carry a value the model chose.
        Element scroller = new Element("div");
        scroller.setAttribute("class", "jc-table");
        scroller.appendChild(table);

        out.add(scroller);
        return i;
    }

    /**
     * One row, held to the header's column count so every row lines up under the same headings: a
     * ragged row is padded with empty cells and a long one is cut, rather than shifting the columns
     * of that row against every other.
     */
    private static Element tableRow(String cellTag, List<String> cells, int columns, int depth) {
        Element row = new Element("tr");
        for (int column = 0; column < columns; column++) {
            Element cell = new Element(cellTag);
            if (column < cells.size()) {
                inline(cells.get(column).strip(), cell, depth + 1);
            }
            row.appendChild(cell);
        }
        return row;
    }

    /**
     * Splits a table row into its cells.
     *
     * <p>Only an unescaped pipe outside a code span divides two cells, so {@code \|} and a pipe
     * inside backticks stay part of the text. The escape itself is left in place for {@link #inline}
     * to resolve, so there is one implementation of what a backslash means rather than two.
     *
     * <p>The pipes on the outside of a row are optional in the syntax and are not columns; they are
     * dropped here so {@code | a | b |} and {@code a | b} describe the same two-column row.
     *
     * @return the cells in order, or an empty list when this line is not a row at all
     */
    private static List<String> tableCells(String line) {
        String row = line.strip();
        if (row.isEmpty()) {
            return List.of();
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean divided = false;
        boolean inCodeSpan = false;

        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                cell.append(c).append(row.charAt(i + 1));
                i++;
            } else if (c == '`') {
                inCodeSpan = !inCodeSpan;
                cell.append(c);
            } else if (c == '|' && !inCodeSpan) {
                divided = true;
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());

        if (!divided) {
            return List.of();
        }
        if (row.startsWith("|")) {
            cells.remove(0);
        }
        if (row.endsWith("|") && !cells.isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells.size() > MAX_TABLE_COLUMNS ? List.of() : cells;
    }

    /**
     * Reads one run of list items at a single indent level.
     *
     * <p>Anything indented past the marker belongs to the item that opened it and is handed back to
     * {@link #blocks} with that indent removed, so a nested list is parsed by the same code as a top
     * level one rather than by a second, subtly different implementation.
     */
    private static int list(List<String> lines, int start, List<Element> out, int depth) {
        int indent = indentOf(lines.get(start));
        boolean ordered = ORDERED.matcher(lines.get(start)).matches();
        Element list = new Element(ordered ? "ol" : "ul");

        int i = start;
        while (i < lines.size()) {
            Matcher marker = itemMarker(lines.get(i));
            if (marker == null || indentOf(lines.get(i)) != indent) {
                break;
            }
            // A numbered step that opens with a bracket is a numbered step: GFM puts task boxes on
            // bullets only, and reading one into an ordered list would rewrite the reader's steps.
            Matcher box = TASK_BOX.matcher(marker.group(3));
            boolean task = !ordered && box.matches();
            String written = task ? box.group(2) : marker.group(3);

            List<String> item = new ArrayList<>();
            item.add(written);
            i++;
            i = collectItemBody(lines, i, indent, item);

            Element entry = new Element("li");
            if (task) {
                // The class is a literal and the box is built here, so nothing derived from the
                // answer reaches an attribute.
                list.setAttribute("class", "jc-task-list");
                entry.setAttribute("class", "jc-task");
                entry.appendChild(taskBox(!" ".equals(box.group(1))));
            }
            appendItemContent(entry, item, depth);
            list.appendChild(entry);
        }
        out.add(list);
        return i;
    }

    /**
     * The box on a task-list item, in the state the answer put it in.
     *
     * <p>Always disabled. The transcript is a record of what was said, and a box the reader could
     * tick would be an edit of an answer that nothing would record and nobody would act on - the
     * checklist belongs to the model, not to the page. Being disabled is also what keeps it out of
     * the tab order, so a long checklist does not become a screenful of stops on the way to the
     * composer.
     *
     * <p>Named for assistive technology, because a checkbox is the one part of the item whose
     * meaning is not in the text beside it: without the label a screen reader reads the state of an
     * unnamed control, and with it the state has a subject.
     *
     * @param done whether the answer marked this item as done
     */
    private static Element taskBox(boolean done) {
        Element box = new Element("input");
        box.setAttribute("type", "checkbox");
        box.setAttribute("disabled", true);
        if (done) {
            box.setAttribute("checked", true);
        }
        box.setAttribute("class", "jc-task__box");
        box.setAttribute("aria-label", done ? "Done" : "Not done");
        return box;
    }

    /** Everything that hangs under an item: its wrapped text, its nested lists, its paragraphs. */
    private static int collectItemBody(List<String> lines, int from, int indent, List<String> item) {
        int i = from;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                // A blank line only stays inside the item when something indented follows it.
                if (i + 1 < lines.size() && !lines.get(i + 1).isBlank() && indentOf(lines.get(i + 1)) > indent) {
                    item.add("");
                    i++;
                    continue;
                }
                break;
            }
            if (indentOf(line) <= indent) {
                break;
            }
            item.add(dedent(line, indent + 2));
            i++;
        }
        return i;
    }

    private static void appendItemContent(Element entry, List<String> item, int depth) {
        boolean singleLine = item.stream().skip(1).allMatch(String::isBlank);
        if (singleLine || depth >= MAX_DEPTH) {
            inline(item.get(0), entry, depth + 1);
            return;
        }
        List<Element> parts = blocks(item, depth + 1);
        // A one-paragraph item is unwrapped: an <li> whose only child is a <p> adds a block margin
        // inside a row that is already spaced by the list.
        if (parts.size() == 1 && "p".equals(parts.get(0).getTag())) {
            List<Element> children = parts.get(0).getChildren().toList();
            parts.get(0).removeAllChildren();
            children.forEach(entry::appendChild);
            return;
        }
        parts.forEach(entry::appendChild);
    }

    private static int paragraph(List<String> lines, int start, List<Element> out, int depth) {
        List<String> text = new ArrayList<>();
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i);
            // A table is the one block that cannot be recognised from its own first line, so it is
            // asked about separately. Without this a lead-in sentence swallows the table under it,
            // which is the shape an answer almost always arrives in.
            if (line.isBlank()
                    || (i > start && (opensBlock(line) || opensDisplayMath(line) || opensTable(lines, i)))) {
                break;
            }
            text.add(line);
            i++;
        }
        Element paragraph = new Element("p");
        for (int n = 0; n < text.size(); n++) {
            if (n > 0) {
                // A single newline is a line break here rather than a space. An agent writing to a
                // chat window means the break it typed; collapsing them runs its lines together.
                paragraph.appendChild(new Element("br"));
            }
            inline(text.get(n), paragraph, depth + 1);
        }
        out.add(paragraph);
        return i;
    }

    /**
     * Whether a line opens display maths, judged on the line alone.
     *
     * <p>Deliberately syntactic: this is asked while a paragraph is being read, to decide whether
     * the paragraph has ended, and the answer must not depend on whether the formula further down
     * turns out to be one this UI can draw. A run of dollars that {@link Latex} later refuses ends
     * the paragraph and becomes a paragraph of its own, which is the source, unaltered.
     */
    private static boolean opensDisplayMath(String line) {
        String start = line.strip();
        for (String[] pair : DISPLAY_MATH) {
            if (start.startsWith(pair[0])) {
                return true;
            }
        }
        return false;
    }

    private static boolean opensBlock(String line) {
        return FENCE.matcher(line).matches()
                || THEMATIC_BREAK.matcher(line).matches()
                || HEADING.matcher(line).matches()
                || QUOTE.matcher(line).matches()
                || itemMarker(line) != null;
    }

    private static Matcher itemMarker(String line) {
        Matcher bullet = BULLET.matcher(line);
        if (bullet.matches()) {
            return bullet;
        }
        Matcher ordered = ORDERED.matcher(line);
        return ordered.matches() ? ordered : null;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String dedent(String line, int columns) {
        int cut = 0;
        while (cut < columns && cut < line.length() && line.charAt(cut) == ' ') {
            cut++;
        }
        return line.substring(cut);
    }

    // ------------------------------------------------------------------ inline

    /**
     * Appends one line of inline markdown to a parent element.
     *
     * <p>Scanned left to right and closed by the first matching delimiter, which is what keeps a
     * lone asterisk in a sentence from swallowing the rest of the paragraph: an opener with no
     * closer is emitted as the character it is.
     */
    private static void inline(String text, Element parent, int depth) {
        inline(text, parent, depth, false);
    }

    /**
     * The same, told whether it is running inside a link.
     *
     * <p>The flag exists for one construct: a URL written bare becomes a link, and a bare URL
     * inside the label of an explicit link must not, because an anchor inside an anchor is markup
     * no browser agrees about and the label is already inside the link the model wrote. It is
     * carried rather than derived from the parent element, because the parent of a URL inside
     * emphasis inside a link is an {@code em}.
     *
     * @param inLink true when everything appended here is already inside an {@code a}
     */
    private static void inline(String text, Element parent, int depth, boolean inLink) {
        if (depth >= MAX_DEPTH) {
            parent.appendChild(Element.createText(text));
            return;
        }
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            // Asked before the escape rule, because \( is an opening delimiter here and an escaped
            // parenthesis there, and the formula is the more specific reading of the two.
            if (c == '$' || c == '\\') {
                int consumed = math(text, i, parent, plain);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            if (c == '\\' && i + 1 < text.length() && ESCAPABLE.indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    flush(plain, parent);
                    Element code = new Element("code");
                    code.setText(text.substring(i + 1, end));
                    parent.appendChild(code);
                    i = end + 1;
                    continue;
                }
            }
            if (isEmphasisOpener(text, i)) {
                int consumed = emphasis(text, i, parent, plain, depth, inLink);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            if (c == '~') {
                int consumed = strikethrough(text, i, parent, plain, depth, inLink);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            if (c == '[') {
                int consumed = link(text, i, parent, plain, depth);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            if (!inLink && opensAutolink(text, i)) {
                int consumed = autolink(text, i, parent, plain);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        flush(plain, parent);
    }

    /**
     * Reads a run the model struck out.
     *
     * <p>Governed by the same closing rule as {@link #emphasis}: scanned left to right, closed by
     * the first matching pair, and an opener with no closer is the two characters it is. The
     * opening pair must be followed by something other than a space, so a line of tildes used as a
     * rule does not open a strike that swallows the paragraph.
     *
     * <p>Worth having rather than cosmetic. Struck text is the model saying a thing is <em>not</em>
     * so, and printed as its own tildes it reads as the affirmative claim with punctuation around
     * it - the one inline construct whose absence inverts a sentence.
     *
     * @return characters consumed, or 0 when this is not a struck run
     */
    private static int strikethrough(String text, int at, Element parent, StringBuilder plain,
                                     int depth, boolean inLink) {
        if (!text.startsWith(STRIKE, at)) {
            return 0;
        }
        int from = at + STRIKE.length();
        if (from >= text.length() || Character.isWhitespace(text.charAt(from))) {
            return 0;
        }
        int end = text.indexOf(STRIKE, from);
        if (end <= from) {
            return 0;
        }
        flush(plain, parent);
        Element struck = new Element("del");
        inline(text.substring(from, end), struck, depth + 1, inLink);
        parent.appendChild(struck);
        return end + STRIKE.length() - at;
    }

    /**
     * Whether a URL written without brackets starts here.
     *
     * <p>The character before it has to be a boundary. Without that, the tail of an identifier or
     * of a URL already being read - {@code xhttps://}, or the second half of one this scan has
     * passed over - would open a link in the middle of a word.
     */
    private static boolean opensAutolink(String text, int at) {
        if (at > 0 && Character.isLetterOrDigit(text.charAt(at - 1))) {
            return false;
        }
        return schemeAt(text, at) != null;
    }

    /**
     * @return the allow-listed scheme this position opens, matched without regard to case as the
     *         syntax requires, or null when it opens none
     */
    private static String schemeAt(String text, int at) {
        for (String scheme : AUTOLINK_SCHEMES) {
            if (text.regionMatches(true, at, scheme, 0, scheme.length())) {
                return scheme;
            }
        }
        return null;
    }

    /**
     * Turns a bare URL into the link it plainly is.
     *
     * <p>A model writes most of its URLs bare, because in a chat window that is what a person does,
     * and until this existed the one thing on screen that was a link was a string to select and
     * copy by hand.
     *
     * <p>Where the URL ends is the whole difficulty, and it is decided conservatively: it runs to
     * the next space, and then the sentence's own punctuation is handed back. A trailing bracket is
     * given back only when nothing inside the URL opened it, which is what keeps
     * {@code https://en.wikipedia.org/wiki/Foo_(bar)} intact while
     * {@code (see https://example.com)} does not swallow the closing bracket.
     *
     * <p>The target goes through the same {@link #safeHref} allow-list as an explicit link, so a
     * scheme this UI will not navigate to is left as the text it was rather than made clickable.
     *
     * @return characters consumed, or 0 when what starts here is not a URL worth linking
     */
    private static int autolink(String text, int at, Element parent, StringBuilder plain) {
        String scheme = schemeAt(text, at);
        if (scheme == null) {
            return 0;
        }
        int end = at;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '<') {
            end++;
        }
        end = trimSentencePunctuation(text, at, end);
        String raw = text.substring(at, end);
        // A scheme on its own is not a target: "https://" would become a link to nowhere.
        if (raw.length() <= scheme.length()) {
            return 0;
        }
        String href = safeHref(raw);
        if (href == null) {
            return 0;
        }
        flush(plain, parent);
        Element link = new Element("a");
        link.setAttribute("href", href);
        link.setAttribute("target", "_blank");
        link.setAttribute("rel", "noopener noreferrer nofollow");
        // The label is the URL exactly as written; nothing is parsed inside it.
        link.setText(raw);
        parent.appendChild(link);
        return end - at;
    }

    /**
     * Hands back the punctuation that belongs to the sentence rather than to the URL.
     *
     * @param from where the URL starts
     * @param to   where the run of non-space characters ends
     * @return the end of the URL itself, never before {@code from}
     */
    private static int trimSentencePunctuation(String text, int from, int to) {
        int end = to;
        while (end > from) {
            char last = text.charAt(end - 1);
            if (URL_TRAILERS.indexOf(last) >= 0 || (last == ')' && !opensItsOwnBracket(text, from, end))) {
                end--;
                continue;
            }
            break;
        }
        return end;
    }

    /** Whether the bracket closing this run was opened inside it, rather than around it. */
    private static boolean opensItsOwnBracket(String text, int from, int end) {
        int open = 0;
        int close = 0;
        for (int i = from; i < end; i++) {
            if (text.charAt(i) == '(') {
                open++;
            } else if (text.charAt(i) == ')') {
                close++;
            }
        }
        return open >= close;
    }

    /**
     * Whether a delimiter at this position can open emphasis.
     *
     * <p>An underscore between word characters is not a delimiter - it is a name. Without this,
     * every {@code snake_case_identifier} in an answer about code loses its underscores and turns
     * italic halfway through.
     */
    private static boolean isEmphasisOpener(String text, int at) {
        char c = text.charAt(at);
        if (c != '*' && c != '_') {
            return false;
        }
        if (c == '_' && at > 0 && Character.isLetterOrDigit(text.charAt(at - 1))) {
            return false;
        }
        int next = at + 1;
        if (next < text.length() && text.charAt(next) == c) {
            next++;
        }
        // "** " opens nothing; a delimiter run has to be followed by the text it emphasises.
        return next < text.length() && !Character.isWhitespace(text.charAt(next));
    }

    /**
     * @param inLink whether this run is already inside a link, which its contents inherit
     * @return characters consumed, or 0 when the run has no closing delimiter
     */
    private static int emphasis(String text, int at, Element parent, StringBuilder plain, int depth,
                                boolean inLink) {
        char c = text.charAt(at);
        boolean strong = at + 1 < text.length() && text.charAt(at + 1) == c;
        String delimiter = strong ? String.valueOf(new char[]{c, c}) : String.valueOf(c);

        int from = at + delimiter.length();
        int end = text.indexOf(delimiter, from);
        while (end > from && !strong && end + 1 < text.length() && text.charAt(end + 1) == c) {
            // A single-delimiter run must not close on the first half of a double one.
            end = text.indexOf(delimiter, end + 2);
        }
        if (end <= from) {
            return 0;
        }
        if (c == '_' && end + delimiter.length() < text.length()
                && Character.isLetterOrDigit(text.charAt(end + delimiter.length()))) {
            return 0;
        }
        flush(plain, parent);
        Element emphasis = new Element(strong ? "strong" : "em");
        inline(text.substring(from, end), emphasis, depth + 1, inLink);
        parent.appendChild(emphasis);
        return end + delimiter.length() - at;
    }

    /**
     * Typesets a formula that sits inside a sentence.
     *
     * <p>Two families of delimiter, and they are not equally safe. {@code \( \)} and {@code $$ $$}
     * mean maths and nothing else, so a closing delimiter is enough. A single {@code $} is a
     * currency symbol most times it appears in an answer, so it has to earn the reading: the text
     * after it is not a space, the closing dollar is not preceded by one and is not followed by a
     * digit, and what lies between actually looks like a formula. "It costs $5 and $10 more" fails
     * three of those, which is the point of having them.
     *
     * @return characters consumed, or 0 when this is not a formula this UI will draw
     */
    private static int math(String text, int at, Element parent, StringBuilder plain) {
        for (String[] pair : INLINE_MATH) {
            if (!text.startsWith(pair[0], at)) {
                continue;
            }
            int from = at + pair[0].length();
            int end = text.indexOf(pair[1], from);
            if (end < 0) {
                continue;
            }
            int consumed = typeset(text.substring(from, end), parent, plain,
                    end + pair[1].length() - at);
            if (consumed > 0) {
                return consumed;
            }
        }
        return text.charAt(at) == '$' ? dollarMath(text, at, parent, plain) : 0;
    }

    /** @return characters consumed, or 0 when this dollar does not open a formula */
    private static int dollarMath(String text, int at, Element parent, StringBuilder plain) {
        int from = at + 1;
        if (from >= text.length()) {
            return 0;
        }
        char after = text.charAt(from);
        if (after == '$' || Character.isWhitespace(after)) {
            return 0;
        }
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                // An escaped dollar belongs to the formula rather than closing it.
                i++;
                continue;
            }
            if (c != '$') {
                continue;
            }
            if (Character.isWhitespace(text.charAt(i - 1))) {
                continue;
            }
            if (i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                // "$5 and $10": the second dollar opens a price, so the first closed nothing.
                return 0;
            }
            String formula = text.substring(from, i);
            return Latex.plausible(formula) ? typeset(formula, parent, plain, i + 1 - at) : 0;
        }
        return 0;
    }

    /**
     * Draws a formula, or reports that it drew nothing.
     *
     * @param formula  the text between the delimiters
     * @param consumed what to report when it was drawn
     * @return {@code consumed}, or 0 when {@link Latex} refused the formula - in which case the
     *         delimiters and everything between them stay the characters the model sent
     */
    private static int typeset(String formula, Element parent, StringBuilder plain, int consumed) {
        Optional<Element> math = Latex.render(formula, false);
        if (math.isEmpty()) {
            return 0;
        }
        flush(plain, parent);
        parent.appendChild(math.get());
        return consumed;
    }

    /** @return characters consumed, or 0 when this bracket does not open a link */
    private static int link(String text, int at, Element parent, StringBuilder plain, int depth) {
        Matcher matcher = LINK.matcher(text);
        if (!matcher.find(at) || matcher.start() != at) {
            return 0;
        }
        flush(plain, parent);
        String href = safeHref(matcher.group(2));
        if (href == null) {
            // The target is not something this UI will navigate to, but the label is still the
            // reader's text and is shown rather than dropped. It is not inside a link, so a URL
            // written in it may still become one.
            inline(matcher.group(1), parent, depth + 1);
        } else {
            Element link = new Element("a");
            link.setAttribute("href", href);
            link.setAttribute("target", "_blank");
            // Denies the opened page any handle on this one, and any referrer it did not need.
            link.setAttribute("rel", "noopener noreferrer nofollow");
            inline(matcher.group(1), link, depth + 1, true);
            parent.appendChild(link);
        }
        return matcher.end() - at;
    }

    /**
     * Allow-lists the schemes a link may carry.
     *
     * <p>Deny by default: anything not named here - {@code javascript:}, {@code data:}, a scheme
     * invented tomorrow - is not an href, because the text being parsed came out of a model and may
     * have come out of a web page before that.
     *
     * @param raw target as written in the answer
     * @return a target safe to put in an href, or null when there is none
     */
    private static String safeHref(String raw) {
        String href = raw.strip();
        if (href.isEmpty() || href.length() > 2048) {
            return null;
        }
        String scheme = href.toLowerCase(Locale.ROOT);
        if (scheme.startsWith("http://") || scheme.startsWith("https://") || scheme.startsWith("mailto:")) {
            return href;
        }
        // A same-document or same-origin target, which cannot name a scheme at all.
        if ((href.startsWith("#") || href.startsWith("/")) && !href.startsWith("//")) {
            return href;
        }
        return null;
    }

    private static void flush(StringBuilder plain, Element parent) {
        if (plain.isEmpty()) {
            return;
        }
        parent.appendChild(Element.createText(plain.toString()));
        plain.setLength(0);
    }

    private Markdown() {
    }
}
