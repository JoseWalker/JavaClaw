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
 * <p>So the tail is typeset too, with the inline half of the grammar only. That is sound for the
 * same reason block settling is not: no inline construct here spans a line, so a delimiter pair
 * that has closed cannot be reopened by what arrives next. The one run still being typed keeps its
 * delimiters, because until the closing one lands there is nothing to say it was emphasis. The tail
 * is re-rendered rather than appended to, which is what {@link #tail} costs and why it is bounded
 * by the settled boundary above it.
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
     * back carries emphasis, code spans and links but no block construct: see
     * {@link Markdown#inlineFlow} for why that line is drawn where it is.
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
     * Typesets the pending text, holding back the runs where markup is not markup.
     *
     * <p>A fence is the exception the rest of this class already knows about, and it matters twice
     * over here: inside one, a backtick pair is an identifier a reader is being shown and a
     * {@code **} run is two asterisks of somebody's code. So the pending text is walked line by
     * line and switched between two treatments at the fence markers, rather than handed to the
     * inline parser whole.
     *
     * @param text the pending run, possibly empty
     */
    private static List<Element> typeset(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<Element> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        boolean pendingIsCode = false;
        String fence = null;

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String marker = fenceMarker(lines[i]);
            boolean isCode;
            if (fence == null && marker != null) {
                fence = marker;
                isCode = true;
            } else if (fence != null && fence.equals(marker)) {
                fence = null;
                isCode = true;
            } else {
                isCode = fence != null;
            }
            if (isCode != pendingIsCode) {
                flush(pending, pendingIsCode, out);
                pendingIsCode = isCode;
            }
            if (i > 0) {
                pending.append('\n');
            }
            pending.append(lines[i]);
        }
        flush(pending, pendingIsCode, out);
        return out;
    }

    private static void flush(StringBuilder pending, boolean asCode, List<Element> out) {
        if (pending.isEmpty()) {
            return;
        }
        if (asCode) {
            out.add(Element.createText(pending.toString()));
        } else {
            out.addAll(Markdown.inlineFlow(pending.toString()));
        }
        pending.setLength(0);
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
