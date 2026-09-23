package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Holds the stylesheet to the claims its own comments make.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Three defects shipped in a row underneath a comment asserting the opposite of what the rule
 * did: a token said to "clear AA with room to spare" on a ground it had never been measured against,
 * a rule said to cross the sidebar divider that covered 19% and stopped, and a draft row said to
 * claim nothing that painted no difference at all. Each comment was written from an audit that
 * stopped one case short, and nothing existed to notice.
 *
 * <p>A comment that states a measurement is a test with no runner. This is the runner. The contrast
 * table in the stylesheet is parsed out of the comment itself and recomputed from the tokens beside
 * it, so changing a colour without revisiting the table fails here rather than in a critique - and
 * the structural claims are checked against the rules they describe.
 *
 * <p>It reads the stylesheet off the classpath, which is also the only automated confirmation that
 * the file is packaged where {@code @StyleSheet} expects to find it.
 */
class StylesheetClaimsTest {

    private static final String STYLESHEET = "/META-INF/resources/styles/javaclaw.css";

    /** WCAG 2.1 AA for text below 18pt. Everything in the audited table is 12-15px. */
    private static final double AA = 4.5;

    /** The stylesheet's own audit rows: token, light ratio, dark ratio, and whether it is allowed. */
    private static final Pattern AUDIT_ROW = Pattern.compile(
            "--jc-([\\w-]+)\\s+(\\d+\\.\\d+):1 light\\s+(\\d+\\.\\d+):1 dark\\s+(yes|no)");

    private static final Pattern TOKEN = Pattern.compile(
            "--jc-([\\w-]+):\\s*light-dark\\(\\s*(#[0-9a-fA-F]{6})\\s*,\\s*(#[0-9a-fA-F]{6})\\s*\\)");

    private final String css = readStylesheet();

    /**
     * The same sheet with its comments removed, for the two tests that scan for a declaration
     * anywhere in the file. This file's comments are long and explain the declarations around them,
     * so they contain the very strings those scans look for - "identified by its outline: ..." read
     * as a hand-rolled focus ring, which is a test failing on its own documentation.
     */
    private final String rules = css.replaceAll("(?s)/\\*.*?\\*/", "");

    // ------------------------------------------------------------------ contrast

    @Test
    @DisplayName("every ratio the accent-soft audit prints is the ratio the tokens actually produce")
    void theAuditTableMatchesTheTokens() {
        // The ground the selected conversation row and the user's own bubble both sit on. It is
        // mixed, not declared, which is exactly why it was missed the first time the palette was
        // audited: there is no line in the token table to read a value off.
        int[] softLight = mix(token("accent", true), token("surface-raised", true));
        int[] softDark = mix(token("accent", false), token("surface-raised", false));

        List<String> checked = new ArrayList<>();
        Matcher rows = AUDIT_ROW.matcher(css);
        while (rows.find()) {
            String name = rows.group(1);
            double claimedLight = Double.parseDouble(rows.group(2));
            double claimedDark = Double.parseDouble(rows.group(3));
            boolean claimedLegible = "yes".equals(rows.group(4));

            double actualLight = contrast(token(name, true), softLight);
            double actualDark = contrast(token(name, false), softDark);

            assertThat(actualLight)
                    .as("--jc-%s on --jc-accent-soft, light", name)
                    .isCloseTo(claimedLight, org.assertj.core.data.Offset.offset(0.05));
            assertThat(actualDark)
                    .as("--jc-%s on --jc-accent-soft, dark", name)
                    .isCloseTo(claimedDark, org.assertj.core.data.Offset.offset(0.05));
            // The verdict column is the part a rule is written against, so it is checked as well as
            // the arithmetic: a token cannot be marked usable while measuring below AA.
            assertThat(actualLight >= AA && actualDark >= AA)
                    .as("--jc-%s is marked %s in the audit", name, claimedLegible ? "usable" : "unusable")
                    .isEqualTo(claimedLegible);
            checked.add(name);
        }

        // A table that has quietly lost its rows would otherwise pass this test in silence.
        assertThat(checked).contains("text", "accent-hover", "accent", "text-muted", "text-faint");
    }

    /** The text-tier table: every ink the sheet paints, against every surface it lands on. */
    private static final Pattern SURFACE_ROW = Pattern.compile(
            "--jc-(text[\\w-]*) on (surface[\\w-]*)\\s+(\\d+\\.\\d+):1 light\\s+(\\d+\\.\\d+):1 dark"
                    + "\\s+(yes|no)");

    /**
     * The audit that the accent-soft one was a supplement to, and which was written as prose while
     * every other claim in this file had been turned into arithmetic.
     *
     * <p>It exists as a table now because one of its rows has almost no margin: the composer's
     * placeholder is --jc-text-faint on --jc-surface-raised, which measures 4.63:1 in dark against a
     * 4.5 threshold. That is a real pass and it is 0.13 from not being one, and the thing standing
     * between it and a regression was a sentence in a comment.
     */
    @Test
    @DisplayName("every text tier clears AA on every surface the sheet paints it on")
    void everyTextTierClearsAaOnEverySurface() {
        List<String> checked = new ArrayList<>();
        Matcher rows = SURFACE_ROW.matcher(css);
        while (rows.find()) {
            String ink = rows.group(1);
            String ground = rows.group(2);
            double actualLight = contrast(token(ink, true), token(ground, true));
            double actualDark = contrast(token(ink, false), token(ground, false));

            assertThat(actualLight).as("--jc-%s on --jc-%s, light", ink, ground)
                    .isCloseTo(Double.parseDouble(rows.group(3)), org.assertj.core.data.Offset.offset(0.05));
            assertThat(actualDark).as("--jc-%s on --jc-%s, dark", ink, ground)
                    .isCloseTo(Double.parseDouble(rows.group(4)), org.assertj.core.data.Offset.offset(0.05));
            assertThat(actualLight >= AA && actualDark >= AA)
                    .as("--jc-%s on --jc-%s is marked %s", ink, ground, rows.group(5))
                    .isEqualTo("yes".equals(rows.group(5)));
            checked.add(ink + "/" + ground);
        }

        // Nine rows: three inks against three grounds. A table that has quietly lost one of them
        // would otherwise pass in silence, which is how the first version of this audit shipped.
        assertThat(checked).hasSize(9)
                .contains("text-faint/surface-raised", "text-muted/surface-sunken");
    }

    /**
     * Every heading element this interface renders, written as the selector its rules carry.
     *
     * <p>Three sources, and the defect was that only one of them was ever painted: the shell's h1
     * wears {@code .jc-topbar__title}, the empty state's greeting is an h2, and every {@code #},
     * {@code ##} and {@code ###} in an answer becomes an h1/h2/h3 inside {@code .jc-prose}. A fix
     * applied to the transcript alone leaves the greeting on whatever Vaadin decides, which is how
     * this list came to have one entry that was right and four that were not.
     */
    private static final List<String> HEADINGS = List.of(
            ".jc-topbar__title", ".jc-empty__title", ".jc-prose h1", ".jc-prose h2", ".jc-prose h3");

    /**
     * The bridge declaration a heading falls through to when no rule of ours paints it. The capture
     * is the token's name without its {@code --jc-} prefix, which is the form {@link #token} takes.
     */
    private static final Pattern LUMO_HEADER_MAPPING = Pattern.compile(
            "--lumo-header-text-color:\\s*var\\(--jc-([\\w-]+)\\)");

    /**
     * Vaadin's own value for --lumo-header-text-color, light then dark, as read off the running app.
     *
     * <p>Held here so an unmapped heading is measured rather than merely reported missing: the claim
     * this file makes about headings is about what a reader sees, and what a reader saw was these two
     * colours - #192434 and #f5f9ff - sitting on opposite sides of --jc-text.
     */
    private static final int[][] LUMO_HEADER_DEFAULT = {
            {0x19, 0x24, 0x34}, {0xf5, 0xf9, 0xff}};

    /**
     * That a heading is painted from this palette, and never reads fainter than the prose it heads.
     *
     * <p>The defect this pins: Lumo's global typography paints every h1-h6 in the document with
     * --lumo-header-text-color, and section 3 mapped body, secondary and tertiary text but not that
     * one. A heading therefore came from Vaadin's default, light-dark(#192434, #f5f9ff), while the
     * paragraph under it came from --jc-text - and the two defaults sit on opposite sides of ours.
     * On --jc-surface the heading measured 15.00:1 against the body's 16.61:1 in light, so the
     * heading was the <em>fainter</em> of the two and 9.4 Lab b*-units bluer than a palette built on
     * warm neutrals; in dark it measured 17.19:1 against 15.02:1 and became the brightest ink on the
     * page. Three blind critics read the light case off pixels alone as "two near-blacks that are
     * not separable by eye".
     *
     * <p>Stated as a relation rather than as a ratio, because that is the part a reader can see: a
     * heading may be the same ink as its prose and be told apart by size and weight, which is what
     * this sheet does, and it may be brighter. What it may not be is fainter than the text it
     * introduces - and it may certainly not be fainter in one theme and brighter in the other, which
     * is a typographic hierarchy whose sign depends on the time of day.
     *
     * <p>Measured on --jc-surface, which is the ground both symptoms appear on: an assistant turn
     * paints no fill of its own, and neither does the empty state.
     *
     * <p>Fails on the original on all four unmapped headings, in both themes for the light-side
     * claim and in light for the relation.
     */
    @Test
    @DisplayName("every heading is painted from this palette and never reads fainter than its prose")
    void everyHeadingKeepsItsPlaceAboveTheProse() {
        List<String> unpainted = new ArrayList<>();
        List<String> illegible = new ArrayList<>();
        List<String> inverted = new ArrayList<>();
        for (String heading : HEADINGS) {
            String ink = headingInkToken(heading);
            if (ink == null) {
                unpainted.add(heading + " falls through to Vaadin's own header colour");
            }
            for (boolean light : new boolean[]{true, false}) {
                int[] painted = ink == null
                        ? LUMO_HEADER_DEFAULT[light ? 0 : 1]
                        : token(ink, light);
                double headingRatio = contrast(painted, token("surface", light));
                double proseRatio = contrast(token("text", light), token("surface", light));
                String theme = light ? "light" : "dark";
                if (headingRatio < AA) {
                    illegible.add("%s reads at %.2f:1 on --jc-surface in %s"
                            .formatted(heading, headingRatio, theme));
                }
                if (headingRatio < proseRatio) {
                    inverted.add("%s reads at %.2f:1 in %s over prose at %.2f:1"
                            .formatted(heading, headingRatio, theme, proseRatio));
                }
            }
        }
        assertThat(inverted).as("headings fainter than the prose they introduce").isEmpty();
        assertThat(illegible).as("headings under AA on the ground they are read on").isEmpty();
        assertThat(unpainted).as("headings this sheet does not paint").isEmpty();
    }

    /**
     * The palette token a heading's ink actually comes from, following the path the browser follows.
     *
     * <p>A rule of ours wins if there is one; otherwise the element inherits nothing usable and takes
     * Lumo's --lumo-header-text-color, so the answer is whatever section 3 maps that to. Both steps
     * are needed: checking only the first would report the transcript headings as unpainted after a
     * correct fix, and checking only the second would miss a heading that paints itself.
     *
     * @param selector a heading's selector, as written in the sheet
     * @return the token name without its {@code --jc-} prefix, or null when neither this sheet's
     *         rules nor its Vaadin bridge paints the heading - which is the defect, not a missing
     *         input
     */
    private String headingInkToken(String selector) {
        String own = null;
        Matcher blocks = Pattern.compile("([^{}]*)\\{([^{}]*)}").matcher(rules);
        while (blocks.find()) {
            if (!blocks.group(1).contains(selector)) {
                continue;
            }
            // Guarded against background-color, border-color and the rest: a bare `color` only.
            Matcher ink = Pattern.compile("(?<![-\\w])color:\\s*var\\(--jc-([\\w-]+)\\)")
                    .matcher(blocks.group(2));
            if (ink.find()) {
                // Later wins, the way the cascade decides between rules of equal specificity.
                own = ink.group(1);
            }
        }
        if (own != null) {
            return own;
        }
        Matcher bridge = LUMO_HEADER_MAPPING.matcher(css);
        return bridge.find() ? bridge.group(1) : null;
    }

    /** WCAG 1.4.11: a boundary a reader needs in order to identify a control. */
    private static final double NON_TEXT = 3.0;

    private static final Pattern BOUNDARY_ROW = Pattern.compile(
            "(light|dark)\\s+(\\d\\.\\d+):1 on --jc-surface\\s+(\\d\\.\\d+):1 on --jc-surface-raised"
                    + "\\s+(\\d\\.\\d+):1 on --jc-surface-sunken");

    @Test
    @DisplayName("the control boundary clears 3:1 on every ground a bounded control sits on")
    void theControlBoundaryClearsNonTextContrast() {
        // The defect this pins: the suggestion chips were bounded by --jc-border-strong, which is a
        // divider between two surfaces and measures 1.35:1 against the chip's own fill in light and
        // 1.49:1 in dark. A boundary nobody can see is not a boundary; it is a claim.
        List<String> seen = new ArrayList<>();
        Matcher rows = BOUNDARY_ROW.matcher(css);
        while (rows.find()) {
            boolean light = "light".equals(rows.group(1));
            String[] grounds = {"surface", "surface-raised", "surface-sunken"};
            for (int i = 0; i < grounds.length; i++) {
                double actual = contrast(token("border-control", light), token(grounds[i], light));
                assertThat(actual).as("--jc-border-control on --jc-%s, %s", grounds[i], rows.group(1))
                        .isCloseTo(Double.parseDouble(rows.group(i + 2)),
                                org.assertj.core.data.Offset.offset(0.05));
                assertThat(actual).as("--jc-border-control on --jc-%s, %s, against 1.4.11",
                        grounds[i], rows.group(1)).isGreaterThanOrEqualTo(NON_TEXT);
            }
            seen.add(rows.group(1));
        }
        assertThat(seen).containsExactlyInAnyOrder("light", "dark");
    }

    @Test
    @DisplayName("the suggestion chip is bounded by the control token, not by a surface divider")
    void theSuggestionChipUsesTheControlBoundary() {
        String chip = ruleFor(".jc-starter {");
        assertThat(chip).contains("border: 1px solid var(--jc-border-control)");
        assertThat(chip).doesNotContain("--jc-border-strong");
    }

    /**
     * A control whose own edge is the thing that identifies it, and every ground that edge lands on.
     *
     * <p>Both sides are named because a 1px line is read against what is inside it and against what
     * is outside it, and one token can clear 3:1 on one side while failing on the other:
     * --jc-border-strong measured 1.56:1 against the composer's own fill and 1.36:1 against the band
     * the composer sits in. Naming only the fill would have passed a boundary the rail swallows.
     *
     * @param selector the rule the boundary is declared in, written as it appears in the sheet
     * @param grounds  the surface tokens, without their --jc- prefix, the edge is read against
     */
    private record BoundedControl(String selector, List<String> grounds) {
    }

    /**
     * Every rest state in the sheet where an edge is what says "this is a control".
     *
     * <p>The roster is spelled out rather than scraped, and what is absent from it is the half of
     * SC 1.4.11 that is easy to get wrong in the other direction. A conversation row, a folder
     * heading, a menu item, a per-turn action, the code panel's copy control and the shell's icon
     * buttons are all missing on purpose: none of them is bounded at rest, each is identified by its
     * own ink, and every one of those inks already clears AA on the ground it lands on. Promoting
     * their hover plates to a 3:1 edge would draw a grid of boxes over an interface whose structure
     * is carried by tone - and 1.4.11 does not ask for it, because nothing there is needed to
     * identify the control.
     *
     * <p>Equally absent: the hairlines between two surfaces, the "Draft" badge whose meaning is the
     * word inside it, the user's own bubble, and the send button while it is disabled - an inactive
     * component, which the success criterion exempts by name.
     */
    private static final List<BoundedControl> BOUNDED_CONTROLS = List.of(
            new BoundedControl(".jc-new-chat {", List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-search {", List.of("surface", "surface-sunken")),
            new BoundedControl(".jc-composer__field {", List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-message__editor {", List.of("surface-raised", "surface")),
            new BoundedControl(".jc-starter {", List.of("surface")),
            new BoundedControl(".jc-jump__button {", List.of("surface-raised", "surface")),
            new BoundedControl(".jc-skip-link {", List.of("surface-raised", "surface")),
            new BoundedControl(".jc-dialog__close {", List.of("surface-raised")),
            new BoundedControl(".jc-edit-button {", List.of("surface-raised", "surface")),
            new BoundedControl("vaadin-button.jc-send--stop {", List.of("surface-raised")));

    /**
     * The same claim for the states those controls move through, with the ground each state paints.
     *
     * <p>A boundary is not something a control has once. Every rule here changes either the edge or
     * the fill under it, so each one is a fresh pair to measure - and the pair that this round
     * actually failed on was a press: --jc-border-control against the --jc-border fill a pressed
     * control steps down to is 2.75:1 in light and 2.63:1 in dark, so the two controls that press
     * that way have to darken their edge as well, the way the three beside them already did.
     */
    private static final List<BoundedControl> BOUNDED_CONTROL_STATES = List.of(
            new BoundedControl(".jc-new-chat:hover", List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-new-chat:active", List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-search:hover", List.of("surface", "surface-sunken")),
            new BoundedControl(".jc-search[focused]", List.of("surface", "surface-sunken")),
            new BoundedControl(".jc-composer__field:hover", List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-composer__field:focus-within",
                    List.of("surface-raised", "surface-sunken")),
            new BoundedControl(".jc-jump__button:hover", List.of("surface-sunken", "surface")),
            new BoundedControl(".jc-jump__button:active", List.of("border", "surface")),
            new BoundedControl(".jc-skip-link:hover", List.of("surface-sunken", "surface")),
            new BoundedControl(".jc-skip-link:active", List.of("border", "surface")),
            new BoundedControl(".jc-dialog__close:hover", List.of("surface-sunken")),
            new BoundedControl(".jc-dialog__close:active", List.of("border")),
            new BoundedControl(".jc-edit-button:hover", List.of("surface-sunken", "surface")),
            new BoundedControl(".jc-edit-button:active", List.of("border", "surface")),
            new BoundedControl("vaadin-button.jc-send--stop:hover",
                    List.of("surface-sunken", "surface-raised")),
            new BoundedControl("vaadin-button.jc-send--stop:active",
                    List.of("border", "surface-raised")));

    @Test
    @DisplayName("every control identified by its own edge draws that edge at 3:1 or better")
    void everyBoundedControlClearsNonTextContrast() {
        // The defect this pins, from a live audit of the running app: --jc-border-control existed and
        // passed, and one rule in the sheet used it. Every other bounded control was still drawn with
        // a surface hairline, so the search field had a 1.11:1 edge on a 1.10:1 fill - a control with
        // no boundary at all, in both themes, on the column a reader navigates by.
        assertThat(boundaryFailures(BOUNDED_CONTROLS))
                .as("resting control boundaries under the 3:1 SC 1.4.11 asks of them")
                .isEmpty();
    }

    @Test
    @DisplayName("no hover or press state gives back the boundary the resting control has")
    void noStateWeakensAControlBoundary() {
        assertThat(boundaryFailures(BOUNDED_CONTROL_STATES))
                .as("control boundaries that fall under 3:1 in a state the reader can reach")
                .isEmpty();
    }

    @Test
    @DisplayName("the stop control is told apart from the field it is sitting inside")
    void theStopControlHasAVisibleContainer() {
        // The defect this pins: .jc-send--stop paints --jc-surface-raised, and so does the composer
        // field it sits in - 1.00:1, fill against identical fill - so the entire container of the one
        // control that interrupts a running answer was a 1.53:1 ring in light and 1.51:1 in dark.
        String stop = ruleFor("vaadin-button.jc-send--stop {");
        assertThat(stop).as("the fill is still the field's own, so the ring is the whole container")
                .contains("var(--jc-surface-raised)");
        assertThat(ruleFor(".jc-composer__field {")).contains("background: var(--jc-surface-raised)");

        String boundary = boundaryToken(stop);
        for (boolean light : new boolean[]{true, false}) {
            assertThat(contrast(token(boundary, light), token("surface-raised", light)))
                    .as("--jc-%s against the composer fill it is drawn on, %s",
                            boundary, light ? "light" : "dark")
                    .isGreaterThanOrEqualTo(NON_TEXT);
        }
    }

    @Test
    @DisplayName("the hairlines between two surfaces keep the soft tokens 1.4.11 does not reach")
    void decorativeSeparatorsAreNotPromoted() {
        // The other half of the criterion, and the one a contrast fix is most likely to trample: a
        // divider between two surfaces carries no information and is exempt, so darkening it buys no
        // compliance and costs the whole design - an interface whose structure is carried by tone
        // would acquire a drawn grid. Each of these is measured at 1.11:1 to 1.83:1 and stays there.
        List<String> separators = List.of(
                ".jc-topbar {", ".jc-composer {", ".jc-sidebar {", ".jc-sidebar__head {",
                ".jc-seam::after", ".jc-prose hr", ".jc-code {", ".jc-code__head");
        for (String separator : separators) {
            assertThat(ruleFor(separator))
                    .as("%s is a division between surfaces, not a control boundary", separator)
                    .doesNotContain("--jc-border-control");
        }
    }

    /** Where the caret's blink bottoms out, as a share of the accent left standing over the ground. */
    private static final Pattern CARET_FLOOR = Pattern.compile("--jc-caret-floor:\\s*([\\d.]+)");

    /**
     * The one state indicator this sheet draws inside the transcript, measured at its dimmest.
     *
     * <p>The defect this pins, from two independent measurements of the running app: the caret blinked
     * by dropping to opacity 0.4, which composites to #D7B398 on the light transcript and #69472F on
     * the dark one - 1.87:1 and 2.20:1 against the ground either side of it. SC 1.4.11 asks 3:1 of a
     * graphical object needed to understand state, and a blink spends half of every second at its
     * floor, so "it is bright the rest of the time" is an answer about the half the reader is not
     * looking at.
     *
     * <p>Measured rather than asserted as a number, because the thing that decides the ratio is a
     * compositing step between two tokens: change the accent and the floor that used to clear it may
     * not. That is the same reason every other table in this file is recomputed.
     */
    @Test
    @DisplayName("the streaming caret clears 3:1 on the transcript at the dimmest point of its blink")
    void theStreamingCaretStaysVisibleThroughItsBlink() {
        String caret = ruleFor(".jc-message--streaming .jc-message__bubble::after");
        assertThat(caret)
                .as("the caret is painted in the accent, which is what the floor is a share of")
                .contains("background: var(--jc-accent)");

        Matcher declaration = CARET_FLOOR.matcher(css);
        assertThat(declaration.find())
                .as("the floor of the blink is a token, so it can be measured rather than eyeballed")
                .isTrue();
        double floor = Double.parseDouble(declaration.group(1));
        assertThat(ruleFor("@keyframes jc-caret"))
                .as("the animation bottoms out on that token rather than on a number of its own")
                .contains("opacity: var(--jc-caret-floor)");

        for (boolean light : new boolean[]{true, false}) {
            // Opacity over an opaque ground is the same arithmetic as a colour mix at that share.
            int[] dimmest = mix(token("accent", light), token("surface", light), floor);
            assertThat(contrast(dimmest, token("surface", light)))
                    .as("the caret at opacity %s over --jc-surface, %s", floor, light ? "light" : "dark")
                    .isGreaterThanOrEqualTo(NON_TEXT);
        }
    }

    /**
     * Measures one roster and reports what fails, rather than stopping at the first bad pair.
     *
     * @param roster the rules to measure, each with the grounds its boundary is read against
     * @return one line per pair below 3:1, in both themes; empty when every pair clears it
     */
    private List<String> boundaryFailures(List<BoundedControl> roster) {
        List<String> failures = new ArrayList<>();
        for (BoundedControl control : roster) {
            String boundary = boundaryToken(ruleFor(control.selector()));
            for (String ground : control.grounds()) {
                for (boolean light : new boolean[]{true, false}) {
                    double actual = contrast(token(boundary, light), token(ground, light));
                    if (actual < NON_TEXT) {
                        failures.add("%s draws --jc-%s on --jc-%s at %.2f:1 in %s".formatted(
                                control.selector(), boundary, ground, actual, light ? "light" : "dark"));
                    }
                }
            }
        }
        return failures;
    }

    /**
     * Any declaration that paints a border, including the two Vaadin custom properties that are one.
     *
     * <p>Line-anchored so a {@code transition: border-color ...} value cannot read as a declaration
     * of the property it names.
     */
    private static final Pattern BORDER_DECLARATION = Pattern.compile(
            "(?m)^\\s*([\\w-]*border[\\w-]*)\\s*:\\s*([^;]+);");

    private static final Pattern TOKEN_REFERENCE = Pattern.compile("var\\(--jc-([\\w-]+)\\)");

    /**
     * The colour a rule draws its boundary in, as a token name with the {@code --jc-} prefix removed.
     *
     * <p>Radius and width are skipped: they are border properties that carry no colour, and the
     * search field declares all three. A rule with no colour token at all fails rather than passing
     * quietly - a control that has lost its boundary is exactly the defect this file is measuring.
     *
     * @param rule a declaration block, as returned by {@link #ruleFor(String)}
     * @return the token name, never null and never empty
     */
    private String boundaryToken(String rule) {
        Matcher declarations = BORDER_DECLARATION.matcher(rule);
        while (declarations.find()) {
            String property = declarations.group(1);
            if (property.contains("radius") || property.contains("width")) {
                continue;
            }
            Matcher reference = TOKEN_REFERENCE.matcher(declarations.group(2));
            if (reference.find()) {
                return reference.group(1);
            }
        }
        return fail("No border colour token in: " + rule.replaceAll("\\s+", " ").trim());
    }

    /** The stylesheet's second audit table, for the two grounds the selected row deepens through. */
    private static final Pattern PRESS_ROW = Pattern.compile(
            "--jc-([\\w-]+) on soft-active\\s+(\\d+\\.\\d+):1 light\\s+(\\d+\\.\\d+):1 dark\\s+"
                    // Non-greedy and comma-tolerant: a row's prose carries its own commas.
                    + "[^\\n]*?needs (\\d+(?:\\.\\d+)?)");

    @Test
    @DisplayName("the ratios claimed for the pressed selected row are the ones the tokens produce")
    void thePressAuditTableMatchesTheTokens() {
        // The row a reader is pointing at gets a deeper tint than the one it rests at, so the ground
        // its own label sits on moves too. Adding those grounds without measuring them is exactly
        // how --jc-accent-soft acquired four AA failures the first time round.
        int[] deepLight = mix(token("accent", true), token("surface-raised", true), 0.38);
        int[] deepDark = mix(token("accent", false), token("surface-raised", false), 0.38);

        List<String> checked = new ArrayList<>();
        Matcher rows = PRESS_ROW.matcher(css);
        while (rows.find()) {
            String name = rows.group(1);
            double threshold = Double.parseDouble(rows.group(4));
            double actualLight = contrast(token(name, true), deepLight);
            double actualDark = contrast(token(name, false), deepDark);

            assertThat(actualLight).as("--jc-%s on the pressed row, light", name)
                    .isCloseTo(Double.parseDouble(rows.group(2)), org.assertj.core.data.Offset.offset(0.05));
            assertThat(actualDark).as("--jc-%s on the pressed row, dark", name)
                    .isCloseTo(Double.parseDouble(rows.group(3)), org.assertj.core.data.Offset.offset(0.05));
            // The threshold differs by row: the label is text and needs AA, the selection rail is a
            // 2px non-text mark and needs 3:1. Both are read out of the row rather than assumed.
            assertThat(Math.min(actualLight, actualDark))
                    .as("--jc-%s clears the %s:1 it claims", name, threshold)
                    .isGreaterThanOrEqualTo(threshold);
            checked.add(name);
        }

        assertThat(checked).as("the press audit still has its rows").contains("text", "accent");
    }

    @Test
    @DisplayName("the selected row's time is painted in a tone that is legible on the selected row")
    void theSelectedRowsTimeClearsAa() {
        String rule = ruleFor(".jc-conv[aria-current=\"true\"] .jc-conv__time");

        assertThat(rule).contains("--jc-accent-hover");
        // The bug this pins: --jc-accent is the obvious choice and measures 4.21:1 on this ground.
        assertThat(rule).doesNotContain("color: var(--jc-accent);");
    }

    // ------------------------------------------------------------------ structure

    @Test
    @DisplayName("the draft rule cannot outrank the rule that marks the reader's place")
    void theDraftRuleIsScopedAwayFromTheSelectedRow() {
        // Both selectors can match one element - a draft the reader is currently in - and the draft
        // rule is the more specific of the two, so without the :not() it decided the colour of the
        // one row in the column that is meant to be the easiest to read.
        assertThat(css).contains(".jc-conv[data-source=\"draft\"]:not([aria-current=\"true\"]) .jc-conv__title");
        assertThat(css).doesNotContain(".jc-conv[data-source=\"draft\"] .jc-conv__title");
    }

    @Test
    @DisplayName("a draft is told apart by something that is painted, not by an absent rule")
    void aDraftHasAMarkOfItsOwn() {
        assertThat(ruleFor(".jc-conv__badge")).contains("border");
    }

    @Test
    @DisplayName("the header fade starts past the header's own border, so the bar keeps its edge")
    void theHeaderRuleSurvivesTheFade() {
        // A percentage top resolves against the padding box, so `top: 100%` starts the fade at the
        // inner edge of the border and paints the surface straight over it.
        assertThat(ruleFor(".jc-topbar::after")).contains("top: calc(100% + 1px)");
    }

    @Test
    @DisplayName("the composer's own border survives the scrim above it, the way the header's does")
    void theComposerRuleSurvivesTheFade() {
        // The same defect as the header's, in the opposite direction and unnoticed for three rounds:
        // `bottom: 100%` resolves against the padding box, so the scrim's fully opaque last row lands
        // exactly on the border row and paints over it. Measured before the fix, at four x positions
        // and every breakpoint, no --jc-border row existed anywhere along the composer's top edge -
        // 1.08:1 in light and 1.05:1 in dark where the rule beside it claims a hairline.
        assertThat(ruleFor(".jc-composer::before")).contains("bottom: calc(100% + 1px)");
    }

    @Test
    @DisplayName("the locked field draws no rectangle of its own inside the shell's")
    void theLockedFieldAddsNoSecondBorder() {
        // Lumo declares the read-only field's border with a shorthand, which the border-width custom
        // property further up cannot reach. For three rounds a 1px dashed rect therefore appeared for
        // the length of every turn, nested 12px inside our own accent focus ring, in a slate that
        // exists nowhere else in the palette - on the one control the reader is watching.
        //
        // Nothing locks the composer today: the read-only state that defect lived in has been
        // removed, because a read-only field looks exactly like an editable one and went on
        // inviting a reader to type into a control that swallowed the result. The repair is kept
        // under both attributes anyway, and asserted here, so that whatever locks this field next -
        // an upload in flight, a session expiring - does not bring the dash back with it.
        assertThat(ruleFor(".jc-composer vaadin-text-area[readonly]::part(input-field)"))
                .contains("border: 0");
        assertThat(css).contains(".jc-composer vaadin-text-area[disabled]::part(input-field)");
    }

    @Test
    @DisplayName("the composer has no rule for a streaming state it no longer has")
    void noRuleSurvivesForTheStreamingComposer() {
        // `--busy` existed to paint a field that was locked for the length of every answer: it
        // dropped the elevation, and a companion rule put the focus ring back underneath it. The
        // field is no longer locked - the reader may draft their next question while an answer
        // streams - so the modifier described a state the composer does not enter, and a class the
        // stylesheet paints but nothing can ever wear is a rule the next reader has to disprove.
        //
        // Removing it costs no contrast: what it declared was a shadow, so the field keeps the
        // resting border BOUNDED_CONTROLS already measures at 3.52:1 in light and 3.13:1 in dark,
        // and it keeps the raised fill and the ordinary :focus-within ring with it.
        assertThat(css)
                .as("a modifier for a composer state that no longer exists")
                .doesNotContain("jc-composer__field--busy");
    }

    @Test
    @DisplayName("the shell's two chrome edges are declared in the same colour, as the comment says")
    void bothChromeEdgesUseOneHairlineColour() {
        assertThat(ruleFor(".jc-topbar {")).contains("border-bottom: 1px solid var(--jc-border)");
        assertThat(ruleFor(".jc-composer {")).contains("border-top: 1px solid var(--jc-border)");
    }

    @Test
    @DisplayName("the collapse control outranks the icon-button margin reset, not just matches it")
    void theCollapseControlWinsTheCascade() {
        int collapse = css.indexOf(".jc-sidebar__head vaadin-button.jc-sidebar__collapse");
        int reset = css.indexOf("vaadin-button.jc-icon-button {");

        assertThat(collapse).as("the collapse rule is present").isNotNegative();
        // Source order cannot save it: the reset comes later and physical margin cascades with the
        // logical one on the used value, so the win has to come from specificity.
        assertThat(reset).isGreaterThan(collapse);
    }

    @Test
    @DisplayName("the copy control's confirmation outranks the glyph display rule, not just matches it")
    void theCopyConfirmationWinsTheCascade() {
        // The bug this pins: `.jc-msg-action__icon--done { display: none }` is one class deep and
        // `.jc-glyph` sets its own display 500 lines further down, so the single-class version lost
        // the cascade and the tick painted beside the copy glyph on every settled turn.
        assertThat(css).contains(".jc-msg-action .jc-msg-action__icon--done");
        assertThat(css).doesNotContain("\n.jc-msg-action__icon--done {");
        // Anchored to the start of a line, so the bare `.jc-glyph` rule is the one found rather
        // than the tail of a compound selector that happens to end in the same characters.
        assertThat(css.indexOf(".jc-msg-action .jc-msg-action__icon--done"))
                .as("the hiding rule is declared before .jc-glyph, so specificity is what saves it")
                .isLessThan(css.indexOf("\n.jc-glyph {"));
    }

    @Test
    @DisplayName("a turn's controls are painted at rest rather than revealed by pointing at them")
    void turnControlsArePaintedAtRest() {
        String rest = ruleFor(".jc-message__actions {");
        // These were held at opacity 0 until the turn was hovered. The row was already in flow and
        // already reserving its height, so hiding it bought no space - it only meant the answer to
        // "what can I do with this?" was itself behind a hover.
        assertThat(rest).doesNotContain("opacity: 0");
        assertThat(rest).doesNotContain("pointer-events: none");

        // What hover changes now is the ink, not the presence.
        assertThat(css).contains(".jc-message:hover .jc-msg-action");
        assertThat(css).contains(".jc-message:focus-within .jc-msg-action");
    }

    @Test
    @DisplayName("no rule anywhere leaves a turn control invisible and still clickable")
    void noControlIsInvisibleAndClickable() {
        // The invariant the old rule was protecting, kept as its own claim so removing the reveal
        // did not remove the reason it was written that way.
        Matcher blocks = Pattern.compile("\\{[^{}]*}").matcher(rules);
        // Not any "opacity: 0", which would also catch --vaadin-input-field-hover-highlight-opacity
        // and half a dozen other custom properties whose names end in the same characters.
        Pattern transparent = Pattern.compile("(?<![-\\w])opacity:\\s*0;");
        List<String> offenders = new ArrayList<>();
        while (blocks.find()) {
            String block = blocks.group();
            if (transparent.matcher(block).find() && !block.contains("pointer-events: none")) {
                offenders.add(block.replaceAll("\\s+", " ").trim());
            }
        }
        assertThat(offenders).as("blocks that hide a box without also making it unclickable").isEmpty();
    }

    // ------------------------------------------------------------------ the scroll masks

    @Test
    @DisplayName("both scroll masks are longer than a line of prose, and say so in one token")
    void theScrollMasksExceedOneLineBox() {
        // The defect this pins, and the only severity 2 the last round shipped with: the header's
        // mask was var(--jc-space-l), which is 24px, against a 24.75px line box. The gradient
        // therefore ended inside the glyphs of the topmost visible line rather than past it, and
        // that line held 2.96:1 against the page - a cut through the x-height, not a fade.
        assertThat(ruleFor(".jc-topbar::after")).contains("height: var(--jc-scroll-mask)");
        assertThat(ruleFor(".jc-composer::before")).contains("height: var(--jc-scroll-mask)");
        assertThat(ruleFor(".jc-topbar::after")).doesNotContain("height: var(--jc-space-l)");
        assertThat(ruleFor(".jc-composer::before")).doesNotContain("height: var(--jc-space-l)");

        // Expressed as a multiple of the line box rather than as a length, so it cannot fall out of
        // step with the type scale again. Two, because at any smaller multiple a single line can
        // still span the whole ramp and be graded across its own x-height.
        Matcher mask = Pattern.compile("--jc-scroll-mask:\\s*calc\\(var\\(--jc-line-box\\)\\s*\\*\\s*(\\d+)\\)")
                .matcher(css);
        assertThat(mask.find()).as("--jc-scroll-mask is declared as a multiple of --jc-line-box").isTrue();
        assertThat(Integer.parseInt(mask.group(1))).isGreaterThanOrEqualTo(2);

        // And the line box is the real one, not a spacing token that happens to be about the size.
        assertThat(css).contains(
                "--jc-line-box: calc(var(--jc-font-size-m) * var(--jc-line-height-body))");
    }

    /**
     * The third scroll edge, which had no mask and was the only one a reader could see cut a row.
     *
     * <p>The conversation column scrolls under a pinned user menu. Both are painted
     * --jc-surface-sunken and there is no rule between them, so the point where the scroller stops
     * painting is invisible: an audit at 834x1112 measured a row rendering seven of its thirteen ink
     * rows and simply stopping, with the surface either side of the cut identical to the byte. What
     * the reader sees there is not a list with more in it - it is broken text.
     *
     * <p>Two claims, and the fix is wrong without either. The ramp has to be longer than one line of
     * a row, or the gradient ends inside the glyphs it is meant to be fading and the cut comes back
     * graded instead of hard. And there has to be room under the last row at least as deep as the
     * ramp, because the ramp is fixed to the bottom of the scroller rather than to the content: with
     * no room, a reader who scrolls to the end finds the final conversation permanently greyed out
     * with nothing below it to explain why.
     *
     * <p>Fails on the original, which declared neither.
     */
    @Test
    @DisplayName("the conversation column fades at its foot instead of cutting a row through")
    void theConversationColumnMarksItsBottomEdge() {
        String scroll = ruleFor(".jc-sidebar__scroll");

        Matcher ramp = Pattern.compile(
                        "\\bmask-image:\\s*linear-gradient\\(to bottom,[^;]*?"
                                + "calc\\(100% - var\\((--jc-[\\w-]+)\\)\\)[^;]*?transparent[^;]*\\);")
                .matcher(scroll);
        assertThat(ramp.find())
                .as("the scrolling column declares a fade to transparent at its bottom edge, in %s", scroll)
                .isTrue();
        double rampPx = lengthOf(ramp.group(1));

        // A row's own line box, from the row's own rule: 13px of --jc-font-size-s at the 1.9 the
        // rail sets. Derived rather than typed in, so a change to either falsifies this rather than
        // quietly shortening the ramp below the glyphs.
        String row = ruleFor(".jc-conv {");
        Matcher leading = Pattern.compile("line-height:\\s*([\\d.]+)").matcher(row);
        assertThat(leading.find()).as("a conversation row declares its leading, in %s", row).isTrue();
        double rowLineBox = lengthOf("--jc-font-size-s") * Double.parseDouble(leading.group(1));

        assertThat(rampPx)
                .as("the fade is longer than one line of a row, so it cannot end inside the glyphs")
                .isGreaterThan(rowLineBox);

        Matcher room = Pattern.compile("padding-block-end:\\s*var\\((--jc-[\\w-]+)\\)").matcher(scroll);
        assertThat(room.find())
                .as("the scrolling column reserves room under its last row, in %s", scroll)
                .isTrue();
        assertThat(lengthOf(room.group(1)))
                .as("the room under the last row clears the fade, so the end of the list is not in it")
                .isGreaterThanOrEqualTo(rampPx);

        // The fade is only honest about content the reader is scrolling past. A row the browser
        // scrolled to - which is what Tab down the column does - is a row the reader was sent to, and
        // the browser parks it flush against the bottom edge unless the scroller says otherwise. Then
        // it would arrive inside the ramp with its focus ring faded out, which is the one place this
        // fix could make the column worse than the cut it replaces.
        Matcher landing = Pattern.compile("scroll-padding-block-end:\\s*var\\((--jc-[\\w-]+)\\)")
                .matcher(scroll);
        assertThat(landing.find())
                .as("the column keeps what it scrolls to out of its own fade, in %s", scroll)
                .isTrue();
        assertThat(lengthOf(landing.group(1)))
                .as("a row the browser scrolls to lands clear of the fade")
                .isGreaterThanOrEqualTo(rampPx);
    }

    // ------------------------------------------------------------------ the type tiers

    /**
     * One tier of the transcript's typography, as the reader meets it.
     *
     * @param name   what the tier is called in the sheet, for a failure message that names the pair
     * @param size   its rendered size in CSS pixels
     * @param weight its rendered weight
     */
    private record Tier(String name, double size, int weight) {

        /** What separates this tier from another one. Two tiers alike in both render as one. */
        private boolean rendersLike(Tier other) {
            return size == other.size && weight == other.weight;
        }
    }

    /**
     * That a heading and a bold phrase are not the same piece of typography.
     *
     * <p>They were. {@code .jc-prose h3} took --jc-font-size-m, which is the size of body prose, and
     * --jc-font-weight-strong from the shared heading rule; {@code .jc-prose strong} took the same
     * 650. Live, both measured 15.0px at 650 and 1.11:1 apart in colour, which is not a difference -
     * so in a rich answer a section heading and an emphasised phrase inside a paragraph were
     * indistinguishable, and the only cue that one was a heading was that it began a line.
     *
     * <p>Checked over every pair rather than over the reported one, because the property is that the
     * prose has tiers at all. A fix that separated h3 from strong by colliding h3 with h2 would be
     * the same defect one row up the scale.
     *
     * <p>Fails on the original on the h3/strong pair.
     */
    @Test
    @DisplayName("no two tiers of prose render at the same size and the same weight")
    void everyProseTierIsSetApartFromEveryOther() {
        // Everything under .jc-prose inherits the page's own size and weight, so body prose and the
        // tiers that only restate one of the two are resolved against it.
        String page = ruleFor("body {");
        double bodySize = lengthOf(tokenIn(page, "font-size"));
        int bodyWeight = weightOf(tokenIn(page, "font-weight"));
        // Weight is declared once for all three headings and the size separately for each.
        int headingWeight = weightOf(tokenIn(ruleFor(".jc-prose h1,"), "font-weight"));

        List<Tier> tiers = List.of(
                new Tier("body prose", bodySize, bodyWeight),
                new Tier("h1", lengthOf(tokenIn(lastRuleFor(".jc-prose h1 {"), "font-size")), headingWeight),
                new Tier("h2", lengthOf(tokenIn(lastRuleFor(".jc-prose h2 {"), "font-size")), headingWeight),
                new Tier("h3", lengthOf(tokenIn(lastRuleFor(".jc-prose h3 {"), "font-size")), headingWeight),
                new Tier("strong", bodySize,
                        weightOf(tokenIn(lastRuleFor(".jc-prose strong {"), "font-weight"))));

        List<String> collisions = new ArrayList<>();
        for (int first = 0; first < tiers.size(); first++) {
            for (int second = first + 1; second < tiers.size(); second++) {
                if (tiers.get(first).rendersLike(tiers.get(second))) {
                    collisions.add("%s and %s both render at %.1fpx / %d".formatted(
                            tiers.get(first).name(), tiers.get(second).name(),
                            tiers.get(first).size(), tiers.get(first).weight()));
                }
            }
        }
        assertThat(collisions).as("tiers a reader cannot tell apart").isEmpty();
    }

    /**
     * The token a declaration defers to, so a claim is checked against the value and not the name.
     *
     * @param rule     a declaration block, as returned by {@link #ruleFor(String)}
     * @param property the property to read, for instance {@code font-size}
     * @return the custom property's name, including its two leading dashes
     */
    private static String tokenIn(String rule, String property) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(property) + ":\\s*var\\((--jc-[\\w-]+)\\)")
                .matcher(rule);
        return matcher.find() ? matcher.group(1) : fail("No " + property + " token in " + rule);
    }

    /**
     * A token in CSS pixels, following the {@code calc()} products the type scale is built out of.
     *
     * <p>Products only, which is the whole of what this sheet writes: --jc-scroll-mask is a multiple
     * of --jc-line-box, which is --jc-font-size-m times a unitless leading. A general expression
     * evaluator would be a second CSS engine living in a test file, and the tests that need one do
     * not exist.
     *
     * <p>A unitless token - a leading, a multiplier - comes back as its bare number, so a product
     * mixing one length with one ratio lands in pixels. A token that is neither a rem length, a bare
     * number nor a product of those two is not something this can answer for and fails the test
     * rather than guessing at a value.
     *
     * @param token a custom property's name, including its two leading dashes
     * @return its value in CSS pixels at the 16px root the shell never overrides, or its bare number
     *         when the token carries no unit
     */
    private double lengthOf(String token) {
        String value = declared(token);
        if (!value.contains("calc(")) {
            return value.contains("rem") ? rem(value) : Double.parseDouble(value.trim());
        }
        double product = 1;
        Matcher factors = Pattern.compile("var\\((--jc-[\\w-]+)\\)|([\\d.]+)").matcher(value);
        while (factors.find()) {
            product *= factors.group(1) != null
                    ? lengthOf(factors.group(1))
                    : Double.parseDouble(factors.group(2));
        }
        return product;
    }

    /**
     * A weight token as a number.
     *
     * @param token a custom property's name, including its two leading dashes
     * @return the weight it declares
     */
    private int weightOf(String token) {
        return Integer.parseInt(declared(token));
    }

    // ------------------------------------------------------------------ the measure

    @Test
    @DisplayName("the measure holds a line of prose inside the comfortable band")
    void theMeasureHoldsALineInsideTheBand() {
        // Measured on the running build with Playwright, across a full paragraph of an answer:
        // 6.818px per character at --jc-font-size-m in the shell's own font stack. The column is
        // the measure less its two gutters.
        double glyph = 6.818;
        double columnPx = rem(declared("--jc-measure")) - 2 * rem(declared("--jc-space-l"));
        double characters = columnPx / glyph;

        assertThat(characters)
                .as("characters per line at --jc-measure = %s", declared("--jc-measure"))
                // 46rem measured 100.9 characters, which is what this replaced. The upper bound is
                // where a reader starts losing the return sweep; the lower one is where a line
                // stops holding a clause.
                .isBetween(60.0, 90.0);
    }

    // ------------------------------------------------------------------ the rail

    @Test
    @DisplayName("the rail's stacked controls are one height and one ink line, from two tokens")
    void theRailHasOneHeightAndOneIconLead() {
        // Measured before this existed: New chat 34px, the search field 32px and a conversation row
        // 33px, on three controls that share the same two vertical edges. And the New chat label
        // began at x=48 while the search placeholder began at x=42, because each control had picked
        // its own icon size and its own gap.
        assertThat(ruleFor(".jc-new-chat {")).contains("min-height: var(--jc-control-height)");
        assertThat(ruleFor(".jc-conv {")).contains("min-height: var(--jc-control-height)");
        assertThat(ruleFor(".jc-search {")).contains("--vaadin-input-field-height: var(--jc-control-height)");

        assertThat(ruleFor(".jc-new-chat .jc-glyph.jc-glyph")).contains("width: var(--jc-rail-icon)");
        assertThat(ruleFor(".jc-search > .jc-glyph")).contains("width: var(--jc-rail-icon)");
        assertThat(ruleFor(".jc-search::part(input-field)")).contains("gap: var(--jc-space-xs)");
    }

    @Test
    @DisplayName("the search field answers a keyboard with the ring every other control draws")
    void theSearchFieldDrawsTheSameFocusRing() {
        // [focused] covers pointer focus as well, so on its own it made the one text field in the
        // column the one stop whose Tab response was a 1px border-colour change.
        assertThat(ruleFor(".jc-search[focus-ring]::part(input-field)"))
                .contains("var(--jc-focus-ring-width)")
                .contains("var(--jc-focus-ring-color)");
    }

    // ------------------------------------------------------------------ derived geometry

    @Test
    @DisplayName("the per-turn row's optical correction is derived from the glyph, not typed in")
    void theOpticalInsetIsDerived() {
        // A literal was correct for the glyph size on the day it was written and 2px wrong the day
        // the glyph changed, which is exactly what happened: -0.4375rem seats a 16px box's ink and
        // leaves an 18px box's ink 2px inboard of the axis every other block is flush with.
        // The literal is gone from the sheet entirely: it was in two places, the per-turn row and
        // the shell's icon buttons, and both are derived now.
        assertThat(rules).doesNotContain("-0.4375rem");
        assertThat(ruleFor(".jc-message[data-role=\"assistant\"] .jc-message__actions"))
                .contains("calc(-1 * var(--jc-action-optical-inset))");
        assertThat(css).contains("--jc-action-optical-inset:");
        assertThat(css).contains("--jc-glyph-ink: 0.8125");
    }

    @Test
    @DisplayName("a table wide enough to overflow the column scrolls rather than collapsing")
    void aWideTableScrolls() {
        // Measured on the running build: a twelve-column table injected into a real answer gave
        // every column 45px and wrapped each heading onto four lines, with the container reporting
        // no overflow at all. Nothing was clipped, so nothing looked broken - it was just unreadable.
        assertThat(ruleFor(".jc-table {")).contains("overflow-x: auto");
        // The floor on a cell is what makes the overflow happen; without it the columns give up
        // their width to the container and there is nothing to scroll.
        assertThat(ruleFor(".jc-prose th,")).contains("min-width:");
        // And the clamp has to come off inside the wrapper, or the table has nowhere to go but in.
        assertThat(ruleFor(".jc-prose .jc-table > table")).contains("max-width: none");
    }

    /**
     * The rail claims one gutter and one ink line for the whole column. A rule that sets a leading
     * edge from a raw spacing token is how the column got six of them: every one of those was a
     * defensible local choice, and none of them knew about the other five.
     */
    @Test
    @DisplayName("every leading edge in the sidebar is set from the rail, not from a loose token")
    void theSidebarHasOneRail() {
        assertThat(css).contains("--jc-rail-gutter:").contains("--jc-rail-inset:");

        // Where a row's own box begins.
        assertThat(ruleFor(".jc-sidebar__scroll")).contains("padding-inline: var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-sidebar__tools")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-sidebar__foot")).contains("var(--jc-rail-gutter)");
        // Where the ink inside it begins.
        assertThat(ruleFor(".jc-conv {")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-group__head {")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-seam {")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-new-chat {")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-user__trigger")).contains("var(--jc-rail-gutter)");
        assertThat(ruleFor(".jc-search::part(input-field)")).contains("var(--jc-rail-gutter)");
        // The head has no chip of its own, so the brand mark sits on the ink line directly.
        assertThat(ruleFor(".jc-sidebar__head {")).contains("padding-inline: var(--jc-rail-inset)");
    }

    // ------------------------------------------------------------------ the state model

    /**
     * Every control the shell paints that is not a Vaadin component, in the form its rules are
     * written in. Section 9 of the stylesheet claims all of them declare hover, focus-visible and
     * active; three of them declared none of the three, and six declared no active state.
     *
     * <p>A roster is spelled out rather than scraped because the claim is about a closed set: a
     * control added later has to be added here too, and being made to do that is the point.
     */
    private static final List<String> CONTROLS = List.of(
            ".jc-skip-link", ".jc-new-chat", ".jc-group__head", ".jc-conv", ".jc-user__trigger",
            ".jc-menu__item", ".jc-starter", ".jc-msg-action", ".jc-edit-button",
            ".jc-dialog__close", ".jc-jump__button", ".jc-code__copy", ".jc-prose a");

    /** The same claim for the two Vaadin buttons, which expose keyboard focus as an attribute. */
    private static final List<String> VAADIN_BUTTONS =
            List.of("vaadin-button.jc-send", "vaadin-button.jc-icon-button");

    @Test
    @DisplayName("every control section 9 speaks for declares all four of its states")
    void everyControlDeclaresEveryState() {
        // Measured before this test existed, with CSS.forcePseudoState driving a real Chromium:
        // .jc-jump__button, .jc-code__copy and .jc-prose a had no focus-visible rule of their own,
        // so the keyboard ring they drew was Chrome's - rgb(0, 95, 204) at 1px in light and
        // rgb(153, 200, 255) in dark. A blue hairline is not this product's focus ring, and on the
        // jump control it lands on a floating pill where the UA ring is thinnest.
        List<String> missing = new ArrayList<>();
        for (String control : CONTROLS) {
            for (String state : List.of(":hover", ":focus-visible", ":active")) {
                if (!css.contains(control + state)) {
                    missing.add(control + state);
                }
            }
        }
        for (String button : VAADIN_BUTTONS) {
            for (String state : List.of(":hover", "[focus-ring]", ":active")) {
                if (!css.contains(button + state)) {
                    missing.add(button + state);
                }
            }
        }
        assertThat(missing)
                .as("controls with a state section 9 says they declare, and do not")
                .isEmpty();
    }

    @Test
    @DisplayName("the row the reader is on still answers the pointer")
    void theSelectedRowIsNotInert() {
        // `.jc-conv:hover` and `.jc-conv[aria-current="true"]` are both one class plus one qualifier,
        // so specificity ties and source order decides - and the selected rule is declared later.
        // The effect was that the one row a reader is most likely to point at was the only row in
        // the column that painted nothing under the pointer, and nothing on press.
        assertThat(css).contains(".jc-conv[aria-current=\"true\"]:hover");
        assertThat(css).contains(".jc-conv[aria-current=\"true\"]:active");
    }

    @Test
    @DisplayName("the focus ring is one ring, declared from the focus tokens and nowhere else")
    void everyFocusRingComesFromTheTokens() {
        // A ring hand-rolled from a literal colour is how a palette drifts: it survives a token
        // change untouched and is only noticed when someone photographs it next to another ring.
        Matcher rings = Pattern.compile("outline:\\s*([^;]+);").matcher(rules);
        List<String> handRolled = new ArrayList<>();
        while (rings.find()) {
            String value = rings.group(1).trim();
            if (value.startsWith("none") || value.contains("--jc-focus-ring")) {
                continue;
            }
            handRolled.add(value);
        }
        assertThat(handRolled).as("outline declarations that bypass the focus tokens").isEmpty();
    }

    /** Any rule that turns a box into a scroll container, with the selector it is written under. */
    private static final Pattern SCROLL_CONTAINER = Pattern.compile(
            "([^{}]*)\\{([^{}]*?overflow(?:-[xy])?:\\s*(?:auto|scroll)[^{}]*)}");

    /**
     * The scroll containers a keyboard cannot land on, and therefore the only ones allowed no ring.
     *
     * <p>Chrome gives a scroll container its own tab stop unless the container holds something
     * focusable, on the reasoning that a region a reader can scroll is a region a reader must be able
     * to reach. That rule is the whole of this roster: each of these is exempt because the reader
     * lands on something inside it instead, never on the scroller.
     *
     * <p>--jc-sidebar__scroll holds the conversation rows, every one of them a button. The two
     * textareas are focusable in their own right and their ring is drawn on the field around them -
     * .jc-composer__field on :focus-within and .jc-message__editor's own input-field part.
     *
     * <p>.jc-main__content > .jc-settings holds nothing but form controls and buttons from its first
     * scrollable pixel to its last - unlike --jc-sidebar__scroll, which leads with a header before its
     * first button, this one has no empty run before a focusable child either. Measured live the same
     * way the sidebar exemption above was: five Tabs from the page heading landed on a select, three
     * inputs and a password-field toggle, never once on the scroll container itself.
     *
     * <p>What is deliberately <em>not</em> here is anything holding only prose: a code block, a wide
     * table and a display formula each scroll sideways with nothing focusable inside, so each is a
     * real tab stop and each needs the ring this sheet draws everywhere else.
     */
    private static final List<String> SCROLLERS_WITHOUT_A_TAB_STOP = List.of(
            ".jc-sidebar__scroll", ".jc-message__editor > textarea",
            ".jc-composer vaadin-text-area > textarea", ".jc-main__content > .jc-settings");

    /**
     * That every scroll container a keyboard can reach answers it with this product's ring.
     *
     * <p>The defect this pins, and it is the third time this list has been the thing that was wrong:
     * `.jc-prose .jc-code pre` takes overflow-x: auto so a long line can be read, which makes it a
     * tab stop - measured live as stop 13 of 16 in an answered transcript, reporting
     * {@code outline: 1px auto rgb(0, 95, 204)} while the fifteen stops either side of it reported
     * {@code outline: 2px solid rgb(164, 76, 8)}. The sheet's own comment above the shared list
     * already records two earlier rounds where a control was added to the transcript and forgotten
     * here.
     *
     * <p>Derived from the sheet rather than from a list of names, which is the difference between
     * this and the roster in {@link #everyControlDeclaresEveryState()}: what creates the tab stop is
     * the overflow declaration, so the overflow declarations are what is enumerated. The exemptions
     * are named, because "this one cannot be focused" is a claim that has to be made deliberately.
     *
     * <p>Fails on the original on the code block, on the table wrapper and on a display formula - the
     * critic saw only the first, and all three are the same defect.
     */
    @Test
    @DisplayName("every scroll region a keyboard can land on draws this sheet's ring, not Chrome's")
    void everyFocusableScrollerDrawsTheSharedRing() {
        List<String> uaRing = new ArrayList<>();
        List<String> found = new ArrayList<>();
        Matcher scrollers = SCROLL_CONTAINER.matcher(rules);
        while (scrollers.find()) {
            String selector = scrollers.group(1).replaceAll("\\s+", " ").trim();
            found.add(selector);
            if (SCROLLERS_WITHOUT_A_TAB_STOP.contains(selector)) {
                continue;
            }
            if (!css.contains(selector + ":focus-visible")) {
                uaRing.add(selector + " scrolls and is focusable, and declares no focus-visible ring");
            }
        }
        assertThat(uaRing).as("scroll regions left wearing the browser's own focus ring").isEmpty();
        // A sheet that had quietly lost its scrollers would otherwise pass this in silence, and an
        // exemption naming a selector the sheet no longer has is an exemption nobody will notice.
        assertThat(found).as("the scroll regions this sheet declares")
                .contains(".jc-transcript", ".jc-prose .jc-code pre", ".jc-table")
                .containsAll(SCROLLERS_WITHOUT_A_TAB_STOP);
    }

    @Test
    @DisplayName("a coarse pointer gets targets it can hit")
    void coarsePointersAreSized() {
        assertThat(css).contains("@media (pointer: coarse)");
        String block = ruleFor("@media (pointer: coarse)");
        // 44px. Below it a target is smaller than the finger pressing it.
        assertThat(block).contains("2.75rem");
    }

    /**
     * The touch target floor, in rem. Below it a control is smaller than the finger pressing it.
     *
     * <p>2.75rem is 44px at the 16px root, which is what both WCAG 2.5.5 and every platform's own
     * guidance settle on.
     */
    private static final double TOUCH_TARGET_REM = 2.75;

    /** Every rest-state declaration that fixes how large a control's box is. */
    private static final Pattern BOX_DECLARATION = Pattern.compile(
            "(?m)^\\s*(?:min-)?(?:width|height):\\s*(var\\(--jc-[\\w-]+\\)|[\\d.]+rem)\\s*;");

    /**
     * That a coarse pointer can hit every control this sheet gives a small box to.
     *
     * <p>Derived from the sizes rather than from a list of names, which is the whole difference
     * between this and the claim above it. The old test asserted that the coarse block mentioned
     * 2.75rem <em>somewhere</em>, so it passed while nine controls were listed and a tenth was not:
     * .jc-jump__button stayed 36x36 under both pointer types - 8px short in both axes, on the one
     * control a reader reaches for one-handed in the middle of an answer.
     *
     * <p>Fails on the original, on .jc-jump__button.
     */
    @Test
    @DisplayName("every control the sheet draws under 44px is raised for a finger")
    void everyUndersizedControlIsRaisedForACoarsePointer() {
        String coarse = mediaBlock("@media (pointer: coarse)");
        List<String> undersized = new ArrayList<>();
        for (String control : allControls()) {
            double smallest = smallestBox(ruleFor(control + " {"));
            if (smallest >= TOUCH_TARGET_REM * 16 || smallest == 0) {
                continue;
            }
            // Present in the block and raised by it: naming a selector there without giving it a
            // target size would be the same silence with a name attached.
            if (!coarse.contains(control) || smallestBox(coarseRuleFor(coarse, control)) < TOUCH_TARGET_REM * 16) {
                undersized.add("%s rests at %.0fpx and is not raised for a coarse pointer"
                        .formatted(control, smallest));
            }
        }
        assertThat(undersized).as("controls a finger cannot hit").isEmpty();
    }

    /**
     * Every control in this sheet whose box a finger has to land on.
     *
     * @return the two rosters section 9 already speaks for, as one list
     */
    private static List<String> allControls() {
        List<String> all = new ArrayList<>(CONTROLS);
        all.addAll(VAADIN_BUTTONS);
        return all;
    }

    /**
     * The smallest box a rule fixes, in CSS pixels.
     *
     * @param rule a declaration block
     * @return the smallest width, height or min-height it declares, or 0 when it fixes none - a
     *         control sized by its padding and its text is not something this can answer for, and
     *         guessing at one would be a failure invented rather than measured
     */
    private double smallestBox(String rule) {
        double smallest = 0;
        Matcher boxes = BOX_DECLARATION.matcher(rule);
        while (boxes.find()) {
            String value = boxes.group(1);
            double px = value.startsWith("var(")
                    ? lengthOf(value.substring(4, value.length() - 1))
                    : rem(value);
            smallest = smallest == 0 ? px : Math.min(smallest, px);
        }
        return smallest;
    }

    /**
     * What the coarse-pointer block declares for one control.
     *
     * <p>Selectors are grouped there - several controls share one declaration block - so the block
     * is searched from the selector to the next closing brace rather than assuming a rule per name.
     *
     * @param coarse the whole coarse-pointer media block
     * @param control the selector to read
     * @return its declarations, empty when the block never names it
     */
    private static String coarseRuleFor(String coarse, String control) {
        int start = coarse.indexOf(control);
        if (start < 0) {
            return "";
        }
        int end = coarse.indexOf('}', start);
        return end < 0 ? coarse.substring(start) : coarse.substring(start, end);
    }

    // ------------------------------------------------------------------ the shared optical axis

    /** A media block's own bound, and the rules inside it. */
    private static final Pattern TOGGLE_LIFTED = Pattern.compile(
            "@media \\(min-width: ([\\d.]+)rem\\) \\{\\s*vaadin-button\\.jc-topbar__toggle \\{");

    /**
     * That the header never asserts a leading edge, or a centre, of its own.
     *
     * <p>One optical axis runs down this layout: the bar's title, the first word of every answer and
     * the composer's own text all begin on it. The hamburger broke that wherever it was in flow - it
     * took the measure's leading edge and pushed the heading 41-56px inboard - and the first repair
     * was applied at 832px and up only.
     *
     * <p>The second repair is the defect this now pins. Below 832px the heading was centred instead,
     * on the argument that a title asserting no axis cannot compete with the transcript's. Measured
     * live, it competes hard: the title's ink centre is exactly viewport/2 at every width below the
     * bound, which put it 221px from the message body's leading edge at 768px and 89px at 360px,
     * while the hamburger sat <em>on</em> that leading edge. Two axes, and the loud one belonged to
     * a control rather than to the title.
     *
     * <p>So there is one arrangement now, and this asserts the three things that keep it one. The
     * toggle is parked out of flow wherever the gutter outside the measure can hold it, and that
     * width is derived from the tokens rather than typed - the original typed 832px where 720px is
     * what the arithmetic asks for, which is 112px of viewport handed to the wrong arrangement.
     * Below it the toggle is in flow and the title follows it along the same edge, one control and
     * one gap inboard, which is the least a leading control can cost. And nothing anywhere centres
     * the heading, at any width.
     *
     * <p>Fails on the original three times over: on the bound, on the second arrangement existing at
     * all, and on the {@code text-align: center} inside it.
     */
    @Test
    @DisplayName("the header shares the transcript's leading edge and never centres its title")
    void theHeaderNeverAssertsASecondLeadingAxis() {
        Matcher lift = TOGGLE_LIFTED.matcher(css);
        assertThat(lift.find()).as("the toggle is lifted out of flow somewhere").isTrue();
        assertThat(mediaBlock("@media (min-width: " + lift.group(1) + "rem)"))
                .as("the wide arrangement parks the toggle outside the measure")
                .contains("position: absolute");

        // What the parked control needs to the left of the title's first glyph: its own inset from
        // the band's edge, its widest box - a coarse pointer's - and one gap of clearance.
        double parked = rem(declared("--jc-space-s"))
                + TOUCH_TARGET_REM * 16
                + rem(declared("--jc-space-xs"));
        // The narrowest viewport that offers it. Half of what a viewport has over the measure is
        // gutter on this side, and the measure's own padding is leading room too.
        double narrowest = rem(declared("--jc-measure")) + 2 * (parked - rem(declared("--jc-space-l")));
        double liftsAt = rem(lift.group(1) + "rem");
        assertThat(liftsAt)
                .as("%.0fpx of leading room is needed, and a viewport first offers it at %.0fpx",
                        parked, narrowest)
                .isGreaterThanOrEqualTo(narrowest)
                // Rounded up to the next whole rem and no further: every width between the two is a
                // width where the axis could have been kept and was not.
                .isLessThan(narrowest + 16);

        assertThat(narrowHeaderBlock())
                .as("one arrangement, not two - the second one is where the centred title got in")
                .isEmpty();
        for (String rule : rulesNaming(".jc-topbar__heading")) {
            assertThat(rule)
                    .as("a centred title is a second axis, and it measured 221px off the first")
                    .doesNotContain("text-align: center")
                    .doesNotContain("justify-self: center");
        }
        assertThat(ruleFor(".jc-topbar__inner {"))
                .as("one row, so a title that cannot have the edge starts as near it as it can")
                .contains("display: flex");
    }

    /**
     * The narrow header's own media block, if the sheet has grown one again.
     *
     * <p>Comments are stripped before the block is searched, because this file's prose names the
     * selectors it is about and a rule that no longer exists is still described by the paragraph
     * explaining why it does not.
     *
     * @return the whole block including its {@code @media} line, or empty when no narrow block
     *         declares anything for the heading - which is the passing case
     */
    private String narrowHeaderBlock() {
        Matcher blocks = Pattern.compile("@media \\(max-width: [\\d.]+rem\\)").matcher(css);
        while (blocks.find()) {
            String block = mediaBlock(blocks.group());
            if (block.replaceAll("(?s)/\\*.*?\\*/", "").contains(".jc-topbar__heading")) {
                return block;
            }
        }
        return "";
    }

    /**
     * Every declaration block whose selector names something, wherever in the sheet it is written.
     *
     * <p>Read off the comment-free copy of the sheet, and taken from the start of the whole selector
     * rather than from the match: a rule is often reached through a match on its last compound, and
     * the part that says <em>when</em> it applies - a state attribute on an ancestor - is all in
     * front of that.
     *
     * @param selector the selector text to look for, as written in the sheet
     * @return one entry per rule, each running from the first character of its selector list to the
     *         last declaration before its closing brace; empty when the sheet never names it
     */
    private List<String> rulesNaming(String selector) {
        List<String> found = new ArrayList<>();
        int at = rules.indexOf(selector);
        while (at >= 0) {
            int from = Math.max(rules.lastIndexOf('}', at), rules.lastIndexOf('{', at)) + 1;
            int end = rules.indexOf('}', at);
            found.add(rules.substring(from, end < 0 ? rules.length() : end));
            at = rules.indexOf(selector, at + selector.length());
        }
        return found;
    }

    // ------------------------------------------------------------------ what the chrome costs

    /**
     * That the composer holds still while a turn starts.
     *
     * <p>The defect this pins, measured at 1440x900 in both themes: at rest the divider between the
     * transcript and the composer sat at y=813 and the composer box at y=830..884; while streaming
     * the divider was at y=781 and the composer at y=798..852. The whole bar and the transcript
     * above it jumped 32px upward at the instant the Send button became Stop, and 32px is exactly
     * the status line's 24px band plus the 8px flex gap above it - a band that did not exist until
     * "JavaClaw is responding" was put into it. The reference held its composer at y=796..881 across
     * idle, streaming and rich answers.
     *
     * <p>The first attempt at this reserved the band on {@code :focus-within} as well, so a turn
     * sent from the composer found it already open. That closes the reported keystroke and leaves
     * the defect: the band still comes and goes, only now it comes when the reader clicks into the
     * field - which happens far more often than a turn starts - and an idle screenshot still differs
     * from a streaming one by the same 32px.
     *
     * <p>So the property is the one a reader can see, not the path they took to it: nothing about a
     * turn, a caret or a refusal may change the composer's height. The band is there before there is
     * anything in it, the key caps are hidden in place rather than taken out of flow, and the single
     * rule that does remove the band - going offline - hands the same reserved height to the
     * sentence that replaces it.
     *
     * <p>Costs 32px of transcript on every screen, deliberately: that reclaim is what created this.
     *
     * <p>Fails on the original on all three claims - the resting rule declares {@code display: none},
     * {@code .jc-composer__hint:has(.jc-dots)} switches the band back on for a streaming turn, and
     * there is no rule hiding the key caps in place because they were hidden by not existing.
     */
    @Test
    @DisplayName("the composer's status line takes the same band whether or not a turn is streaming")
    void theComposerDoesNotMoveWhenATurnStarts() {
        assertThat(ruleFor(".jc-composer__hint {"))
                .as("the band is there before there is anything in it, or arriving in it moves the page")
                .contains("display: block")
                .contains("min-height: 1.5rem");

        List<String> switched = new ArrayList<>();
        for (String rule : rulesNaming(".jc-composer__hint")) {
            String selector = rule.substring(0, rule.indexOf('{')).trim();
            // The key cap's own box, which is inside the band rather than being it.
            if (selector.endsWith("kbd")) {
                continue;
            }
            // The unconditional rule is the band, and the offline swap is the one state allowed to
            // take it away - only because it is compensated to the pixel, asserted below.
            if (selector.equals(".jc-composer__hint")
                    || selector.contains("[data-connection=\"offline\"]")
                    || !rule.contains("display:")) {
                continue;
            }
            switched.add(selector);
        }
        assertThat(switched)
                .as("rules that make the band come and go under the composer, and the page with it")
                .isEmpty();

        assertThat(ruleFor(".jc-shell[data-connection=\"offline\"] .jc-composer__offline {"))
                .as("the offline sentence takes the band the key caps just gave up, to the pixel")
                .contains("min-height: 1.5rem");

        assertThat(ruleFor(".jc-composer__hint:has(kbd) {"))
                .as("the key caps are hidden in place, so showing them cannot move the composer")
                .contains("visibility: hidden");
    }

    /**
     * That losing the connection is something this interface draws.
     *
     * <p>The defect this pins: a grep for "offline" or "disconnect" across the sheet returned
     * nothing at all, which is the measurement that says a state has no owner. An audit went offline
     * and waited fourteen seconds; the composer kept the text, the send control kept its resting
     * treatment, no turn appeared, and the only thing on screen was Vaadin's own five-pixel striped
     * band whose sentence is in the DOM and is not painted readably.
     *
     * <p>Three claims. There is a band with something to say and it costs nothing while there is
     * nothing to say. The composer's hint line - which is where a reader whose keystroke did nothing
     * is looking - swaps to the sentence about being offline rather than going on advertising a key
     * that will not work. And the swap is declared after the rules that reserve the hint's band, or
     * it is a rule of equal weight that loses the cascade and paints nothing.
     *
     * <p>The band's own colours are named and then measured, because a state nobody can read is the
     * defect restated: this is a full line of prose, so it is held to AA rather than to 3:1.
     */
    @Test
    @DisplayName("being offline is a state this sheet paints, not one only Vaadin's band reports")
    void offlineIsAPaintedState() {
        String band = ruleFor(".jc-connection {");
        assertThat(band).as("silent, and taking no room, while the connection is up")
                .contains("display: none");
        assertThat(ruleFor(".jc-composer__offline {"))
                .as("the composer's offline line is equally quiet while there is nothing to say")
                .contains("display: none");

        assertThat(ruleFor(".jc-shell[data-connection=\"offline\"] .jc-connection {"))
                .contains("display: block");
        assertThat(ruleFor(".jc-shell[data-connection=\"offline\"] .jc-composer__hint {"))
                .as("the key caps stop advertising a key that cannot reach anything")
                .contains("display: none");
        assertThat(ruleFor(".jc-shell[data-connection=\"offline\"] .jc-composer__offline {"))
                .contains("display: block");

        assertThat(rules.indexOf(".jc-shell[data-connection=\"offline\"] .jc-composer__hint"))
                .as("the swap is declared after the rule that reserves the hint's band")
                .isGreaterThan(rules.indexOf(".jc-composer__hint {"));

        assertThat(band).contains("color: var(--jc-text)")
                .contains("background: var(--jc-surface-sunken)");
        for (boolean light : new boolean[]{true, false}) {
            assertThat(contrast(token("text", light), token("surface-sunken", light)))
                    .as("the offline sentence against the band it is painted on, %s",
                            light ? "light" : "dark")
                    .isGreaterThanOrEqualTo(AA);
        }
    }

    /**
     * That the empty state has one inline axis rather than two.
     *
     * <p>Measured at 1440x900 and again at 834x1112: the brand mark, the greeting and the subtitle
     * were all centred on the column's midpoint, and the row of prompt chips underneath began on the
     * column's leading edge - so the first chip row sat 46px left of the axis above it and the
     * second, being shorter, 180px left. Two alignments in one block of six elements reads as a
     * layout that has come apart, whichever of the two is right.
     *
     * <p>Resolved towards the leading edge rather than the centre, because that is the axis the rest
     * of this application asserts: the bar's title, the first word of every answer and the composer's
     * own text all begin on it, and the greeting is replaced in place by the first answer. Centring
     * the chips would have agreed with the hero and disagreed with everything that comes after it.
     *
     * <p>Fails on the original, which centres the hero over a leading-edge chip row.
     */
    @Test
    @DisplayName("the empty state agrees with itself about where a line begins")
    void theEmptyStateHasOneInlineAxis() {
        String hero = ruleFor(".jc-empty {");
        String chips = ruleFor(".jc-starters {");

        assertThat(inlineAxis(hero, "align-items"))
                .as("the hero's own axis, in %s", hero)
                .isEqualTo(inlineAxis(chips, "justify-content"));
        assertThat(hero).as("and the text inside it follows the same edge")
                .contains("text-align: start");
    }

    /**
     * Which edge a flex alignment settles on, normalised across the two properties that say it.
     *
     * @param rule     a declaration block
     * @param property {@code align-items} on a column, {@code justify-content} on a row
     * @return "start", "center" or "end"
     */
    private static String inlineAxis(String rule, String property) {
        Matcher matcher = Pattern.compile(property + ":\\s*(?:flex-)?(\\w+)\\s*;").matcher(rule);
        return matcher.find() ? matcher.group(1) : fail("No " + property + " in " + rule);
    }

    /** A colour written as {@code rgba(r, g, b, a)}, which is how the scrim declares both of its. */
    private static final Pattern SCRIM = Pattern.compile(
            "background:\\s*light-dark\\(\\s*rgba\\(([^)]+)\\),\\s*rgba\\(([^)]+)\\)\\s*\\)");

    /**
     * That the scrim dims the page it has taken away.
     *
     * <p>The drawer is a modal in everything but name: it makes the page behind it inert, announces
     * itself as a dialog and closes on Escape. The shade in front of that page is what says so to the
     * reader who is looking rather than listening, and at 0.44 in light it did not: the canvas fell
     * from 251 to 153, a 39% drop against the reference's 60%, and at that strength the theme
     * control, the chip outlines and the model name in the 87px strip beside the drawer all kept
     * crisp edges - a page that has been taken away still reading as live interface.
     *
     * <p>Stated as a share of the surface rather than as an alpha, because what matters is how much
     * of the page survives the shade and the two themes shade different grounds.
     *
     * <p>Fails on the original in light, which leaves 61% of the surface standing.
     */
    @Test
    @DisplayName("the scrim leaves no more than half the page it covers")
    void theScrimDimsThePageItTakesAway() {
        Matcher scrim = SCRIM.matcher(ruleFor(".jc-scrim {"));
        assertThat(scrim.find()).as("the scrim declares a shade for each theme").isTrue();

        for (boolean light : new boolean[]{true, false}) {
            int[] surface = token("surface", light);
            double[] shade = shadeOf(scrim.group(light ? 1 : 2));
            for (int channel = 0; channel < 3; channel++) {
                double behind = shade[3] * shade[channel] + (1 - shade[3]) * surface[channel];
                assertThat(behind)
                        .as("channel %d of --jc-surface behind the %s scrim",
                                channel, light ? "light" : "dark")
                        .isLessThanOrEqualTo(surface[channel] / 2.0);
            }
        }
    }

    /**
     * An {@code rgba()} argument list as four numbers.
     *
     * @param arguments the inside of the parentheses, as written
     * @return red, green, blue and alpha
     */
    private static double[] shadeOf(String arguments) {
        String[] parts = arguments.split(",");
        return new double[]{
                Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim())};
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A whole media block, braces balanced, rather than up to its first inner rule.
     *
     * <p>{@link #ruleFor(String)} stops at the first closing brace, which for an {@code @media} is
     * the end of the first rule inside it - so a claim about what a block does and does not contain
     * has to count braces or it is a claim about one rule.
     *
     * @param query the block's opening text, as written in the sheet
     * @return the block including its opening line, or empty when the sheet has no such block
     */
    private String mediaBlock(String query) {
        int start = css.indexOf(query);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int at = css.indexOf('{', start); at < css.length(); at++) {
            if (css.charAt(at) == '{') {
                depth++;
            } else if (css.charAt(at) == '}' && --depth == 0) {
                return css.substring(start, at + 1);
            }
        }
        return css.substring(start);
    }

    /** The value a custom property is declared with, as written. */
    private String declared(String property) {
        Matcher matcher = Pattern.compile(Pattern.quote(property) + ":\\s*([^;]+);").matcher(css);
        return matcher.find() ? matcher.group(1).trim() : fail("No declaration of " + property);
    }

    /** A rem length in CSS pixels, at the 16px root the shell never overrides. */
    private static double rem(String length) {
        return Double.parseDouble(length.replace("rem", "").trim()) * 16;
    }

    /**
     * The declaration block of the <em>last</em> rule written for a selector.
     *
     * <p>Needed wherever a selector appears both on its own and inside a grouped one. The heading
     * tiers are the case: {@code .jc-prose h3} closes the group that gives all three headings their
     * weight and then has a rule of its own giving it its size, so a search from the front returns
     * the group and reports that h3 declares no size at all. Taking the last is also the right rule
     * to take on its own terms - between two rules of equal specificity, the later one is the one the
     * cascade leaves standing, which is the one the reader sees.
     *
     * @param selector the selector text, including its opening brace
     * @return the declarations of the last rule introduced by it
     */
    private String lastRuleFor(String selector) {
        int start = css.lastIndexOf(selector);
        if (start < 0) {
            return fail("No rule for " + selector + " in " + STYLESHEET);
        }
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end);
    }

    /** The declaration block following a selector, up to its first closing brace. */
    private String ruleFor(String selector) {
        int start = css.indexOf(selector);
        if (start < 0) {
            return fail("No rule for " + selector + " in " + STYLESHEET);
        }
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end);
    }

    private int[] token(String name, boolean light) {
        Matcher matcher = TOKEN.matcher(css);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return rgb(matcher.group(light ? 2 : 3));
            }
        }
        return fail("No light-dark() token named --jc-" + name);
    }

    /** color-mix(in srgb, accent 22%, ground), rounded the way a browser rounds it. */
    private static int[] mix(int[] accent, int[] ground) {
        return mix(accent, ground, 0.22);
    }

    /**
     * The same mix at an arbitrary share, for the deeper grounds the selected row moves through.
     *
     * @param share the accent's fraction, matching the percentage in the token's color-mix()
     */
    private static int[] mix(int[] accent, int[] ground, double share) {
        int[] mixed = new int[3];
        for (int channel = 0; channel < 3; channel++) {
            mixed[channel] = (int) Math.round(share * accent[channel] + (1 - share) * ground[channel]);
        }
        return mixed;
    }

    private static int[] rgb(String hex) {
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }

    private static double contrast(int[] ink, int[] ground) {
        double a = luminance(ink);
        double b = luminance(ground);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** WCAG relative luminance. */
    private static double luminance(int[] colour) {
        double[] linear = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double value = colour[channel] / 255.0;
            linear[channel] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    private static String readStylesheet() {
        try (InputStream stream = StylesheetClaimsTest.class.getResourceAsStream(STYLESHEET)) {
            if (stream == null) {
                return fail("The stylesheet is not on the classpath at " + STYLESHEET);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("Could not read " + STYLESHEET, e);
        }
    }
}
