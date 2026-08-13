package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * An answer being rendered while it is still being written.
 *
 * <p>{@link Markdown} answers what text means; this answers <em>when</em> it is safe to say so. A
 * block that has been rendered into the transcript cannot be revised by a later token, so the cost
 * of settling one early is not cosmetic: a table missing its last row, or a sentence cut mid-clause,
 * is frozen into the answer as though the model had meant to stop there.
 *
 * <h2>Where a block stops being able to change</h2>
 *
 * <p>At a blank line outside a code fence, and nowhere else. Markdown has no construct that reaches
 * back across one: whatever arrives next opens a new block. That makes the buffer sliceable - the
 * text before the last such line parses the same alone as it does in the finished answer - so each
 * settled run is rendered once, appended, and never touched again. Everything after it stays plain
 * text, because a paragraph is still a paragraph until something ends it, and a table can always
 * grow another row.
 *
 * <p>The consequence is that a block appears one beat after its final line rather than as it is
 * typed. That is the intended trade: the alternative is re-rendering the whole answer on every
 * token, which pushes a growing subtree over the wire dozens of times a second.
 *
 * <h2>What the unsettled part still gets</h2>
 *
 * <p>Settling and typesetting are not the same question, and treating them as one was a defect the
 * reader met on every list. Ask an agent for one item per line - the commonest shape there is - and
 * the answer contains no blank line until it ends, so nothing settles and the entire answer is the
 * unsettled tail for the entire turn. Drawn as its own source, that is a screen of {@code **} runs
 * and backticks that reflows into type at the last moment.
 *
 * <p>So the tail is typeset too. Mostly with the inline half of the grammar, which is sound for the
 * same reason block settling is not: no inline construct here spans a line, so a delimiter pair
 * that has closed cannot be reopened by what arrives next. The one run still being typed keeps its
 * delimiters, because until the closing one lands there is nothing to say it was emphasis. The tail
 * is re-rendered rather than appended to, which is what {@link #tail} costs and why it is bounded
 * by the settled boundary above it.
 *
 * <p>Two block constructs are drawn in the tail as well - a fenced block and a table - and the
 * reason is that they are the two whose syntax is unreadable while it is half written: a delimiter
 * row is pure punctuation, and a code fence with its source under it in the prose font is neither
 * prose nor code. Drawing them costs nothing that settling would, because the tail is re-derived
 * from the buffer on every token: what is drawn here is never committed to, and is redrawn as a
 * paragraph the moment the next token says it was one. See {@link #typeset}.
 *
 * <p>Not thread safe. One instance belongs to one turn, and every call is made on the UI thread.
 */
final class StreamingProse {

    private final StringBuilder buffer = new StringBuilder();

    /** How much of {@link #buffer} has already been rendered and handed to the transcript. */
    private int settled;

    /**
     * Takes the next piece of the answer.
     *
     * @param token arriving text, possibly null or empty
     * @return the blocks that became final because of this token, in order; empty on most tokens
     */
    List<Element> absorb(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        buffer.append(token);
        int boundary = lastSettledIndex();
        if (boundary <= settled) {
            return List.of();
        }
        List<Element> blocks = Markdown.render(buffer.substring(settled, boundary));
        settled = boundary;
        return blocks;
    }

    /**
     * The block still being written, typeset as far as it can honestly be.
     *
     * <p>Rendered fresh on every call and meant to replace whatever the tail was showing before,
     * because it is the one part of the answer a later token is still allowed to change. What comes
     * back carries emphasis, code spans and links, plus the two block constructs {@link #typeset}
     * explains: a fenced block and a table, both redrawn from the buffer rather than committed to.
     *
     * @return detached nodes in order, empty when nothing is pending
     */
    List<Element> tail() {
        return typeset(tailText());
    }

    /** The block still being written, exactly as the model wrote it. */
    String tailText() {
        return buffer.substring(settled);
    }

    /**
     * Typesets the pending text, healing the two blocks whose syntax is unreadable half-written.
     *
     * <h2>Why the inline grammar alone was not enough</h2>
     *
     * <p>The tail used to be handed to the inline parser whole, with one exception for the inside
     * of a fence, where a backtick pair is an identifier a reader is being shown. That left the
     * fence line itself and every pipe of a table on screen as body prose. An audit of the running
     * build measured a 20-row table showing raw pipes for 4.27s, the delimiter row included, and a
     * fenced block showing {@code ```bash} above its own source set in the 15px proportional prose
     * font with no {@code pre} in the document at all. The reference product opens the code panel
     * on the opening fence and never renders a delimiter row.
     *
     * <p>Both of those are block constructs, which is why they were excluded - block structure
     * genuinely cannot be settled from a half-written answer. But settling and typesetting are two
     * questions, and this is the second one: the tail is re-derived from the buffer on every token,
     * so a block drawn here is never committed to. It can be redrawn as a paragraph the moment the
     * next token says it was one, which is exactly what the settled part cannot do.
     *
     * <p>So a run that a complete block grammar can read is cut out and given to {@link Markdown},
     * and everything else stays inline flow. A fence needs no repair to be read - the parser
     * already tolerates an unterminated one, which is what a stopped answer leaves - and a table is
     * handed over only from the point its delimiter row is complete, which is the point it is
     * decidably a table.
     *
     * @param text the pending run, possibly empty
     */
    private static List<Element> typeset(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = List.of(text.split("\n", -1));
        List<Element> out = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        boolean started = false;

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String marker = fenceMarker(line);
            if (marker != null) {
                flushProse(prose, out);
                started = false;
                i = codeRun(lines, i, marker, out);
                continue;
            }
            int table = Markdown.tableEnd(lines, i);
            if (table > i) {
                flushProse(prose, out);
                started = false;
                out.addAll(Markdown.render(String.join("\n", lines.subList(i, table))));
                i = table;
                continue;
            }
            if (i == lines.size() - 1 && isTableSyntax(line)) {
                // A delimiter row being typed. It carries no content at all - it says which way the
                // columns line up - so there is nothing to show and a second of pipes to avoid.
                break;
            }
            if (started) {
                prose.append('\n');
            }
            prose.append(line);
            started = true;
            i++;
        }
        flushProse(prose, out);
        return out;
    }

    /** Typesets whatever prose has accumulated and empties the buffer. */
    private static void flushProse(StringBuilder prose, List<Element> out) {
        if (prose.isEmpty()) {
            return;
        }
        out.addAll(Markdown.inlineFlow(prose.toString()));
        prose.setLength(0);
    }

    /**
     * Draws the fenced block that opens on this line, closed or not.
     *
     * <p>Handed to the block parser rather than printed, so the reader gets the code panel, the
     * monospace and the line numbering from the moment the fence arrives rather than once the
     * closing one does - and never sees the delimiter, which is pure syntax. Inside the block
     * nothing is markup, which the parser already guarantees and is the property the old text-node
     * treatment was protecting.
     *
     * @param lines the pending lines
     * @param start the line carrying the opening fence
     * @param fence the marker that opened it, which is the only thing that can close it
     * @param out   where the block is appended
     * @return the line to continue from: past the closing fence, or the end of the pending text
     */
    private static int codeRun(List<String> lines, int start, String fence, List<Element> out) {
        int end = start + 1;
        while (end < lines.size() && !fence.equals(fenceMarker(lines.get(end)))) {
            end++;
        }
        int past = Math.min(end + 1, lines.size());
        out.addAll(Markdown.render(String.join("\n", lines.subList(start, past))));
        return past;
    }

    /**
     * Whether a line is table syntax and nothing else.
     *
     * <p>Only ever asked of the last line of the tail, which is the one still being typed. A line
     * of pipes, dashes and colons with no word in it is a delimiter row on its way to being
     * complete: it is the one piece of markdown that is never meant to be seen, and holding it back
     * for the moment it takes to finish costs the reader nothing. A dash is required as well as a
     * pipe, so an empty row of cells is not swallowed by this.
     */
    private static boolean isTableSyntax(String line) {
        String candidate = line.strip();
        if (candidate.isEmpty() || candidate.indexOf('|') < 0) {
            return false;
        }
        boolean shaped = false;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '-' || c == ':') {
                shaped = true;
            } else if (c != '|' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return shaped;
    }

    /**
     * Closes the answer, complete or cut short.
     *
     * @return whatever had not settled yet, rendered as it stands - an unterminated fence included,
     *         since a stopped answer is still worth reading
     */
    List<Element> finish() {
        List<Element> blocks = Markdown.render(buffer.substring(settled));
        settled = buffer.length();
        return blocks;
    }

    /**
     * The index just past the last blank line that closed a block.
     *
     * <p>Only a <em>terminated</em> line counts. A buffer ending in {@code "Alpha.\n"} has no blank
     * line yet - the next token may continue that very line - and a fence's own blank lines belong
     * to the fence, not to the prose around it.
     *
     * @return an index into the buffer, never less than {@link #settled}
     */
    private int lastSettledIndex() {
        int boundary = settled;
        String fence = null;
        int lineStart = 0;

        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) != '\n') {
                continue;
            }
            String line = buffer.substring(lineStart, i);
            String marker = fenceMarker(line);
            if (fence == null && marker != null) {
                fence = marker;
            } else if (fence != null && fence.equals(marker)) {
                fence = null;
            } else if (fence == null && line.isBlank()) {
                boundary = i + 1;
            }
            lineStart = i + 1;
        }
        return boundary;
    }

    /**
     * @param line one complete line of the answer
     * @return the fence marker that opens or closes a code block here, or null when this line is
     *         ordinary text
     */
    private static String fenceMarker(String line) {
        String start = line.stripLeading();
        // More than three leading spaces is indented content rather than a fence, which is the same
        // threshold the parser applies.
        if (line.length() - start.length() > 3) {
            return null;
        }
        if (start.startsWith("```")) {
            return "```";
        }
        return start.startsWith("~~~") ? "~~~" : null;
    }
}
