package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.dom.Element;
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
