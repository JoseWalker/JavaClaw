package ai.javaclaw.chat.ui;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <h2>Why being told apart is enforced separately</h2>
 *
 * <p>Two threads can genuinely open with the same words. Reducing both to the same topic is correct
 * and still unusable, so {@link #forColumn} takes the set the column is about to paint and separates
 * it. What it guarantees is not that the labels are <em>unequal</em>: that was the promise this class
 * used to make, and it is the wrong one. Twenty threads whose topics share a twenty-nine character
 * opening and differ only in a trailing ordinal produce twenty unequal labels and twenty rows reading
 * "How do I configure the Spring…", because the difference is past the ellipsis on a narrow column and
 * past the reader's eye on a wide one. Unequal is a property of the strings; distinguishable is a
 * property of the rows, and the rows are what the reader has.
 *
 * <p>So the guarantee is stated where it is read: no two labels agree over their first
 * {@link #GLANCE} display columns. See {@link #forColumn} for how that is reached, and what is given
 * up to reach it.
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

    /**
     * Columns of a label a reader takes in while scanning a column, rather than reading it.
     *
     * <p>This is the width the distinguishing part of a label has to fall inside, and it is the whole
     * of what {@link #forColumn} guarantees. Twenty rows that differ at character thirty are twenty
     * rows nobody can tell apart: the browser's own ellipsis lands before the difference on a wide
     * glyph run, and where it does not, the eye still stops at the left edge of the column and never
     * reaches the right one.
     *
     * <p>Sixteen, from the same measurement the column's width comes from: the title box is 231px of
     * a 13px system stack and fits 38 latin characters, so sixteen columns is its leading 97px - a
     * little over a third of the row, and comfortably inside the 29-to-35 characters at which CSS
     * starts clipping a wide label. A difference inside sixteen columns therefore survives both
     * truncations, this one and the browser's.
     *
     * <p>Twenty-four was rejected: at two thirds of the row it is a width the reader has to read
     * rather than scan, and the set that produced this rule shared twenty-nine characters, so a
     * budget that close to the column lets the next such set straight through. Four to eight was
     * rejected in the other direction: it would elide "Vaadin session locking" and "Vaadin routing"
     * down to "…session locking" and "…routing", spending a topic to solve a collision that was not
     * happening.
     */
    static final int GLANCE = 16;

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
     * Labels for the rows a column is about to paint, no two of them alike where they are read.
     *
     * <h2>The rule</h2>
     *
     * <p>No two returned labels agree over their first {@link #GLANCE} display columns. Not "no two
     * are equal": a set can be pairwise unequal and still render as twenty identical rows, which is
     * exactly the defect this replaced. Distinctness at the glance is the strictly stronger property,
     * and it is also monotone - labels that differ within sixteen columns differ at every width wider
     * than sixteen, so the guarantee holds however the browser's own ellipsis lands on top of the
     * character budget.
     *
     * <h2>How it is reached</h2>
     *
     * <p>Rows whose labels agree over the glance are gathered and shown what they do not share, in
     * this order, escalating only as far as each cluster forces:
     *
     * <ol>
     *   <li>The shared opening is elided at a <em>word</em> boundary, so what remains is readable
     *       words rather than the tail of one. This is the good case and the common one.</li>
     *   <li>Where whole words are no help - one long unbroken token differing in its last digits,
     *       which is what a pasted id or build number looks like - the cut is made at the character
     *       where the titles actually diverge. Mid-word, deliberately: a mid-word cut is a poor label
     *       and an unreadable column is not a label at all.</li>
     *   <li>Threads identical to their last character have nothing to expose, and take a leading
     *       occurrence marker. It claims nothing about the conversation, which is the honest thing to
     *       say about two threads that really did open with the same words.</li>
     * </ol>
     *
     * <p>The marker goes in front rather than behind. The column ellipsises with CSS at whatever
     * width the glyphs happen to need, on top of the character count used here - measured, at 231px a
     * 35-character label is already being clipped - and a trailing marker is the first thing that
     * clipping removes. A marker that can be truncated away is a decoration that usually works.
     *
     * <h2>What this gives up</h2>
     *
     * <p>Twenty threads that really do share their first twenty-nine characters come out as "…1"
     * through "…20": the shared subject is gone from the column and only the ordinal remains. That is
     * the intended trade, not a shortfall. The shared part is by definition the part that told the
     * reader nothing, the row's {@code title} attribute still carries the whole of it, and the
     * alternative - keeping the subject and appending the ordinal - is the shape that was reported as
     * unusable. Showing the shared subject once, on the group heading, would be better still, but
     * that is the sidebar's layout to change and not this class's to decide.
     *
     * <h2>Bounds and ordering</h2>
     *
     * <p>Numbering follows the order the column draws, so a given row's marker does not change
     * between two renders of the same list. Every returned label fits {@code limit} in characters and
     * in display columns. The marker search is bounded by the number of rows and needs at most one
     * attempt more than there are labels already placed, because two different markers differ inside
     * the glance - which holds up to a six-digit marker even at the floor of eight columns, and so up
     * to more rows than a column could paint.
     *
     * @param rows  the conversations in the order they will be drawn; null entries are skipped, and a
     *              row with a blank title is named rather than drawn empty
     * @param limit characters the column can show before the browser ellipsises; floored at 8
     * @return conversation id to the label to draw, one entry per distinct id, in row order; empty
     *         when there is nothing to draw
     */
    static Map<String, String> forColumn(List<ConversationSummary> rows, int limit) {
        if (rows == null || rows.isEmpty()) {
            return new LinkedHashMap<>();
        }
        int width = Math.max(8, limit);
        int glance = Math.min(GLANCE, width);

        // Distinct ids only: the same conversation offered twice is one row's worth of naming, and
        // keying by id is what lets the caller look a label up without tracking positions.
        Map<String, String> sources = new LinkedHashMap<>();
        for (ConversationSummary row : rows) {
            // A null row is not this class's to explain. Skipping it costs one label; dereferencing
            // it costs the whole column.
            if (row != null) {
                sources.putIfAbsent(row.id(), nameOf(row.title()));
            }
        }

        // Clustering on the glance rather than on the whole truncated label is the fix. The old key
        // was the full-width truncation, which let a set differing at character thirty of a
        // thirty-eight character budget through untouched.
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        sources.forEach((id, title) -> clusters
                .computeIfAbsent(glanceKey(shorten(title, width), glance), key -> new ArrayList<>())
                .add(id));

        Map<String, String> separated = new LinkedHashMap<>();
        clusters.values().forEach(ids -> {
            List<String> titles = ids.stream().map(sources::get).toList();
            List<String> labels = ids.size() == 1
                    ? List.of(shorten(titles.getFirst(), width))
                    : separate(titles, width, glance);
            for (int index = 0; index < ids.size(); index++) {
                separated.put(ids.get(index), labels.get(index));
            }
        });

        // Back into row order before the markers are handed out, so the numbering a reader sees runs
        // down the column rather than following the order the clusters happened to be built in.
        Map<String, String> ordered = new LinkedHashMap<>();
        sources.keySet().forEach(id -> ordered.put(id, separated.get(id)));

        // The property is enforced here rather than trusted from the branches above. Eliding usually
        // separates a cluster, but it cannot separate two threads identical to their last character,
        // and nothing stops an elided label from landing on one some other cluster already took.
        // This sweep is what makes the guarantee true by construction instead of by argument.
        return markRemainingCollisions(ordered, width, glance);
    }

    /**
     * A name for a row the store had nothing to call.
     *
     * <p>{@link #topic} never returns blank, but the title on a summary can come from a model or from
     * a store that answered with nothing, and a blank label draws an empty row - which reads as a
     * failure to load rather than as a conversation without a name.
     *
     * @param title the summary's title, possibly null or blank
     * @return the title, or {@link #UNTITLED} when there was not one
     */
    private static String nameOf(String title) {
        return title == null || title.isBlank() ? UNTITLED : title;
    }

    /**
     * Shows a colliding cluster what it does not share, by whichever cut is the least destructive.
     *
     * <p>Word-boundary elision is preferred because what it leaves is readable; the character-level
     * cut is taken only when the word-level one leaves the cluster still unreadable, and is abandoned
     * in turn when it cannot separate the cluster either - which happens exactly when the titles are
     * identical, and is what the occurrence marker is for.
     *
     * @param titles the full topics of the rows that read alike at a glance, in row order
     * @param width  characters the column can show
     * @param glance columns a reader takes in
     * @return one label per title, in the same order; possibly still colliding, for the caller's
     *         final sweep to resolve
     */
    private static List<String> separate(List<String> titles, int width, int glance) {
        List<String> byWord = elide(titles, width);
        if (!collides(byWord, glance)) {
            return byWord;
        }
        List<String> byCharacter = elideAtDivergence(titles, width);
        return collides(byCharacter, glance) ? byWord : byCharacter;
    }

    /**
     * Whether any two of these labels still read the same.
     *
     * @param labels labels being considered for the same column
     * @param glance columns a reader takes in
     * @return true when at least two of them agree over the glance
     */
    private static boolean collides(List<String> labels, int glance) {
        return labels.stream().map(label -> glanceKey(label, glance)).distinct().count() < labels.size();
    }

    /**
     * Shows a colliding cluster what it does not share, when there is anything it does not share.
     *
     * @param titles the full topics of the rows that read alike at a glance
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
     * Cuts where the titles actually part company, when no word boundary will do it.
     *
     * <p>The case this exists for is one long token - "build-000000000000001" against
     * "build-000000000000002" - where the only whitespace is outside the difference, so eliding whole
     * words either removes nothing or removes everything. Cutting at the divergence puts the digit
     * that differs at the front of the row, which is the whole of the information the reader has.
     *
     * <p>The cut is snapped back to a character boundary first. Splitting a surrogate pair leaves half
     * of an emoji, which the browser draws as a replacement box, and cutting between a letter and its
     * combining accent orphans the accent onto the ellipsis.
     *
     * @param titles the full topics that read alike at a glance
     * @param width  characters the column can show
     * @return one label per title in the same order, or the merely shortened titles when there is no
     *         divergence to cut at - which is the case when they are identical, or when one is a
     *         prefix of another and would be cut away entirely
     */
    private static List<String> elideAtDivergence(List<String> titles, int width) {
        int shared = sharedPrefix(titles);
        if (shared == 0 || titles.stream().anyMatch(title -> title.length() <= shared)) {
            return titles.stream().map(title -> shorten(title, width)).toList();
        }
        return titles.stream().map(title -> shorten("…" + title.substring(shared), width)).toList();
    }

    /**
     * How much of these titles is character-for-character the same, ending on a character boundary.
     *
     * @param titles at least one title
     * @return an index into every title, at or before the first difference between any two of them
     */
    private static int sharedPrefix(List<String> titles) {
        String first = titles.getFirst();
        int shared = first.length();
        for (String title : titles) {
            shared = Math.min(shared, commonPrefixLength(first, title));
        }
        return graphemeFloor(first, shared);
    }

    /** How many leading {@code char}s two strings have in common, which may split a character. */
    private static int commonPrefixLength(String left, String right) {
        int bound = Math.min(left.length(), right.length());
        int index = 0;
        while (index < bound && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    /**
     * The character boundary at or before {@code index}, so no cut lands inside a drawn character.
     *
     * @param text  the string being cut
     * @param index a {@code char} offset into it, which may sit inside a surrogate pair or between a
     *              letter and its combining marks
     * @return a safe offset, never negative and never past {@code index}
     */
    private static int graphemeFloor(String text, int index) {
        if (index <= 0) {
            return 0;
        }
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        return characters.isBoundary(index) ? index : Math.max(0, characters.preceding(index));
    }

    /**
     * The leading columns of a label: what is on screen before the eye moves to the next row.
     *
     * <p>Two labels with the same key are two rows the reader cannot separate, whatever they do
     * further right. Keys are compared exactly, including case: two labels differing only in case are
     * left alone rather than elided, because collapsing them would spend a topic to solve a collision
     * that is not one.
     *
     * @param label  a label the column would draw
     * @param glance columns a reader takes in
     * @return the label's leading columns, or the whole label when it is shorter than that
     */
    private static String glanceKey(String label, int glance) {
        return label.substring(0, cutIndex(label, glance));
    }

    /**
     * The last resort, and the only one that always works: say which of the identical rows this is.
     *
     * <p>Applied against every label already assigned, not just against the cluster a label came from,
     * because eliding one cluster can land it on top of another. The marker is prepended and the label
     * re-shortened as a whole, so a marked label still fits the column.
     *
     * @param labels id to label, in the order the column draws them
     * @param width  characters the column can show
     * @param glance columns a reader takes in
     * @return the same ids in the same order, with markers where they were needed
     */
    private static Map<String, String> markRemainingCollisions(
            Map<String, String> labels, int width, int glance) {
        Set<String> taken = new HashSet<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        // Each attempt puts a different marker at the front, so each produces a different key; with
        // finitely many labels already placed, one of them is free well before this bound.
        int attempts = labels.size() + 2;
        labels.forEach((id, label) -> {
            String candidate = label;
            for (int occurrence = 2;
                 occurrence <= attempts && taken.contains(glanceKey(candidate, glance));
                 occurrence++) {
                candidate = shorten("(" + occurrence + ") " + label, width);
            }
            taken.add(glanceKey(candidate, glance));
            resolved.put(id, candidate);
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
     * <p>{@code limit} bounds the result twice over, because a label has two budgets and the reader
     * meets both: the column is a fixed number of pixels, and the record behind it is a fixed number
     * of characters. A run of CJK is half as many characters as it is columns wide, so counting
     * characters alone lets it overflow the box; a letter carrying three combining accents is one
     * column and four characters, so counting columns alone lets it overflow {@link #MAX_TITLE}.
     * Whichever budget runs out first ends the label.
     *
     * @param text  the string to fit; null and blank are tolerated
     * @param limit the length the result must not exceed in characters or in columns, ellipsis
     *              included; a limit of one or less yields a bare ellipsis
     * @return the fitted string, empty only when there was nothing to fit
     */
    static String shorten(String text, int limit) {
        String flat = flatten(text);
        if (limit <= 1) {
            return flat.isEmpty() ? flat : "…";
        }
        if (flat.length() <= limit && columns(flat) <= limit) {
            return flat;
        }
        String cut = flat.substring(0, cutIndex(flat, limit - 1));
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > limit / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }

    /**
     * Where to cut {@code text} so that what is kept fits {@code budget} characters and columns both.
     *
     * <p>Walks whole characters as the reader sees them - a base letter with its combining marks, an
     * emoji built from a surrogate pair - so a cut never lands inside one. A new iterator per call:
     * {@link BreakIterator} carries the text it is scanning as state and is not safe to share between
     * the threads that render two sessions.
     *
     * @param text   the string to cut
     * @param budget columns and characters available; zero or less keeps nothing
     * @return an offset into {@code text}, on a character boundary
     */
    private static int cutIndex(String text, int budget) {
        if (budget <= 0) {
            return 0;
        }
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        int used = 0;
        int accepted = 0;
        int start = characters.first();
        for (int end = characters.next(); end != BreakIterator.DONE; start = end, end = characters.next()) {
            // end doubles as the character count of everything kept so far, which is the second
            // budget: a cluster of combining marks is one column and several characters.
            if (used + columnsOf(text.codePointAt(start)) > budget || end > budget) {
                break;
            }
            used += columnsOf(text.codePointAt(start));
            accepted = end;
        }
        return accepted;
    }

    /**
     * How wide {@code text} is drawn, in columns of one latin character.
     *
     * @param text the string to measure
     * @return its width, never less than the number of characters a reader would count
     */
    private static int columns(String text) {
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        int total = 0;
        int start = characters.first();
        for (int end = characters.next(); end != BreakIterator.DONE; start = end, end = characters.next()) {
            total += columnsOf(text.codePointAt(start));
        }
        return total;
    }

    /**
     * How wide one character is drawn, relative to a latin one.
     *
     * <p>An approximation, and deliberately a cheap one: the ideographic scripts and emoji are the
     * cases where treating every character as one column costs the column half its width, and they
     * are answerable from the JDK's own Unicode data. The full East Asian Width table would buy a
     * more exact answer for a handful of punctuation marks, at the cost of carrying a table in a
     * sidebar. Only the base character of a cluster is measured; combining marks are drawn on top of
     * it and add nothing.
     *
     * @param codePoint the base character of a cluster
     * @return 2 for a character drawn at roughly double width, 1 otherwise
     */
    private static int columnsOf(int codePoint) {
        if (Character.isIdeographic(codePoint) || Character.isEmojiPresentation(codePoint)) {
            return 2;
        }
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> 2;
            default -> 1;
        };
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
