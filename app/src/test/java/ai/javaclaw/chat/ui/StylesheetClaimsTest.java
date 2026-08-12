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
        assertThat(ruleFor(".jc-composer vaadin-text-area[readonly]::part(input-field)"))
                .contains("border: 0");
        // Locked with [readonly] today; the disabled path is carried for the same reason the rule
        // above it carries it, so nothing restores the dash if the lock ever changes.
        assertThat(css).contains(".jc-composer vaadin-text-area[disabled]::part(input-field)");
    }

    @Test
    @DisplayName("the field a turn is streaming into is still painted as a field")
    void theBusyFieldKeepsAFillOfItsOwn() {
        String busy = ruleFor(".jc-composer__field--busy {");
        // The rule's own comment states the intent its old value failed: stepping down to the page
        // ground put the locked field within 1.05:1 of the band it sits in, so nothing but the focus
        // ring said it was a control. It keeps the idle field's raised tone and gives up its shadow.
        assertThat(busy).doesNotContain("background:");
        assertThat(busy).contains("box-shadow: none");
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

    @Test
    @DisplayName("a coarse pointer gets targets it can hit")
    void coarsePointersAreSized() {
        assertThat(css).contains("@media (pointer: coarse)");
        String block = ruleFor("@media (pointer: coarse)");
        // 44px. Below it a target is smaller than the finger pressing it.
        assertThat(block).contains("2.75rem");
    }

    // ------------------------------------------------------------------ helpers

    /** The value a custom property is declared with, as written. */
    private String declared(String property) {
        Matcher matcher = Pattern.compile(Pattern.quote(property) + ":\\s*([^;]+);").matcher(css);
        return matcher.find() ? matcher.group(1).trim() : fail("No declaration of " + property);
    }

    /** A rem length in CSS pixels, at the 16px root the shell never overrides. */
    private static double rem(String length) {
        return Double.parseDouble(length.replace("rem", "").trim()) * 16;
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
