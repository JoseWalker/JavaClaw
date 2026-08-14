package ai.javaclaw.chat.ui;

import tools.jackson.databind.JsonNode;

/**
 * What the browser saw when a turn ended, and the one rule that reads it.
 *
 * <h2>Why this is a record on the server and not four lines of script</h2>
 *
 * <p>Only the browser knows where focus is. The obvious arrangement is therefore to decide there
 * too - one function in the shell that looks around and answers yes or no - and that arrangement
 * shipped once and was wrong. Nothing in this repository executes a line of that script: the rule
 * could be inverted, pinned to true, pinned to false, or pointed at the wrong element, and the whole
 * suite stayed green. The defect it was written to close is invisible on screen, which is how it
 * survived in the first place, so a rule nothing can run is a rule nobody will notice breaking.
 *
 * <p>So the split is drawn at the line between seeing and judging, and it is drawn as far towards
 * seeing as it will go. The script reads properties; it composes nothing. Every {@code &&}, every
 * {@code !} and every choice about what a combination of properties means is here, where each one
 * is a case a test can state. {@code MainLayout} still owns the focus memory these observations are
 * drawn from - it is still the only thing that reads {@code __jcFocusReturn} - and this is still
 * the only thing that reads what it reports.
 *
 * <p>A first version of this split kept {@code caretHeld} as a fact, computed in the script by a
 * five-clause {@code usable()} predicate. That was the wrong line: a test can check that a fact
 * still names {@code document.activeElement}, but not that it reads it the right way round, so an
 * inverted {@code !usable(...)} - which hands the caret to the composer while somebody is using the
 * page, on every finished turn - passed every check in the build. The four {@code caret*}
 * observations below replace it, and the composition they feed is {@link #caretHeld()}, where each
 * clause has a test that fails when it is dropped.
 *
 * <h2>What the rule says</h2>
 *
 * <p>The composer may take the caret only when the composer is the reason nobody has it. That is one
 * situation and not a family of them: the reader was in the composer, something the composer did
 * took their footing away, and no other reader anywhere on the page loses anything by the caret
 * moving. Every other arrangement - somebody holding the caret, somebody selecting text, somebody
 * who walked off on their own, somebody stranded in a region this composer never touched - is left
 * exactly as it was found, because moving the caret on an event the reader did not cause is WCAG 2.1
 * SC 3.2.1 and that is the defect this whole path exists to close.
 *
 * <h2>What the browser was observed reporting</h2>
 *
 * <p>The rule is executed by the build; the observations it reads are produced by a script the build
 * cannot run, so they were measured instead - against this application in Chromium 136, driving
 * real streaming turns and recording what {@code __jcFocusFacts} returned at the moment the server
 * asked. Written in the vocabulary of the rule rather than of the eight raw observations, because
 * that is the level the cases differ at:
 *
 * <pre>
 * reader standing on Stop, empty field   attached, LOOSE,  no sel, in region, stayed    -&gt; caret returns
 * reader standing on Stop, draft typed   attached, HELD,   no sel, in region, stayed    -&gt; left alone
 * reader clicked a paragraph of the answer  attached, HELD, no sel, OUTSIDE,  stayed    -&gt; left alone
 * reader dragging a selection            attached, HELD,   SEL,    in region, stayed    -&gt; left alone
 * reader let the caret go to nothing     attached, LOOSE,  no sel, in region, STEPPED   -&gt; left alone
 * </pre>
 *
 * <p>The first line is the case this call was kept for, and it is worth saying what it looked like:
 * the send control was still {@code document.activeElement} and carried {@code disabled}, because
 * Chromium does not blur a disabled custom element. A rule that only asked whether the caret was on
 * the document body would have reported that reader as busy and never given them the caret back,
 * which is why {@link #caretDisabled} exists. The last line is the case where four clauses say yes
 * and only {@code readerSteppedAway} says no, which is the whole of that observation's
 * justification.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>The observations are a snapshot taken when the script ran, and the reader can act inside the
 * round trip that carries them back. Half of that window is closed on the other side: acts the
 * server can see - a conversation opened, a question asked, Stop pressed - move the caret out of
 * this rule's gift, and {@code ChatView} refuses an answer describing the page as it was before
 * one. What remains is the half the server never learns about, a Tab or a click inside the same
 * window, and it is only reachable on the branch where the caret was already loose. That is the
 * price of the rule being testable, and it is a narrower price than the first version of this
 * docblock claimed.
 *
 * <h2>What no test here can catch</h2>
 *
 * <p>Stated plainly, because the table above is evidence of a moment and not a guarantee about the
 * future. The build executes this rule. It does not execute the script that feeds it: no JavaScript
 * engine is on the test classpath, so the property reads and the {@code focusout} listener that
 * sets {@code __jcFocusGivenUp} are covered only by that measurement and by a test that reads the
 * script as text. That text check fails any observation that stops naming the browser property it
 * is about, which is what an observation pinned to a constant does. What it still cannot catch is a
 * single property read inverted in place - {@code isConnected !== true} where the name still says
 * {@code isConnected}. That is now the whole of the exposure, and it is one operator per
 * observation rather than a predicate: the compositions that used to live there, and that a text
 * check could never have covered, are the eight-line rule below. Closing the remainder needs the
 * script executed, which needs either a browser in the build or a JavaScript engine on the test
 * classpath, and neither is a dependency this change is entitled to add on its own.
 *
 * <p>Every component is a plain observation with no null state, so there is no null or empty
 * contract on the record itself. There is one on {@link #reportedBy}, and it is the whole of the
 * boundary.
 *
 * <p>Nothing here touches a component, so none of it is bound to the Vaadin UI thread. It is called
 * from one, because the thing that acts on the answer must be.
 *
 * @param regionAttached     whether the composer the question was asked about is still in the
 *                           document. A turn can finish after the reader has navigated away, and a
 *                           detached field is not somewhere a caret can go
 * @param caretConnected     whether whatever holds the caret is still in the document. A control
 *                           can be removed from under a reader standing on it, and a browser that
 *                           leaves {@code document.activeElement} pointing at the removed node is
 *                           describing a reader who holds nothing
 * @param caretOnRoot        whether the caret is on the document body or the document element,
 *                           which is where a browser puts a reader it has nowhere else to put. Both
 *                           are permanently connected, so this is the observation that stops
 *                           {@link #caretConnected} from reading the body as somebody working
 * @param caretDisabled      whether whatever holds the caret reports itself disabled. This is where
 *                           a reader dropped by the send control ends up when the browser leaves
 *                           them standing on it rather than moving them off
 * @param caretAriaDisabled  whether it is marked disabled to assistive technology. A custom element
 *                           carrying a {@code disabled} attribute is not a natively disabled form
 *                           control, and Vaadin marks such a control both ways; asking only the
 *                           property would read one of the two shapes as a working control
 * @param selectionCollapsed whether the selection is empty. Focusing anything collapses a selection,
 *                           and a reader halfway through dragging over an answer to copy it has not
 *                           asked for that
 * @param lastChoiceInRegion whether the last place the reader chose for themselves is inside that
 *                           composer. This is what makes the rule about this region rather than
 *                           about the page: a caret stranded where somebody was working in the
 *                           transcript is not the composer's to claim
 * @param readerSteppedAway  whether the reader gave the caret up themselves, walking off a control
 *                           that still worked. It is the only thing that separates a reader dropped
 *                           by the send control being disabled from a reader who had already left
 *                           that control before it was disabled - every other observation about
 *                           those two is identical
 */
record CaretPlace(boolean regionAttached, boolean caretConnected, boolean caretOnRoot,
                  boolean caretDisabled, boolean caretAriaDisabled, boolean selectionCollapsed,
                  boolean lastChoiceInRegion, boolean readerSteppedAway) {

    /**
     * What an unanswered question means, which is not silence but "somebody may be busy".
     *
     * <p>Each value is the one that denies, and they are chosen per axis of the rule rather than
     * per component: what has to come out denying is {@link #caretHeld()}, not each of the four
     * observations it is built from, so those four carry the combination that reads as a reader
     * holding a live control. A missing observation must never read as the permissive one: the two
     * sides of this question are a Java record and a script in another file that agree by name
     * alone, so a rename, a browser too old for something the script reads, or a shell that was
     * never installed all arrive here as absence - and an absence that resolved to "nobody is using
     * the caret" would hand the composer every one of those cases.
     *
     * <p>Each axis denies on its own, so a report damaged in any way at all still denies: a partial
     * report can only relax the axes whose observations it actually supplies, and it takes the
     * whole set, permissively answered, to reach a yes.
     */
    private static final CaretPlace NOTHING_KNOWN =
            new CaretPlace(false, true, false, false, false, false, false, true);

    /**
     * Reads what the browser sent, treating anything it did not send as a reason to do nothing.
     *
     * <p>This is the trust boundary. What arrives is a JSON value produced by a page this process
     * does not control, so nothing about its shape is assumed: not that it is an object, not that a
     * given name is present, not that a present name carries a boolean. Each observation that
     * cannot be read as a boolean falls back to the value in {@link #NOTHING_KNOWN} that denies, so
     * a report that is damaged in any way at all produces a caret that does not move.
     *
     * <p>A view routed without the shell reaches this every turn: with nothing installed the script
     * returns null, Vaadin decodes that to a null node or to null itself depending on how it
     * travelled, and both are handled here rather than deeper.
     *
     * @param report what the browser returned; null, a JSON null, and any value that is not an
     *               object are all accepted and all mean the same thing
     * @return the observations as far as they could be read, never null
     */
    static CaretPlace reportedBy(JsonNode report) {
        if (report == null || !report.isObject()) {
            return NOTHING_KNOWN;
        }
        return new CaretPlace(
                fact(report, "regionAttached", NOTHING_KNOWN.regionAttached()),
                fact(report, "caretConnected", NOTHING_KNOWN.caretConnected()),
                fact(report, "caretOnRoot", NOTHING_KNOWN.caretOnRoot()),
                fact(report, "caretDisabled", NOTHING_KNOWN.caretDisabled()),
                fact(report, "caretAriaDisabled", NOTHING_KNOWN.caretAriaDisabled()),
                fact(report, "selectionCollapsed", NOTHING_KNOWN.selectionCollapsed()),
                fact(report, "lastChoiceInRegion", NOTHING_KNOWN.lastChoiceInRegion()),
                fact(report, "readerSteppedAway", NOTHING_KNOWN.readerSteppedAway()));
    }

    /**
     * One boolean out of the report, or the denying value when there is no boolean to be had.
     *
     * <p>{@code isBoolean} rather than {@code asBoolean} on purpose: Jackson will coerce a string or
     * a number into a boolean quite happily, and a coerced observation is a guess. An observation
     * that did not arrive as a boolean did not arrive.
     *
     * @param report      the object to read, already known to be one
     * @param name        the observation's name, which is also its record component's name
     * @param whenUnknown what to answer when the name is absent or carries something else
     * @return the reported value, or {@code whenUnknown}
     */
    private static boolean fact(JsonNode report, String name, boolean whenUnknown) {
        JsonNode value = report.get(name);
        return value != null && value.isBoolean() ? value.booleanValue() : whenUnknown;
    }

    /**
     * Whether somebody is still acting through whatever the caret is on.
     *
     * <p>The four clauses are four ways of holding nothing, and each one is a case that was measured
     * or is reachable in this application: a caret on no element at all or on one that has left the
     * document ({@link #caretConnected}), a caret the browser parked on the body because it had
     * nowhere else to put it ({@link #caretOnRoot}), and the two shapes of a control disabled under
     * the reader standing on it ({@link #caretDisabled}, {@link #caretAriaDisabled}). Drop any one
     * of them and a reader who has been dropped reads as a reader who is busy, and never gets the
     * caret back; invert the whole thing and the composer takes the caret off somebody using the
     * page on every finished turn.
     *
     * <p>This is a composition and not an observation, which is exactly why it is here: the script
     * reports what it can see and this decides what it means, so every clause is a case
     * {@code KeyboardAccessTest} states and no clause can be dropped in silence.
     *
     * @return true when the caret is on something that still works, which is the reading that
     *         leaves the reader alone
     */
    private boolean caretHeld() {
        return caretConnected && !caretOnRoot && !caretDisabled && !caretAriaDisabled;
    }

    /**
     * Whether text is under selection, which is somebody reading whatever else is true.
     *
     * @return true when the browser reported a selection that is not collapsed, and true as well
     *         when it reported nothing at all - an unanswered question about a selection is
     *         answered here the way every unanswered question is, by assuming somebody is busy
     */
    private boolean selectionOpen() {
        return !selectionCollapsed;
    }

    /**
     * Whether the composer may take the caret, which it may only if it is why nobody has it.
     *
     * <p>Read the clauses as one sentence: there is a composer to go to, nobody is holding the
     * caret, nobody is selecting anything, the reader did not let it go, and the last place they
     * chose was in that composer. Each of the five is a case; drop any one of them and a reader
     * somewhere loses something they were doing.
     *
     * @return true only for the single arrangement the composer itself creates by disabling its own
     *         send control at the end of a turn
     */
    boolean invitesTheCaretBack() {
        return regionAttached
                && !caretHeld()
                && !selectionOpen()
                && !readerSteppedAway
                && lastChoiceInRegion;
    }
}
