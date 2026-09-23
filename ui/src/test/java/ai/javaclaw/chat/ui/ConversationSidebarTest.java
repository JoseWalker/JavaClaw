package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the navigation column does when it is used.
 *
 * <p>The column's whole purpose is to hand a conversation to whatever is showing one, so the first
 * thing pinned here is that a click actually reports the row that was clicked - a list that renders
 * threads but never says which one was picked is decoration. The rest is the state the column owns
 * on the reader's behalf: what the search box hides, which row is marked, and the fact that a chat
 * started here becomes a real conversation rather than a label.
 */
class ConversationSidebarTest {

    @Test
    @DisplayName("clicking a row reports that row, and marks it as the reader's place")
    void reportsTheClickedConversation() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Live chat"),
                stored("web-abc", "Comparing build tools"));
        AtomicReference<ConversationSummary> picked = new AtomicReference<>();
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", picked::set, () -> {
        });

        click(rowTitled(sidebar, "Comparing build tools"));

        assertThat(picked.get()).isNotNull();
        assertThat(picked.get().id()).isEqualTo("web-abc");
        assertThat(rowTitled(sidebar, "Comparing build tools").getAttribute("aria-current")).isEqualTo("true");
        assertThat(rowTitled(sidebar, "Live chat").getAttribute("aria-current")).isEqualTo("false");
    }

    @Test
    @DisplayName("the row the view is already showing is marked before anything is clicked")
    void marksTheActiveRowOnFirstPaint() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"), stored("web-abc", "Something else"));

        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        assertThat(rowTitled(sidebar, "Live chat").getAttribute("aria-current")).isEqualTo("true");
    }

    @Test
    @DisplayName("the column says out loud what it is calling the row the reader is in")
    void reportsTheActiveRowsName() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"));
        List<ConversationSummary> reported = new ArrayList<>();
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });
        sidebar.whenActiveNamed(reported::add);

        directory.replace(stored("web", "Comparing build tools"));
        sidebar.refresh();

        // Nothing else in the shell reads the directory, so this report is the only way anything
        // outside the column can name the open conversation the same way the column does.
        assertThat(reported).isNotEmpty();
        assertThat(reported.getLast().id()).isEqualTo("web");
        assertThat(reported.getLast().title()).isEqualTo("Comparing build tools");
    }

    @Test
    @DisplayName("a name arriving for some other conversation is not reported as the open one")
    void reportsNothingForARowTheReaderIsNotIn() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"));
        List<ConversationSummary> reported = new ArrayList<>();
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });
        sidebar.whenActiveNamed(reported::add);

        directory.add(stored("web-abc", "Comparing build tools"));
        sidebar.refresh();

        assertThat(reported).allSatisfy(row -> assertThat(row.id()).isEqualTo("web"));
    }

    @Test
    @DisplayName("search narrows the list to the conversations that match it")
    void searchFiltersTheHistory() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Comparing build tools"),
                stored("web-abc", "Virtual threads and the session lock"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        search(sidebar).setValue("virtual");

        // Fitted to the column, not echoed at whatever length the store holds: the label is what a
        // 231px box can draw, and the untruncated title stays reachable in the row's tooltip.
        assertThat(titles(sidebar)).singleElement()
                .satisfies(title -> assertThat(title).startsWith("Virtual threads and the session"));
    }

    @Test
    @DisplayName("a search that matches nothing says so rather than showing an empty column")
    void searchWithNoMatchesExplainsItself() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Comparing build tools"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        search(sidebar).setValue("nothing like this");

        assertThat(titles(sidebar)).isEmpty();
        assertThat(sidebar.getElement().getTextRecursively()).contains("No chats match");
    }

    @Test
    @DisplayName("new chat opens a real conversation id, not a label")
    void newChatMintsARealConversation() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"));
        AtomicReference<ConversationSummary> picked = new AtomicReference<>();
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", picked::set, () -> {
        });

        click(element(sidebar, "jc-new-chat"));

        assertThat(picked.get()).isNotNull();
        assertThat(picked.get().source()).isEqualTo(ConversationSummary.Source.DRAFT);
        assertThat(ConversationIds.isWellFormed(picked.get().id())).isTrue();
        assertThat(titles(sidebar)).contains("New chat");
    }

    @Test
    @DisplayName("once the store knows the new chat, the placeholder row stops being drawn twice")
    void refreshDropsTheDraftTheStoreHasCaughtUpWith() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"));
        AtomicReference<ConversationSummary> picked = new AtomicReference<>();
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", picked::set, () -> {
        });
        click(element(sidebar, "jc-new-chat"));

        directory.add(stored(picked.get().id(), "What is a virtual thread?"));
        sidebar.refresh();

        assertThat(titles(sidebar)).containsExactlyInAnyOrder("Live chat", "What is a virtual thread?");
    }

    @Test
    @DisplayName("one channel is not a folder, so no heading is drawn over the whole list")
    void dropsAHeadingThatDistinguishesNothing() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Live chat"),
                stored("web-abc", "Comparing build tools"));

        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        assertThat(groupLabels(sidebar)).isEmpty();
        assertThat(titles(sidebar)).containsExactly("Live chat", "Comparing build tools");
    }

    @Test
    @DisplayName("headings appear as soon as they tell two kinds of conversation apart")
    void keepsHeadingsThatSeparateChannels() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Live chat"),
                new ConversationSummary("telegram-42", "From my phone", "", "Telegram", List.of(),
                        Optional.empty(), ConversationSummary.Source.STORED));

        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        assertThat(groupLabels(sidebar)).containsExactly("Web", "Telegram");
    }

    @Test
    @DisplayName("no two rows on screen are called the same thing, whatever the store holds")
    void everyVisibleTitleIsDistinct() {
        // The real store looks like this: the same question asked twice, and a run of threads that
        // open with the same phrase. Before titles were derived, two of these rows rendered
        // byte-identical and five more shared their first seventeen characters.
        FakeDirectory directory = new FakeDirectory(
                asked("web-1", "List the 15 most significant features added to Java between 8 and 21, one per line."),
                asked("web-2", "List the 15 most significant features added to Java between 8 and 21, one per line."),
                asked("web-3", "In one sentence, what is a Vaadin session?"),
                asked("web-4", "In one sentence, what is a sealed interface?"),
                asked("web-5", "In one sentence, what does Spring Modulith verify?"),
                asked("web-6", "Explain virtual threads in exactly two sentences."));

        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        assertThat(titles(sidebar)).doesNotHaveDuplicates();
        assertThat(titles(sidebar)).allSatisfy(title -> assertThat(title).isNotBlank());
    }

    @Test
    @DisplayName("a search that narrows the list still leaves every visible row distinct")
    void titlesStayDistinctUnderSearch() {
        FakeDirectory directory = new FakeDirectory(
                asked("web-1", "Explain the composer layout in exactly two sentences."),
                asked("web-2", "Explain the composer layout in exactly two sentences."),
                asked("web-3", "Comparing build tools"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        search(sidebar).setValue("composer");

        assertThat(titles(sidebar)).hasSize(2);
        assertThat(titles(sidebar)).doesNotHaveDuplicates();
    }

    /**
     * The row was announced as whatever survived the column's width.
     *
     * <p>A label is cut to fit 231px, and the cut half was reachable only through a {@code title}
     * attribute - a tooltip, which is a pointer affordance and is not part of the accessible name. So
     * a screen reader read "…s 8 to 25" and the reader had no way at all to reach the rest, on the one
     * control whose entire purpose is to say which conversation it opens.
     *
     * <p>Fails on the original: the only text in the row was the fitted label.
     */
    @Test
    @DisplayName("a row is announced by the whole conversation title, not by the part that fitted")
    void announcesTheUntruncatedTitle() {
        String whole = "Compare the memory footprint of a platform thread and a virtual thread";
        FakeDirectory directory = new FakeDirectory(
                stored("web-1", whole), stored("web-2", "Comparing build tools"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        Element row = rowTitled(sidebar, "Compare the memory");

        // The label really is cut, or this test would pass on a column that never truncates.
        assertThat(titleOf(row)).isNotEqualTo(whole);
        assertThat(announcedText(row)).contains(whole);
    }

    /**
     * The half of the untruncated name that was still missing: the counter that tells the copies apart.
     *
     * <p>Threads that really did open with the same words get the same title, and the column
     * separates them with a leading occurrence marker - "(2) ", "(3) " - which is the only thing on
     * those rows that differs. That marker lives in the drawn label, and the drawn label is taken out
     * of the accessibility tree by {@code announceWholeTitle} so the row is not read out twice. The
     * result was sixteen rows with byte-identical accessible names: a sighted reader could at least
     * count them, and a screen-reader user was handed sixteen controls that claim to be the same one.
     *
     * <p>The marker is carried across verbatim rather than reworded, so what is spoken and what is
     * drawn stay the same string - a name rebuilt in different words is a name that drifts from the
     * row it belongs to.
     *
     * <p>Fails on the original, where both rows announce the bare title.
     */
    @Test
    @DisplayName("two threads that opened alike are announced apart, not just drawn apart")
    void announcesTheOccurrenceMarker() {
        String same = "List the 15 most significant features added to Java between 8 and 21, one per line.";
        FakeDirectory directory = new FakeDirectory(asked("web-1", same), asked("web-2", same));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        List<String> announced = descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-conv"))
                .map(ConversationSidebarTest::announcedText)
                .map(String::strip)
                .toList();

        assertThat(announced).hasSize(2);
        assertThat(announced).as("two rows a screen reader cannot tell apart").doesNotHaveDuplicates();
        // The marker itself, not merely "something differs": the column draws "(2) " and anything
        // else spoken here would be a second opinion about which row this is.
        assertThat(announced.get(1)).startsWith("(2) ");
    }

    /**
     * The other half of the same rule: what is announced must not be said twice.
     *
     * <p>A row the column draws in full needs no off-screen copy, and adding one anyway would have
     * every short conversation read out twice in a row.
     */
    @Test
    @DisplayName("a title the column draws in full is announced once, not twice")
    void doesNotAnnounceAShortTitleTwice() {
        FakeDirectory directory = new FakeDirectory(stored("web-1", "Comparing build tools"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        String announced = announcedText(rowTitled(sidebar, "Comparing build tools"));

        assertThat(announced.split("Comparing build tools", -1)).hasSize(2);
    }

    @Test
    @DisplayName("a chat with nothing behind it says so, and claims no time")
    void theDraftRowDoesNotClaimAStorageEvent() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Live chat"));
        ConversationSidebar sidebar = new ConversationSidebar(directory, "web", summary -> {
        }, () -> {
        });

        click(element(sidebar, "jc-new-chat"));

        Element draft = rowTitled(sidebar, "New chat");
        assertThat(draft.getAttribute("data-source")).isEqualTo("draft");
        // The badge is the mark. A relative time on this row would be a modification claim about a
        // conversation the store has never been told exists.
        assertThat(childClasses(draft)).contains("jc-conv__badge").doesNotContain("jc-conv__time");
    }

    @Test
    @DisplayName("the point where the column stops knowing when is marked, not left as a blank run")
    void marksTheSeamBetweenDatedAndUndatedRows() {
        // The store's own ordering: what this session watched being used, then everything else. The
        // second block has an empty trailing edge on every row, and two readers of the same capture
        // read that two different ways - as a list that had failed to load, and as a claim that the
        // rows below were the oldest. Neither is what it means.
        FakeDirectory directory = new FakeDirectory(
                dated("web-1", "Just used", Instant.now()),
                dated("web-2", "Used an hour ago", Instant.now().minusSeconds(3600)),
                stored("web-3", "Never seen used"),
                stored("web-4", "Nor this one"));

        ConversationSidebar sidebar = new ConversationSidebar(directory, "web-1", summary -> {
        }, () -> {
        });

        assertThat(seams(sidebar)).hasSize(1);
        // It has to be the boundary itself, not a heading over the whole block: the row above it is
        // the last one with a time and the row below it the first one without.
        assertThat(rowsAndSeams(sidebar)).containsExactly(
                "Just used", "Used an hour ago", "seam", "Never seen used", "Nor this one");
    }

    @Test
    @DisplayName("a column that is entirely undated is the one that most needs the explanation")
    void marksTheSeamWhenNoRowIsDated() {
        // The state this arises in is not a corner: names and last-used times both live in this
        // process, so the first render after every restart is a column where no row has a time and
        // several rows carry the same derived title separated by a bare ordinal. That is precisely
        // the reader who needs to be told the column is ordered by name - and the seam was drawn
        // only when a dated row already existed above an undated one, so it was absent here.
        FakeDirectory allUndated = new FakeDirectory(stored("web-1", "One"), stored("web-2", "Two"));

        ConversationSidebar sidebar = new ConversationSidebar(allUndated, "web-1", summary -> {
        }, () -> {
        });

        assertThat(seams(sidebar)).hasSize(1);
        // Above the first row it speaks for, in the same place it would sit if a dated block had
        // preceded it - which keeps it one marker with one meaning rather than two.
        assertThat(rowsAndSeams(sidebar)).containsExactly("seam", "One", "Two");
    }

    @Test
    @DisplayName("a column where every row has a time needs no explanation and gets none")
    void drawsNoSeamWhenEveryRowIsDated() {
        FakeDirectory allDated = new FakeDirectory(
                dated("web-3", "Three", Instant.now()), dated("web-4", "Four", Instant.now()));

        assertThat(seams(new ConversationSidebar(allDated, "web-3", summary -> {
        }, () -> {
        }))).isEmpty();
    }

    @Test
    @DisplayName("an empty store leaves an explanation, not a blank column")
    void emptyHistoryExplainsItself() {
        ConversationSidebar sidebar = new ConversationSidebar(new FakeDirectory(), "web", summary -> {
        }, () -> {
        });

        // The live conversation is always offered, even before the store has heard of it, so the
        // reader's own place is never missing from the column.
        assertThat(titles(sidebar)).containsExactly("New chat");
    }

    // ------------------------------------------------------------------ the keyboard's way through

    /**
     * That the column costs a keyboard one stop, not one per conversation.
     *
     * <p>Measured on the running application with 115 stored conversations: 125 Tab presses to reach
     * the composer, 114 of them conversation rows, against 19 for the product this is measured
     * against - whose list is not in the tab ring at rest at all. Every row was a native button with
     * a native tab stop, so the cost grew with the reader's own history: the more they had used the
     * application, the further the composer was from the top of the page.
     *
     * <p>The list is a composite widget rather than 115 controls, which is what the roving tabindex
     * says: one stop into the list, and the arrow keys to move inside it. Nothing about the layout
     * changes.
     *
     * <p>Fails on the original, where every row is a tab stop because none of them says otherwise.
     */
    @Test
    @DisplayName("the conversation list is one tab stop, whatever it is holding")
    void theListIsOneTabStop() {
        ConversationSidebar sidebar = new ConversationSidebar(
                new FakeDirectory(stored("web-a", "First"), stored("web-b", "Second"),
                        stored("web-c", "Third"), stored("web-d", "Fourth")),
                "web-c", summary -> {
                }, () -> {
                });

        assertThat(tabStops(sidebar))
                .as("the rows a Tab would stop on")
                .containsExactly("Third");
        assertThat(rows(sidebar))
                .allSatisfy(row -> assertThat(row.getAttribute("tabindex")).isNotNull());
    }

    /**
     * The invariant that makes the roving stop safe: there is always exactly one way in.
     *
     * <p>The stop belongs to the row the reader is in, and a search can hide that row. With the rule
     * stated only as "the marked row is the stop", such a search leaves every row at -1 and the whole
     * column unreachable from the keyboard - a worse defect than 114 stops, and a silent one.
     */
    @Test
    @DisplayName("a search that hides the reader's own row still leaves a way into the list")
    void theListNeverLosesItsOnlyTabStop() {
        ConversationSidebar sidebar = new ConversationSidebar(
                new FakeDirectory(stored("web-a", "Comparing build tools"),
                        stored("web-b", "Virtual threads")),
                "web-a", summary -> {
                }, () -> {
                });

        search(sidebar).setValue("threads");

        assertThat(tabStops(sidebar)).containsExactly("Virtual threads");
        // Declared rather than merely left alone: an absent tabindex is a stop by default, and the
        // whole point here is that the column decided which row carries it.
        assertThat(rowTitled(sidebar, "Virtual threads").getAttribute("tabindex")).isEqualTo("0");
    }

    /**
     * The other half of a roving stop: the keys that do the roving.
     *
     * <p>Without them the fix above is a regression - one stop into a list the reader then cannot
     * move through. Asserted on the caret actually being sent to the next row, rather than on a
     * listener existing, because a handler that never reaches the browser moves nobody.
     *
     * <p>Fails on the original, which answers no arrow key at all.
     */
    @Test
    @DisplayName("the arrow keys move down and up the list, and stop at its ends")
    void theArrowKeysMoveThroughTheList() {
        UI ui = UiHarness.ui();
        ConversationSidebar sidebar = new ConversationSidebar(
                new FakeDirectory(stored("web-a", "First"), stored("web-b", "Second"),
                        stored("web-c", "Third")),
                "web-a", summary -> {
                }, () -> {
                });
        ui.add(sidebar);
        focused(ui);

        UiHarness.press(rowTitled(sidebar, "First"), "ArrowDown");
        assertThat(focused(ui)).containsExactly("Second");

        UiHarness.press(rowTitled(sidebar, "Second"), "ArrowUp");
        assertThat(focused(ui)).containsExactly("First");

        // The ends are ends, not wraps: a list that loops has no way of telling a reader they have
        // reached the top of their own history.
        UiHarness.press(rowTitled(sidebar, "First"), "ArrowUp");
        assertThat(focused(ui)).isEmpty();
    }

    // ------------------------------------------------------------------ rows nobody can see

    /**
     * The hole the roving tab stop was written through: a group the reader folded away.
     *
     * <p>The rows of a folded group used to be drawn anyway and hidden in the stylesheet, which put
     * conversations in the tab ring and in the arrow-key sequence that no reader could see. The Java
     * side had no way of knowing: nothing in a component tree can read a {@code display: none} that
     * lives in a CSS file. So the column is asked the only question it can answer honestly - which
     * rows did it draw - and a folded group must contribute none.
     *
     * <p>Fails on the original, where the rows of a folded group are in the DOM.
     */
    @Test
    @DisplayName("a folded group contributes no rows at all")
    void aFoldedGroupDrawsNoRows() {
        ConversationSidebar sidebar = twoGroupColumn("web-1");

        foldGroup(sidebar, "Telegram");

        assertThat(titles(sidebar)).containsExactly("First", "Second");
        assertThat(rows(sidebar)).hasSize(2);
        assertThat(rowsInsideFoldedGroups(sidebar))
                .as("rows drawn into a group the reader has folded away")
                .isEmpty();
        // The heading still counts what it holds. The count comes from the model, so folding a group
        // must not make the column understate the history it is hiding.
        assertThat(groupCounts(sidebar)).containsExactly("2", "2");
    }

    /**
     * The failure the reviewer found: the column with a tab stop and no way to use it.
     *
     * <p>Open a conversation, fold the group it is filed under. The marked row kept {@code tabindex=0}
     * inside a container the stylesheet had set to {@code display: none}, and {@code ensureOneTabStop}
     * - which asks only whether some row carries a non-negative tabindex - was satisfied by it. The
     * column then had a tab stop no keyboard could reach and no reachable row at all, which is a
     * worse outcome than the 114 stops the roving tabindex replaced, and a silent one.
     *
     * <p>Fails on the original: the only tab stop is the hidden row.
     */
    @Test
    @DisplayName("folding the group the reader is in moves the tab stop to a row that is drawn")
    void foldingTheReadersOwnGroupKeepsAWayIntoTheList() {
        ConversationSidebar sidebar = twoGroupColumn("tg-1");
        assertThat(tabStops(sidebar)).containsExactly("Third");

        foldGroup(sidebar, "Telegram");

        assertThat(tabStops(sidebar))
                .as("the way into a column whose marked row has just been folded away")
                .containsExactly("First");
        assertThat(rowsInsideFoldedGroups(sidebar)).isEmpty();
    }

    /**
     * The invariant, stated over every fold state rather than over one of them.
     *
     * <p>Two rules, and they are the same rule seen from two sides: a column that has drawn a row has
     * exactly one row in the tab ring, and no row is drawn into a group that is folded. Asserted
     * across all four combinations of two groups because the defect this replaces was reachable only
     * from a combination the two existing keyboard tests could not produce - both of them use two
     * ungrouped conversations, so {@code groups.size() < 2} and the folding path never runs.
     */
    @Test
    @DisplayName("in every fold state the drawn rows carry exactly one tab stop between them")
    void exactlyOneDrawnRowIsATabStopWhateverIsFolded() {
        for (List<String> folded : List.of(List.<String>of(), List.of("Web"), List.of("Telegram"),
                List.of("Web", "Telegram"))) {
            ConversationSidebar sidebar = twoGroupColumn("tg-1");
            folded.forEach(name -> foldGroup(sidebar, name));

            assertThat(rowsInsideFoldedGroups(sidebar)).as("folded %s", folded).isEmpty();
            assertThat(tabStops(sidebar))
                    .as("tab stops among the drawn rows, folded %s", folded)
                    .hasSize(rows(sidebar).isEmpty() ? 0 : 1);
        }
    }

    /**
     * The arrow keys move through what is on screen, not through what was built.
     *
     * <p>{@code End} on the original walked to the last entry of the render list, which included the
     * rows of a folded group - so the caret was sent to {@code focus()} on a hidden element, which the
     * browser ignores, <em>after</em> the previous row's tabindex had already been set to -1. One
     * keystroke, and the column had no tab stop and no focus.
     *
     * <p>Fails on the original, where End reaches "Fourth".
     */
    @Test
    @DisplayName("the arrow keys never send the caret into a group that is folded away")
    void theArrowKeysStayAmongTheDrawnRows() {
        UI ui = UiHarness.ui();
        ConversationSidebar sidebar = twoGroupColumn("web-1");
        ui.add(sidebar);
        foldGroup(sidebar, "Telegram");
        focused(ui);

        UiHarness.press(rowTitled(sidebar, "First"), "End");
        assertThat(focused(ui)).as("End with the second group folded").containsExactly("Second");

        // The last drawn row is the end of the list, whatever is filed below it out of sight.
        UiHarness.press(rowTitled(sidebar, "Second"), "ArrowDown");
        assertThat(focused(ui)).as("ArrowDown off the last drawn row").isEmpty();
    }

    /**
     * What the column does when the reader folds everything: it has no rows, and needs no row stop.
     *
     * <p>The decision, stated here because it is the one case where "exactly one row is in the tab
     * ring" cannot hold. A column with no drawn rows gets no row tab stop - inventing one would mean
     * either drawing a row the reader asked to be rid of, or putting a stop on a row that is not
     * there. It is not a trap, and that is the part worth pinning: every group heading is an ordinary
     * button with an ordinary tab stop, so the reader keeps as many ways back in as they folded away,
     * and pressing one returns its rows and its stop.
     */
    @Test
    @DisplayName("folding every group leaves the headings as the way back in")
    void foldingEveryGroupLeavesTheHeadingsAsTheWayBackIn() {
        ConversationSidebar sidebar = twoGroupColumn("web-1");

        foldGroup(sidebar, "Web");
        foldGroup(sidebar, "Telegram");

        assertThat(rows(sidebar)).isEmpty();
        assertThat(tabStops(sidebar)).isEmpty();
        // Not a dead end: the headings were never taken out of the tab ring, so the column still
        // costs a keyboard one stop per folded group and every one of them undoes the fold.
        assertThat(groupHeads(sidebar))
                .hasSize(2)
                .allSatisfy(head -> assertThat(head.getAttribute("tabindex"))
                        .as("a group heading must stay an ordinary tab stop")
                        .isNull());

        click(groupHead(sidebar, "Telegram"));

        assertThat(titles(sidebar)).containsExactly("Third", "Fourth");
        assertThat(tabStops(sidebar)).containsExactly("Third");
    }

    /**
     * A column of two groups, four conversations, and nothing folded.
     *
     * @param selected the id of the conversation the view below is showing
     * @return a fresh column; the caller folds what it needs folded
     */
    private static ConversationSidebar twoGroupColumn(String selected) {
        return new ConversationSidebar(
                new FakeDirectory(
                        inGroup("web-1", "First", "Web"),
                        inGroup("web-2", "Second", "Web"),
                        inGroup("tg-1", "Third", "Telegram"),
                        inGroup("tg-2", "Fourth", "Telegram")),
                selected, summary -> {
                }, () -> {
                });
    }

    /** A stored conversation filed under a named heading, so a test can produce more than one group. */
    private static ConversationSummary inGroup(String id, String title, String group) {
        return new ConversationSummary(id, title, title, group, List.of(), Optional.empty(),
                ConversationSummary.Source.STORED);
    }

    /** Folds a group the way a reader does, by pressing its heading. */
    private static void foldGroup(ConversationSidebar sidebar, String name) {
        click(groupHead(sidebar, name));
    }

    /**
     * @param name the heading's own text, which is the conversation's group
     * @return the heading button, which is what a reader presses to fold and unfold
     * @throws AssertionError when the column drew no such heading
     */
    private static Element groupHead(ConversationSidebar sidebar, String name) {
        return groupHeads(sidebar).stream()
                .filter(head -> descendants(head)
                        .filter(child -> child.getClassList().contains("jc-group__label"))
                        .anyMatch(label -> name.equals(label.getTextRecursively())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No group heading called " + name));
    }

    /** Every group heading, in the order the column draws them; empty when it drew no headings. */
    private static List<Element> groupHeads(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-group__head"))
                .toList();
    }

    /** What each heading says it is holding, which comes from the model rather than from the DOM. */
    private static List<String> groupCounts(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-group__count"))
                .map(Element::getTextRecursively)
                .toList();
    }

    /**
     * Rows the column drew inside a group it has marked as folded.
     *
     * <p>The stylesheet hides those with {@code display: none}, which nothing in a component tree can
     * see - so this is the only place a test can catch a row that exists on the server and not on the
     * screen. It must always be empty.
     *
     * @param sidebar the column
     * @return the fitted label of every row inside a folded group
     */
    private static List<String> rowsInsideFoldedGroups(ConversationSidebar sidebar) {
        return rows(sidebar).stream()
                .filter(ConversationSidebarTest::insideAFoldedGroup)
                .map(ConversationSidebarTest::titleOf)
                .toList();
    }

    /**
     * Walks up rather than checking the parent, because the row sits two levels below the element
     * that carries the fold - {@code .jc-group > .jc-group__items > .jc-conv} - and a test that
     * hard-codes the depth would stop catching the defect the first time the markup changed.
     *
     * @param row a conversation row
     * @return true when some ancestor of it is a folded group
     */
    private static boolean insideAFoldedGroup(Element row) {
        for (Element ancestor = row.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if ("true".equals(ancestor.getAttribute("data-collapsed"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rows a Tab would stop on, in the order the column draws them.
     *
     * @param sidebar the column
     * @return the fitted label of every row whose tabindex is not negative
     */
    private static List<String> tabStops(ConversationSidebar sidebar) {
        return rows(sidebar).stream()
                .filter(row -> row.getAttribute("tabindex") == null
                        || !row.getAttribute("tabindex").startsWith("-"))
                .map(ConversationSidebarTest::titleOf)
                .toList();
    }

    /** Every conversation row in the column, in the order it is drawn. */
    private static List<Element> rows(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-conv"))
                .toList();
    }

    /**
     * The rows the server has asked the browser to focus since this was last called.
     *
     * <p>Read off the pending script queue rather than off a field: a caret that never leaves the
     * server has not moved. Draining also clears the queue, so each call reports one keystroke.
     *
     * @param ui the UI whose queue to drain
     * @return the fitted label of each row focused, in order
     */
    private static List<String> focused(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(pending -> pending.getInvocation().getExpression().contains(".focus("))
                // One invocation, one caret. Vaadin passes the element twice - once as the script's
                // argument and once as the receiver it is applied to - so taking every parameter
                // would report each keystroke as two.
                .map(pending -> pending.getInvocation().getParameters().stream()
                        .filter(Element.class::isInstance)
                        .map(Element.class::cast)
                        .findFirst())
                .flatMap(Optional::stream)
                .filter(element -> element.getClassList().contains("jc-conv"))
                .map(ConversationSidebarTest::titleOf)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private static ConversationSummary stored(String id, String title) {
        return new ConversationSummary(id, title, title, "Web", List.of(), Optional.empty(),
                ConversationSummary.Source.STORED);
    }

    /** A conversation this session watched being used, which is the only kind that carries a time. */
    private static ConversationSummary dated(String id, String title, Instant when) {
        return new ConversationSummary(id, title, title, "Web", List.of(), Optional.of(when),
                ConversationSummary.Source.STORED);
    }

    private static List<String> seams(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-seam"))
                .map(Element::getTextRecursively)
                .toList();
    }

    /** The column as a sequence, with every marker in it, so position can be asserted and not just count. */
    private static List<String> rowsAndSeams(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-conv")
                        || element.getClassList().contains("jc-seam"))
                .map(element -> element.getClassList().contains("jc-seam")
                        ? "seam"
                        // The row's own title, not everything printed in it: a row also draws its
                        // time, and asserting on that would make this a test about the clock.
                        : descendants(element)
                                .filter(child -> child.getClassList().contains("jc-conv__title"))
                                .map(Element::getTextRecursively)
                                .findFirst()
                                .orElse(""))
                .toList();
    }

    /**
     * A stored conversation as the directory really builds one: titled by deriving a topic from the
     * opening message, so a test about titles is testing the derivation the application uses.
     */
    private static ConversationSummary asked(String id, String openingMessage) {
        return new ConversationSummary(id, ConversationTitles.topic(openingMessage), openingMessage,
                "Web", List.of(), Optional.empty(), ConversationSummary.Source.STORED);
    }

    /** The fitted label a row draws, which is the part a sighted reader sees. */
    private static String titleOf(Element row) {
        return descendants(row)
                .filter(child -> child.getClassList().contains("jc-conv__title"))
                .map(Element::getTextRecursively)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The row draws no title"));
    }

    /**
     * What assistive technology would read from a row, approximated the way the browser computes it.
     *
     * <p>Text from the element's own subtree, with anything marked {@code aria-hidden} left out - the
     * two rules that matter here. It is deliberately not "the text in the row": a truncated label
     * that is hidden from the accessibility tree is not announced, and a {@code title} attribute is a
     * description rather than a name, so neither belongs in the answer.
     *
     * @param row the conversation row
     * @return the text a screen reader would announce for it
     */
    private static String announcedText(Element row) {
        if ("true".equals(row.getAttribute("aria-hidden"))) {
            return "";
        }
        if (row.isTextNode()) {
            return row.getText();
        }
        StringBuilder spoken = new StringBuilder();
        row.getChildren().forEach(child -> spoken.append(announcedText(child)).append(' '));
        return spoken.toString();
    }

    private static List<String> childClasses(Element row) {
        return descendants(row).flatMap(child -> child.getClassList().stream()).toList();
    }

    private static TextField search(ConversationSidebar sidebar) {
        return (TextField) element(sidebar, "jc-search").getComponent().orElseThrow();
    }

    private static List<String> titles(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-conv__title"))
                .map(Element::getTextRecursively)
                .toList();
    }

    private static List<String> groupLabels(ConversationSidebar sidebar) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-group__label"))
                .map(Element::getTextRecursively)
                .toList();
    }

    private static Element rowTitled(ConversationSidebar sidebar, String title) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains("jc-conv"))
                .filter(element -> element.getTextRecursively().contains(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No conversation row titled " + title));
    }

    private static Element element(ConversationSidebar sidebar, String className) {
        return descendants(sidebar.getElement())
                .filter(element -> element.getClassList().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with class " + className));
    }

    private static void click(Element element) {
        Component component = element.getComponent().orElseThrow();
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
    }

    private static java.util.stream.Stream<Element> descendants(Element root) {
        List<Element> found = new ArrayList<>();
        collect(root, found);
        return found.stream();
    }

    private static void collect(Element element, List<Element> into) {
        element.getChildren().forEach(child -> {
            into.add(child);
            collect(child, into);
        });
    }

    /** A directory that answers from a list held in the test, with no store behind it. */
    private static final class FakeDirectory implements ConversationDirectory {

        private final List<ConversationSummary> rows = new ArrayList<>();

        private FakeDirectory(ConversationSummary... initial) {
            rows.addAll(List.of(initial));
        }

        void add(ConversationSummary row) {
            rows.add(row);
        }

        /** Stands in for a conversation acquiring a real name after the column was drawn. */
        void replace(ConversationSummary row) {
            rows.removeIf(existing -> existing.id().equals(row.id()));
            rows.add(row);
        }

        @Override
        public List<ConversationSummary> conversations() {
            return List.copyOf(rows);
        }

        @Override
        public Optional<ConversationSummary> find(String id) {
            return rows.stream().filter(row -> row.id().equals(id)).findFirst();
        }

        @Override
        public List<ConversationMessage> transcript(String id) {
            return List.of();
        }
    }
}
