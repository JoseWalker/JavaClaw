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
 * <p>So the guarantee is stated where it is read: no two labels are alike over the part of the row
 * the column is certain to draw - {@link #DRAWN} display columns. See {@link #forColumn} for how that
 * is reached, and what is given up to reach it.
 *
 * <h2>Why the guarantee is about what is drawn rather than about what is scanned</h2>
 *
 * <p>It used to be the stronger claim: no two labels alike over the leading {@link #GLANCE} columns,
 * a third of the row. That is a fine thing to want and a ruinous thing to require. Requiring it made
 * every pair of rows sharing sixteen leading columns a collision to be dissolved, including pairs
 * that the column was drawing in full and that any reader could tell apart by reading them - so
 * "History of the JVM" was reduced to "…JVM" beside "…Java language", and a run of threads differing
 * only in their closing word came out as "…21" repeated sixteen times behind an occurrence marker. An
 * audit of the running application found those rows using 197px of a 197px box: nothing was being
 * clipped, and the elision had bought nothing and spent the topic.
 *
 * <p>Eliding is therefore triggered by what the column cannot show rather than by what a reader might
 * not scan, and the {@link #GLANCE} is demoted to what it can honestly be - the place a cut aims to
 * put the difference, when a cut has to be made at all.
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
     * Columns of a row the column is certain to draw, whatever the glyphs turn out to be.
     *
     * <p>This is the width two labels have to differ inside, and it is the whole of what
     * {@link #forColumn} guarantees. Past it a label is at the mercy of the browser's own ellipsis:
     * the title box is 231px of a 13px system stack, and a wide label was measured starting to clip
     * somewhere between twenty-nine and thirty-five characters depending on the glyphs in it. Inside
     * twenty-nine, every label measured was drawn whole.
     *
     * <p>So a difference that falls inside this many columns is a difference the reader is looking
     * at, and one that falls outside it may not have been rendered at all - which is the failure this
     * class exists to prevent, and the only one it should be spending a topic to prevent. The floor
     * of the measured range is taken rather than its middle: the cost of being one column too
     * cautious is a cut that was not needed, and the cost of being one column too bold is a row whose
     * only distinguishing mark was clipped away.
     */
    static final int DRAWN = 29;

    /**
     * Columns of a label a reader takes in while scanning a column, rather than reading it.
     *
     * <p>Where a cut <em>aims</em> to leave the difference, not the rule for whether to cut. Sixteen
     * is the leading 97px of the 231px box - a little over a third of the row - and a difference that
     * lands inside it is found by the eye moving down the column rather than across it.
     *
     * <p>It is an aim and not a requirement because insisting on it is what produced labels of eight
     * and nine characters: two threads whose only difference is their closing word cannot put that
     * word in the leading third without throwing the rest of the name away. Where the aim and
     * {@link #KEEP} disagree, {@link #KEEP} wins and the difference sits further right - still inside
     * {@link #DRAWN}, which is the part that is not negotiable.
     */
    static final int GLANCE = 16;

    /**
     * Columns of the title a label keeps, however far right the difference turns out to be.
     *
     * <p>A label is a name before it is a discriminator. "…21" is unique, fits, and names nothing;
     * sixteen rows of it behind an occurrence marker was the reported defect. Twelve columns is about
     * two ordinary words, which is the least that can carry a subject at all, and it is the floor a
     * cut is not allowed to go under while any earlier cut would still show the difference.
     *
     * <p>Half a bound, and worth saying which half. It is measured against the <em>longest</em> title
     * in a cluster, because measuring it against the shortest would demand labels that cannot exist
     * on a narrow column - and the longest says nothing about what a short title beside it has left,
     * which is how "…21" survived a first fix and was photographed a second time. The other half of
     * the bound is {@link #wordBeforeTheDifference}, which is about words rather than columns and
     * does not depend on the lengths in the cluster at all.
     */
    private static final int KEEP = 12;

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
     * Labels for the rows a column is about to paint, no two of them alike where they are drawn.
     *
     * <h2>The rule</h2>
     *
     * <p>No two returned labels agree over their first {@link #DRAWN} display columns. Not "no two
     * are equal": a set can be pairwise unequal and still render as twenty identical rows, because
     * the browser clips what does not fit and the clipped part is where the difference was. Equality
     * of the drawn part is the property the reader actually has.
     *
     * <p>It is also monotone in the right direction - labels that differ within twenty-nine columns
     * differ at every width wider than twenty-nine - so the guarantee survives whatever the browser's
     * own ellipsis does on top of the character budget used here.
     *
     * <h2>How it is reached</h2>
     *
     * <p>Rows whose labels are identical over the drawn part are gathered and shown what they do not
     * share, escalating only as far as each cluster forces:
     *
     * <ol>
     *   <li>The shared opening is elided, at a <em>word</em> boundary wherever one is available, so
     *       what remains is readable words rather than the tail of one. Where the only whitespace is
     *       outside the difference - one long unbroken token differing in its last digits, which is
     *       what a pasted id or build number looks like - the cut falls between characters instead.
     *       See {@link #elisionCut} for how far the cut goes, which is the whole of the judgement
     *       here.</li>
     *   <li>Threads identical to their last character have nothing to expose, and take a leading
     *       occurrence marker. It claims nothing about the conversation, which is the honest thing to
     *       say about two threads that really did open with the same words.</li>
     * </ol>
     *
     * <p>The marker goes in front rather than behind. The column ellipsises with CSS at whatever
     * width the glyphs happen to need, on top of the character count used here, and a trailing marker
     * is the first thing that clipping removes. A marker that can be truncated away is a decoration
     * that usually works.
     *
     * <h2>What this gives up</h2>
     *
     * <p>Two threads whose difference is their closing word, past the drawn part of the column, come
     * back with their opening elided: the reader loses the front of a name they could not read
     * anyway, and keeps at least {@link #KEEP} columns of the rest. What is not given up any more is
     * the name of a thread the column was drawing in full - a set that fits is left exactly as it is,
     * because a difference that is on screen needs no help to be seen.
     *
     * <h2>Bounds and ordering</h2>
     *
     * <p>Numbering follows the order the column draws, so a given row's marker does not change
     * between two renders of the same list. Every returned label fits {@code limit} in characters and
     * in display columns. The marker search is bounded by the number of rows and needs at most one
     * attempt more than there are labels already placed, because two different markers differ inside
     * the drawn part - which holds up to a six-digit marker even at the floor of eight columns, and so
     * up to more rows than a column could paint.
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
        // Both budgets are capped by the column: on a column narrower than either of them, "what is
        // certainly drawn" and "what is scanned" are the column itself.
        int drawn = Math.min(DRAWN, width);
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

        // Clustering on the drawn part of the truncated label. Narrower than this - the leading third
        // of the row - gathered rows the column was showing in full and elided the name off them.
        Map<String, List<String>> clusters = new LinkedHashMap<>();
        sources.forEach((id, title) -> clusters
                .computeIfAbsent(drawnKey(shorten(title, width), drawn), key -> new ArrayList<>())
                .add(id));

        Map<String, String> separated = new LinkedHashMap<>();
        clusters.values().forEach(ids -> {
            List<String> titles = ids.stream().map(sources::get).toList();
            List<String> labels = ids.size() == 1
                    ? List.of(shorten(titles.getFirst(), width))
                    : separate(titles, width, drawn, glance);
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
        return markRemainingCollisions(ordered, width, drawn);
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
     * Shows a colliding cluster what it does not share, by the least destructive cut that works.
     *
     * <p>The elided labels are only accepted if they separate everything that <em>can</em> be
     * separated - that is, if they leave as many distinct rows as there are distinct titles. The
     * comparison is against the distinct titles rather than against the row count on purpose: a
     * cluster containing seventeen copies of one thread and one other can never be reduced to
     * eighteen distinct labels by cutting, and judging the cut on that would throw away a good
     * elision because of a duplicate the occurrence marker was always going to handle.
     *
     * @param titles the full topics of the rows the column draws alike, in row order
     * @param width  characters the column can show
     * @param drawn  columns the column is certain to draw
     * @param glance columns a reader takes in at once
     * @return one label per title, in the same order; possibly still colliding, for the caller's
     *         final sweep to resolve
     */
    private static List<String> separate(List<String> titles, int width, int drawn, int glance) {
        List<String> plain = titles.stream().map(title -> shorten(title, width)).toList();
        int cut = elisionCut(titles, drawn, glance);
        if (cut <= 0) {
            return plain;
        }
        // The ellipsis is load-bearing: it says the row is named after part of a longer topic,
        // rather than claiming the remainder is the whole of it.
        List<String> elided = titles.stream()
                .map(title -> shorten("…" + title.substring(cut), width))
                .toList();
        long separable = titles.stream().distinct().count();
        long separated = elided.stream().map(label -> drawnKey(label, drawn)).distinct().count();
        return separated < separable ? plain : elided;
    }

    /**
     * How much of the shared opening to take off, or zero to leave these titles alone.
     *
     * <p>This is where the judgement in this class lives, so the three bounds are named rather than
     * folded together. Let {@code d} be the character at which the titles part company.
     *
     * <ul>
     *   <li><b>The floor.</b> The cut must be deep enough that {@code d} lands inside the
     *       {@code drawn} part of the resulting label. Below this floor the elision buys nothing:
     *       the difference is still off the end of the row, which is the state the reader complained
     *       about. It is the only bound here that is not negotiable, and it is now the only thing in
     *       the floor - see below.</li>
     *   <li><b>The aim.</b> Deeper still, so that {@code d} lands inside the {@code glance} - the
     *       leading third of the row, where the eye moving down a column finds it.</li>
     *   <li><b>The ceiling.</b> Not so deep that the label stops naming anything. Two bounds, and
     *       the tighter one wins: fewer than {@link #KEEP} columns of the longest title may not
     *       survive, and the cut may not land on the word the titles part company in, so at least
     *       one whole word of subject stands in front of the difference.</li>
     * </ul>
     *
     * <h2>What the last two rounds of this defect were</h2>
     *
     * <p>The word bound is the second half of the fix and the half that was missing. The character
     * count alone is measured against the <em>longest</em> title in the cluster, which says nothing
     * about what the short ones have left: a cluster holding one long topic and sixteen short ones
     * was cut by the long one's budget, and the sixteen came back as the two digits they differ in.
     * The count cannot be moved to the shortest title without demanding labels that cannot exist on
     * a narrow column, so what is bounded instead is the thing the reader actually loses - the last
     * word of the name.
     *
     * <p>The floor used to carry a second term as well: cut deep enough that the longest label fits
     * {@code width} exactly. That term is gone. It was never about the reader - {@link #shorten}
     * fits every label to the column afterwards regardless, and the difference is already guaranteed
     * inside {@code drawn} by the term that remains - and on a cluster with one long topic in it, it
     * was the term that pushed the cut past the last space in the shared opening. Its only cost is
     * that a long row may now be ellipsised at both ends, which is what a long row looks like.
     *
     * <p>Both ceilings are floored by the floor rather than allowed to overrule it. On a column too
     * narrow to keep a word at all - eight columns, where the whole label is the difference and two
     * characters of context - there is no cut that satisfies both, and showing the difference is
     * worth more than a word that would not fit beside it.
     *
     * <p>Where the aim and the ceiling disagree the ceiling wins, and the difference sits further
     * right - still drawn, which is the part that is not negotiable. The cut is then moved to a word
     * boundary: forward first, so no text is lost that the bounds did not already require, and
     * backward second, because a cut that keeps a whole word is worth a few columns more of shared
     * opening. Only when neither exists does the cut fall between characters, which is what one long
     * unbroken token leaves to work with.
     *
     * @param titles the full topics of the rows the column draws alike; at least two, not identical
     *               in every case
     * @param drawn  columns the column is certain to draw
     * @param glance columns a reader takes in at once
     * @return an index into the shared opening of every title, or 0 when no cut helps - because the
     *         titles are identical, because one is a prefix of another and would be cut away
     *         entirely, or because no cut can both show the difference and leave a name around it
     */
    private static int elisionCut(List<String> titles, int drawn, int glance) {
        int divergence = sharedPrefix(titles);
        int shortest = titles.stream().mapToInt(String::length).min().orElse(0);
        int longest = titles.stream().mapToInt(String::length).max().orElse(0);
        if (divergence == 0 || divergence >= shortest) {
            return 0;
        }
        // Every title agrees with the first one up to the divergence, so the word boundaries of the
        // first are the word boundaries of all of them at any index the bounds permit.
        String shared = titles.getFirst();

        int floor = Math.max(0, divergence + 2 - drawn);
        if (floor > divergence) {
            return 0;
        }
        int subject = Math.min(longest - KEEP, wordBeforeTheDifference(shared, divergence));
        int ceiling = graphemeFloor(shared, Math.min(divergence, Math.max(floor, subject)));
        if (ceiling < floor) {
            return 0;
        }
        int aim = Math.min(ceiling, Math.max(floor, divergence + 2 - glance));

        int cut = firstWordStart(shared, aim, ceiling);
        if (cut < 0) {
            cut = lastWordStart(shared, floor, aim);
        }
        if (cut < 0) {
            cut = Math.min(graphemeCeiling(shared, aim), ceiling);
        }
        return cut <= 0 || cut > ceiling ? 0 : cut;
    }

    /**
     * The deepest cut that still leaves a whole word standing in front of what differs.
     *
     * <p>This is the bound that separates a name from a discriminator. A label elided to exactly the
     * point the titles part company is the difference and nothing else - "…21" beside "…25" - which
     * is unique, fits, and tells the reader nothing about which conversation the row opens. Cutting
     * one word earlier costs a few columns of an opening every row in the cluster shares, and buys
     * back the only part of the label that says what the thread is about.
     *
     * <p>It is a bound on the cut and not a promise about the result: how much of a name one word
     * carries depends on the name, and the caller floors this against the width the difference has
     * to be drawn inside. Where the shared opening has no word boundary in front of the difference -
     * one unbroken token, which is what a pasted id or a build number looks like - there is no word
     * to keep and the divergence itself is returned, leaving the caller's other bounds to decide.
     *
     * @param shared     any of the titles in the cluster, all of which agree up to {@code divergence}
     * @param divergence the index at which the titles stop agreeing, inside {@code shared}
     * @return an index at or before {@code divergence}, never negative
     */
    private static int wordBeforeTheDifference(String shared, int divergence) {
        int differing = wordStartAt(shared, divergence);
        int previous = lastWordStart(shared, 1, differing - 1);
        return previous < 0 ? divergence : previous;
    }

    /**
     * Where the word containing {@code index} begins.
     *
     * <p>Walks back rather than forward, because the question being asked is which word the reader
     * would see broken by a cut here - and a cut inside a word breaks the word that started before
     * it. An index sitting on whitespace answers with the word in front of that whitespace, which is
     * the conservative reading: it treats the space as belonging to the word it follows, and so
     * never reports a later start than the truth.
     *
     * @param text  the string being cut
     * @param index a position inside it, never negative and never past the end
     * @return the index the word begins at, or 0 when nothing before it begins a word
     */
    private static int wordStartAt(String text, int index) {
        for (int at = Math.min(index, text.length() - 1); at >= 1; at--) {
            if (beginsAWord(text, at)) {
                return at;
            }
        }
        return 0;
    }

    /**
     * The first index in {@code [from, to]} at which a word begins.
     *
     * @param text the shared opening being cut
     * @param from lowest index to consider, never negative
     * @param to   highest index to consider, which may be below {@code from}
     * @return the index, or -1 when no word begins in that range
     */
    private static int firstWordStart(String text, int from, int to) {
        for (int index = Math.max(1, from); index <= to && index < text.length(); index++) {
            if (beginsAWord(text, index)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The last index in {@code [from, to]} at which a word begins.
     *
     * @param text the shared opening being cut
     * @param from lowest index to consider, never negative
     * @param to   highest index to consider
     * @return the index, or -1 when no word begins in that range
     */
    private static int lastWordStart(String text, int from, int to) {
        for (int index = Math.min(to, text.length() - 1); index >= Math.max(1, from); index--) {
            if (beginsAWord(text, index)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Whether a word starts at {@code index}: the character before it is a space and it is not.
     *
     * <p>Cutting here is what keeps a label from opening mid-word, and cutting on the space itself
     * would leave the label starting "… word" with a gap the ellipsis already implies.
     *
     * @param text  the shared opening being cut
     * @param index a position inside it, at least one
     * @return true when a word begins exactly there
     */
    private static boolean beginsAWord(String text, int index) {
        return text.charAt(index - 1) == ' ' && text.charAt(index) != ' ';
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
     * The character boundary at or after {@code index}, for a cut that may not be made any earlier.
     *
     * <p>The counterpart of {@link #graphemeFloor}, and needed because an elision has a floor of its
     * own: cutting one {@code char} short of what the bounds require would put the difference back
     * outside the drawn part of the row, which is the whole point of cutting. Rounding up costs at
     * most one character of an already-shared opening.
     *
     * @param text  the string being cut
     * @param index a {@code char} offset into it, which may sit inside a surrogate pair
     * @return a safe offset, never before {@code index} and never past the end of the text
     */
    private static int graphemeCeiling(String text, int index) {
        if (index <= 0) {
            return 0;
        }
        if (index >= text.length()) {
            return text.length();
        }
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        if (characters.isBoundary(index)) {
            return index;
        }
        int next = characters.following(index);
        return next == BreakIterator.DONE ? text.length() : next;
    }

    /**
     * The part of a label the column is certain to draw.
     *
     * <p>Two labels with the same key are two rows the reader cannot separate, because whatever
     * distinguishes them lies past the point the browser starts clipping. Keys are compared exactly,
     * including case: two labels differing only in case are left alone rather than elided, because
     * collapsing them would spend a topic to solve a collision that is not one.
     *
     * @param label a label the column would draw
     * @param drawn columns the column is certain to draw
     * @return the label's leading columns, or the whole label when it is shorter than that
     */
    private static String drawnKey(String label, int drawn) {
        return label.substring(0, cutIndex(label, drawn));
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
     * @param drawn  columns the column is certain to draw
     * @return the same ids in the same order, with markers where they were needed
     */
    private static Map<String, String> markRemainingCollisions(
            Map<String, String> labels, int width, int drawn) {
        Set<String> taken = new HashSet<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        // Each attempt puts a different marker at the front, so each produces a different key; with
        // finitely many labels already placed, one of them is free well before this bound.
        int attempts = labels.size() + 2;
        labels.forEach((id, label) -> {
            String candidate = label;
            for (int occurrence = 2;
                 occurrence <= attempts && taken.contains(drawnKey(candidate, drawn));
                 occurrence++) {
                candidate = shorten("(" + occurrence + ") " + label, width);
            }
            taken.add(drawnKey(candidate, drawn));
            resolved.put(id, candidate);
        });
        return resolved;
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
