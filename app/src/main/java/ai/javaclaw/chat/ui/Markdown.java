package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * nesting, block quotes, thematic breaks, paragraphs, and inline emphasis, code spans and links.
 * Anything it does not recognise stays as the text it was - the failure mode is a plain paragraph,
 * never a swallowed sentence.
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
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~).*$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(\\s*([^)\\s]*)(?:\\s+\"[^\"]*\")?\\s*\\)");

    /** Punctuation a backslash may hide from the parser, as in CommonMark. */
    private static final String ESCAPABLE = "\\`*_{}[]()#+-.!>~|";

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
            } else {
                i = paragraph(lines, i, out, depth);
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
        Element block = new Element("code");
        block.setText(code.toString());
        Element pre = new Element("pre");
        pre.appendChild(block);
        out.add(pre);
        return i < lines.size() ? i + 1 : i;
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
            List<String> item = new ArrayList<>();
            item.add(marker.group(3));
            i++;
            i = collectItemBody(lines, i, indent, item);

            Element entry = new Element("li");
            appendItemContent(entry, item, depth);
            list.appendChild(entry);
        }
        out.add(list);
        return i;
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
            if (line.isBlank() || (i > start && opensBlock(line))) {
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
        if (depth >= MAX_DEPTH) {
            parent.appendChild(Element.createText(text));
            return;
        }
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

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
                int consumed = emphasis(text, i, parent, plain, depth);
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
            plain.append(c);
            i++;
        }
        flush(plain, parent);
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

    /** @return characters consumed, or 0 when the run has no closing delimiter */
    private static int emphasis(String text, int at, Element parent, StringBuilder plain, int depth) {
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
        inline(text.substring(from, end), emphasis, depth + 1);
        parent.appendChild(emphasis);
        return end + delimiter.length() - at;
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
            // reader's text and is shown rather than dropped.
            inline(matcher.group(1), parent, depth + 1);
        } else {
            Element link = new Element("a");
            link.setAttribute("href", href);
            link.setAttribute("target", "_blank");
            // Denies the opened page any handle on this one, and any referrer it did not need.
            link.setAttribute("rel", "noopener noreferrer nofollow");
            inline(matcher.group(1), link, depth + 1);
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
