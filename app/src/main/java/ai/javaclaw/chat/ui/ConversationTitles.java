package ai.javaclaw.chat.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Names a conversation from what was said in it, and keeps two conversations from sharing a name.
 *
 * <h2>Why a title is not the first message</h2>
 *
 * <p>The chat store records no title, so the only material available is the opening message. Passing
 * that through raw produced a column of rows nobody could navigate: people wrap a request in
 * scaffolding - "explain", "in one sentence", "one per line", "keep it under 150 words" - and the
 * scaffolding is at the front, where a 230px column spends all its width before reaching the subject.
 * Nine real conversations rendered as two byte-identical rows and six sharing a seventeen-character
 * prefix. The fallback was a hover tooltip, which does not exist on a phone.
 *
 * <p>So the opening message is reduced to its topic: the request verb, the opening format directive
 * and the trailing style instruction are removed, because none of them describes what the thread is
 * about. Nothing is added. This is not summarisation by a model - it is removal of the parts that are
 * known not to be the subject, which is deterministic, testable, and cannot invent a topic the
 * conversation does not have.
 *
 * <h2>Why uniqueness is enforced separately</h2>
 *
 * <p>Two threads can genuinely open with the same words. Reducing both to the same topic is correct
 * and still unusable, so {@link #forColumn} takes the set the column is about to paint and guarantees
 * no two labels are equal <em>at the width they will be drawn at</em> - lengthening a label would not
 * help, because the browser's ellipsis would cut it back to the same string.
 *
 * <h2>Boundary</h2>
 *
 * <p>The input is a chat message: unbounded, attacker-influenced in a multi-channel install, and
 * about to become a row label. It is length-capped before any pattern touches it, every pattern is
 * anchored and bounded, and the rewrite loop runs a fixed number of passes - so the cost of naming a
 * conversation cannot be driven by what was typed into it.
 */
final class ConversationTitles {

    /**
     * The longest title this class will ever return.
     *
     * <p>Titles are held untruncated in {@link ConversationSummary} so the sidebar can decide its own
     * width and so {@link #forColumn} has material to tell two rows apart with. "Untruncated" still
     * needs a ceiling: without one, a conversation opened by pasting a file would carry that file
     * around as its name.
     */
    static final int MAX_TITLE = 120;

    /** Longer than this and the tail cannot affect the title, so the patterns never see it. */
    private static final int MAX_SOURCE = 400;

    /** What a row is called when the opening message was nothing but scaffolding. */
    private static final String UNTITLED = "Untitled chat";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Openings that address the assistant rather than state the subject.
     *
     * <p>Applied in order, each at most once per pass, so "Can you please explain X" loses all three
     * words and "Explain the explain keyword" loses only the first.
     */
    private static final List<Pattern> LEAD_INS = List.of(
            Pattern.compile("^(?:please|hey|hi|ok|okay)\\b[,.:]?\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?", Pattern.CASE_INSENSITIVE),
            // An opening format directive: "In one sentence, ", "In markdown: ", "Using a table - ".
            Pattern.compile("^(?:in|using|with|as)\\s+(?:[\\w-]+\\s+){0,3}"
                    + "(?:markdown|json|yaml|xml|tables?|bullets?|bullet\\s+points?|lists?|"
                    + "sentences?|words?|lines?|paragraphs?|code)\\b\\s*[,:;-]\\s*",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:explain|describe|list|write|summarise|summarize|compare|define|create|"
                    + "generate|draft|outline|produce|show\\s+me|give\\s+me|tell\\s+me\\s+about|tell\\s+me|"
                    + "help\\s+me\\s+with|help\\s+me|i\\s+(?:want|need)\\s+(?:you\\s+to\\s+)?)\\s+",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Closings that describe the shape of the answer rather than the subject.
     *
     * <p>Bounded on both sides. Each pattern ends the string and matches a bounded run of characters,
     * so none of them can backtrack across a long message.
     */
    private static final List<Pattern> TRAILERS = List.of(
            Pattern.compile("[.,;]?\\s*(?:and\\s+)?(?:please\\s+)?(?:in|using|with)\\s+(?:exactly\\s+)?"
                    + "(?:\\d{1,3}|one|two|three|four|five|a\\s+few|several)\\s+"
                    + "(?:sentences?|words?|lines?|paragraphs?|bullet\\s+points?|bullets?)\\.?$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("[.,;]?\\s*(?:and\\s+)?(?:in|using|with|as)\\s+(?:a\\s+)?"
                    + "(?:markdown|json|yaml|xml|a\\s+table|table|list|bullet\\s+points?)\\.?$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("[.,;]?\\s*keep\\s+it\\s[^,;]{0,48}$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[.,;]?\\s*(?:no\\s+more\\s+than|at\\s+most|under)\\s[^,;]{0,40}$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("[.,;]?\\s*one\\s+per\\s+line\\.?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[.,;]?\\s*each\\s+(?:one\\s+)?(?:starting|beginning|prefixed)\\s[^,;]{0,64}$",
                    Pattern.CASE_INSENSITIVE));

    private static final Pattern LEADING_ARTICLE =
            Pattern.compile("^(?:the|a|an)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s.,;:!?-]+$");

    /** Rewrite passes. Four is one more than the deepest nesting any real instruction has shown. */
    private static final int MAX_PASSES = 4;

    private ConversationTitles() {
    }

    /**
     * The topic of a conversation, from the message that opened it.
     *
     * <p>Never blank and never longer than {@link #MAX_TITLE}: a row with no name is a worse outcome
     * than a row named after its scaffolding, so a message that is entirely scaffolding keeps it.
     *
     * @param openingMessage the first thing said in the conversation; null and blank are tolerated
     * @return a single line naming the thread
     */
    static String topic(String openingMessage) {
        String flat = flatten(openingMessage);
        if (flat.isEmpty()) {
            return UNTITLED;
        }

        String working = flat;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String before = working;
            working = stripOnce(working);
            if (working.equals(before)) {
                break;
            }
        }

        working = LEADING_ARTICLE.matcher(working).replaceFirst("");
        working = TRAILING_PUNCTUATION.matcher(working).replaceFirst("").strip();
        // Stripping is only ever an improvement while something survives it. When it is not, the
        // reader is better served by the raw opening than by a blank row or by "Untitled chat".
        if (working.isBlank()) {
            working = TRAILING_PUNCTUATION.matcher(flat).replaceFirst("").strip();
        }
        if (working.isBlank()) {
            return UNTITLED;
        }
        return shorten(capitalise(working), MAX_TITLE);
    }

    /**
     * One rewrite pass: at most one lead-in and one trailer of each kind.
     *
     * <p>Split out from the loop so a pattern that matches what a previous pattern uncovered gets its
     * turn, without any single pattern being allowed to run away.
     */
    private static String stripOnce(String text) {
        String working = text;
        for (Pattern leadIn : LEAD_INS) {
            String candidate = leadIn.matcher(working).replaceFirst("");
            // A strip that leaves nothing has removed the subject rather than the scaffolding.
            if (!candidate.isBlank()) {
                working = candidate;
            }
        }
        for (Pattern trailer : TRAILERS) {
            String candidate = trailer.matcher(working).replaceFirst("");
            if (!candidate.isBlank()) {
                working = candidate;
            }
        }
        return working.strip();
    }

    /**
     * Labels for the rows a column is about to paint, no two of them equal.
     *
     * <p>Equality is judged at the truncated length, because that is the string the reader sees: two
     * labels that differ only past the ellipsis are the same label. Where a truncation collides, the
     * shared opening is elided so that the part which separates the rows moves into view; where the
     * threads are identical to their last character, an occurrence marker is appended, which is the
     * only honest thing left to say about two conversations that really did start the same way.
     *
     * @param rows  the conversations in the order they will be drawn
     * @param limit characters the column can show before the browser ellipsises
     * @return conversation id to the label to draw, one entry per distinct id
     */
    static Map<String, String> forColumn(List<ConversationSummary> rows, int limit) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return labels;
        }
        int width = Math.max(8, limit);

        // Distinct ids only: the same conversation offered twice is one row's worth of naming, and
        // keying by id is what lets the caller look a label up without tracking positions.
        Map<String, String> sources = new LinkedHashMap<>();
        for (ConversationSummary row : rows) {
            sources.putIfAbsent(row.id(), row.title());
        }

        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        sources.forEach((id, title) ->
                byLabel.computeIfAbsent(shorten(title, width), key -> new ArrayList<>()).add(id));

        byLabel.forEach((label, ids) -> {
            if (ids.size() == 1) {
                labels.put(ids.getFirst(), label);
                return;
            }
            List<String> collidingTitles = ids.stream().map(sources::get).toList();
            List<String> elided = elide(collidingTitles, width);
            for (int index = 0; index < ids.size(); index++) {
                labels.put(ids.get(index), elided.get(index));
            }
        });

        // The invariant is enforced here rather than trusted from the branches above. Eliding a
        // shared opening usually separates a cluster, but it cannot separate two threads that are
        // identical to their last character, and nothing stops an elided label from landing on a
        // label some other cluster already took. This sweep is what makes "no two labels are equal"
        // true by construction instead of by argument.
        return markRemainingDuplicates(labels, width);
    }

    /**
     * Shows a colliding cluster what it does not share, when there is anything it does not share.
     *
     * @param titles the full topics of the rows that truncated to the same string
     * @param width  characters the column can show
     * @return one label per title, in the same order; possibly still containing duplicates
     */
    private static List<String> elide(List<String> titles, int width) {
        String shared = sharedOpening(titles);
        if (shared.isEmpty()) {
            return titles.stream().map(title -> shorten(title, width)).toList();
        }
        // The ellipsis is load-bearing: it says the row is named after part of a longer topic,
        // rather than claiming the remainder is the whole of it.
        return titles.stream()
                .map(title -> title.length() > shared.length()
                        ? shorten("…" + title.substring(shared.length()).strip(), width)
                        : shorten(title, width))
                .toList();
    }

    /**
     * The last resort, and the only one that always works: say which of the identical rows this is.
     *
     * <p>An occurrence marker claims nothing about the conversation. It is what is left to say when
     * two threads really did open with the same words, and it is still more use to a reader than two
     * rows they cannot tell apart. Numbering follows the order the column draws, so the marker on a
     * given row does not change between two renders of the same list.
     *
     * <p>It goes in front. The column ellipsises with CSS at whatever width the glyphs happen to
     * need, on top of the character count used here - measured, at 231px a 35-character label is
     * already being clipped - and a trailing marker is the first thing that clipping removes. A
     * marker that can be truncated away is not a disambiguator, it is a decoration that usually
     * works. In front, no amount of overflow can reach it.
     */
    private static Map<String, String> markRemainingDuplicates(Map<String, String> labels, int width) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        labels.forEach((id, label) -> {
            int occurrence = seen.merge(label, 1, Integer::sum);
            if (occurrence == 1) {
                resolved.put(id, label);
                return;
            }
            String marker = "(" + occurrence + ") ";
            resolved.put(id, marker + shorten(label, Math.max(4, width - marker.length())));
        });
        return resolved;
    }

    /**
     * The longest run of whole words every one of these titles begins with.
     *
     * <p>Whole words, because eliding mid-word turns "…ual threads" into a typo. Empty unless the run
     * is at least two words long and every title has at least one word left after it - a one-word
     * elision saves nothing, and eliding everything leaves a row with no name at all.
     *
     * <p>Also empty when the titles are identical. Eliding what they share then leaves only the last
     * word of a sentence they both end with, which is how two conversations about fifteen Java
     * features came to be labelled "…21" and "…21 (2)": distinct, fitting, and no use to anybody.
     * Threads that are the same to their last character have nothing to expose, and are separated by
     * the occurrence marker instead.
     */
    private static String sharedOpening(List<String> titles) {
        if (titles.size() < 2 || titles.stream().distinct().count() < 2) {
            return "";
        }
        List<String[]> words = titles.stream().map(title -> title.split(" ")).toList();
        int shortest = words.stream().mapToInt(parts -> parts.length).min().orElse(0);

        int common = 0;
        while (common < shortest - 1) {
            int position = common;
            String candidate = words.getFirst()[position];
            if (!words.stream().allMatch(parts -> parts[position].equals(candidate))) {
                break;
            }
            common++;
        }
        if (common < 2) {
            return "";
        }
        return String.join(" ", List.of(words.getFirst()).subList(0, common)) + " ";
    }

    /**
     * Cuts at a word boundary where there is one nearby, so a label does not end mid-word.
     *
     * @param text  the string to fit
     * @param limit the length the result must not exceed, ellipsis included
     */
    static String shorten(String text, int limit) {
        String flat = flatten(text);
        if (limit <= 1) {
            return flat.isEmpty() ? flat : "…";
        }
        if (flat.length() <= limit) {
            return flat;
        }
        String cut = flat.substring(0, limit - 1);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > limit / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }

    /**
     * One line, one space between words, and bounded before any pattern is run over it.
     *
     * <p>The markdown comes out first, and that is not cosmetic. Everything this class produces is
     * a <em>description</em> of a conversation - the label on a row, the excerpt in a tooltip, the
     * name in the bar - and descriptions are read out loud. A message opening with a heading gave a
     * screen reader "hash hash Java Ecosystem" and one opening with a fence gave it "backtick
     * backtick backtick java pub": punctuation that exists to be rendered, read as words to the one
     * reader who never sees it rendered. It was wrong on screen too, just quieter, because the
     * column had been drawing the source of a heading rather than its text.
     *
     * <p>A message with no prose in it at all - a bare code block - projects to nothing, and the
     * callers here already answer that with {@code Untitled chat} or with the conversation's title.
     * An invented description would be worse than an admitted absence.
     *
     * @param text the raw message text, possibly null
     * @return the prose in it on one line, empty when there is none
     */
    private static String flatten(String text) {
        if (text == null) {
            return "";
        }
        String bounded = text.length() > MAX_SOURCE ? text.substring(0, MAX_SOURCE) : text;
        return WHITESPACE.matcher(Markdown.plainText(bounded)).replaceAll(" ").strip();
    }

    /**
     * Sentence case for the first letter only.
     *
     * <p>The rest is left exactly as typed: upper-casing further would turn "iPhone" into "IPhone"
     * and "JVM" is already right.
     */
    private static String capitalise(String text) {
        char first = text.charAt(0);
        if (!Character.isLowerCase(first)) {
            return text;
        }
        return Character.toUpperCase(first) + text.substring(1);
    }
}
