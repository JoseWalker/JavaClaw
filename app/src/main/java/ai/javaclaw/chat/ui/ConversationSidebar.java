package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.shared.Registration;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The navigation column: brand, new chat, search, the conversation list and the user menu.
 *
 * <p>Whether the column is visible, and whether it pushes the content or floats over it, is not
 * decided here - that is {@link MainLayout}'s job and, below it, the stylesheet's. This class only
 * decides what the column contains.
 *
 * <p>Controls are a deliberate mix. The two icon-only buttons are Vaadin {@code Button}s so they
 * inherit the {@code .jc-icon-button} treatment the header toggle already uses; everything with a
 * label - rows, group headings, the new-chat action - is a {@code NativeButton}, because those need
 * to be styled down to the text baseline and a plain button element has no shadow root in the way.
 *
 * <p>It is a {@code <nav>} rather than a div with a label on it. The label was already there and did
 * nothing: an unlabelled generic element with an {@code aria-label} is still a generic element, so
 * the column never appeared in the landmark list a screen reader user navigates a page by, and the
 * only way to reach a conversation was to tab through everything above it.
 *
 * <h2>What the list costs a keyboard</h2>
 *
 * <p>One stop, not one per conversation. The rows are a composite widget rather than a run of
 * unrelated controls, so exactly one of them is in the tab ring - the row the reader is in - and the
 * arrow keys move between rows from there. Measured before that was true, on the running application
 * with 115 stored conversations: 125 Tab presses to reach the composer, 114 of them rows, against 19
 * for the product this is measured against. The cost grew with the reader's own history, which is the
 * part that makes it a defect rather than a quantity: the more they had used the application, the
 * further away everything else in it became.
 */
class ConversationSidebar extends Nav {

    /**
     * How a date is written once a conversation is too old for a relative time.
     *
     * <p>Pinned to {@link Locale#ENGLISH} rather than the browser's locale, and not because the
     * browser's is unavailable. Every other string in this interface is written in English in the
     * source, so a month name resolved from the request would put a Spanish "mar" in a column of
     * otherwise English chrome - one translated word is a stronger claim about localisation than no
     * translation at all. See {@link DocumentLanguage} for the same decision about the document.
     */
    private static final DateTimeFormatter ABSOLUTE_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * Characters a row can show before the browser ellipsises the title.
     *
     * <p>Measured, not guessed, and re-measured after the first number cost the column width it had.
     * The title box is 231px of the 13px system stack, which fits 38 characters of a representative
     * label; 34 was one word short of the box on most rows, and because the cut lands at a word
     * boundary the shortfall was 31px of empty trailing edge on fifteen rows out of seventeen. 38
     * keeps the ordinary label inside its box, so a row is truncated once - here, at a word boundary
     * - rather than twice, the second time mid-word by CSS.
     *
     * <p>It is also the width at which two labels have to differ: past it they are the same string
     * with the same ellipsis, which is why {@link ConversationTitles#forColumn} is given this number
     * rather than an untruncated set.
     */
    private static final int TITLE_WIDTH = 38;

    /**
     * The shape of the counter {@link ConversationTitles#forColumn} puts in front of a repeated label.
     *
     * <p>Anchored at the start and bounded in the digits, so it can only ever match the prefix the
     * labelling pass writes and cannot be driven by the rest of a title. Six digits is more rows than
     * a column could draw, and matching a bounded run rather than {@code \d+} is what keeps this off
     * a title that happens to open with a bracketed number of its own.
     */
    private static final Pattern OCCURRENCE_MARKER = Pattern.compile("^\\(\\d{1,6}\\) ");

    /**
     * What the column says where the rows it can date end and the rows it cannot begin.
     *
     * <p>Ordering is by recency, and a conversation this process never watched being used has no
     * recency to order by - so those rows fall to the bottom, in a block, with the trailing edge of
     * every row suddenly empty. Two readers looked at that seam and misread it in two different
     * ways: as a list that had failed to finish loading, and as a claim that the conversations below
     * were the oldest. It is neither, and until it was marked the column had no way of saying so.
     *
     * <p>What it says is about the rows, not about the store. "No recorded time" named a field the
     * reader has no relationship with, and a third of a column filed under a missing database column
     * reads as a list that failed rather than as a list that is complete and sorted differently.
     * This states the order the rows below are actually in, which is a fact the reader can use and
     * can check against the rows themselves; why they are in it stays available on the seam.
     */
    private static final String SEAM_LABEL = "Ordered by name";

    /**
     * The whole of why, available on the seam itself for a reader who wants it.
     *
     * <p>It answers the two misreadings the label alone cannot: that the list is still loading, and
     * that these rows are the oldest. Both are denied explicitly rather than left to inference,
     * because the visible evidence - a block of rows with empty trailing edges - supports either.
     */
    private static final String SEAM_EXPLANATION =
            "This session has not seen the conversations below being used, so there is no recency "
                    + "to sort them by and they are ordered by name instead. They are no older and "
                    + "no less complete than the ones above.";

    /**
     * Where the rows come from, re-read on every render rather than cached here.
     *
     * <p>Transient because the directory is an application bean: writing it into a passivated
     * session would serialise the chat store's adapter along with it.
     */
    private final transient ConversationDirectory directory;

    /**
     * What to do when a row is chosen. The column does not navigate; it reports, and the shell that
     * owns the address decides - which is what keeps the URL and the transcript naming one thread.
     */
    private final transient Consumer<ConversationSummary> onSelect;

    /**
     * The filter box. Held as a field because {@link #focusSearch()} has to reach it from the shell
     * when the reader presses the search shortcut with the column shut.
     */
    private final TextField search = new TextField();

    /** The rows and their group headings, rebuilt whole on each render rather than patched. */
    private final Div list = new Div();

    /** The control that shuts the column from inside it, which is the drawer's own exit on a phone. */
    private final Button collapse = new Button();

    /**
     * The foot of the column. Constructed here rather than in {@link #createFoot()} so that the
     * component exists before any render runs and there is exactly one of it per column.
     */
    private final UserMenu userMenu = new UserMenu();

    /** Groups the reader has folded away. Held by name, so the state survives a re-render. */
    private final Set<String> collapsedGroups = new LinkedHashSet<>();

    /**
     * The rows of the current render, in the order they are drawn.
     *
     * <p>Kept because the arrow keys need a sequence and the DOM is a tree: a row's neighbour is not
     * its sibling once the column is grouped, since the last row of one folder and the first of the
     * next sit under different parents. This is the order the reader sees, which is the order the
     * caret has to move in.
     *
     * <p>Rebuilt from nothing on every render rather than patched, for the same reason the list is:
     * a second copy of the model that is updated in place is a second copy that drifts.
     *
     * <p><strong>Its one invariant: this holds exactly the rows that are on screen.</strong> Every
     * keyboard decision in this class is taken from it - which row carries the tab stop, where an
     * arrow key goes, what End means - and each of those is wrong for a row the reader cannot see.
     * It is kept true by {@link #addRow}, which is the only thing that appends here and does so in
     * the same statement that puts the row in the tree, and by {@link #group}, which draws no rows
     * at all for a folder that is folded. See {@link #ensureOneTabStop()} for what it cost when the
     * two could disagree.
     *
     * <p>Deliberately not transient, unlike the collaborators above it. It holds components, which
     * are serializable, and a session that is passivated and restored would otherwise come back with
     * this null and throw on the first keystroke rather than on the first render, where it would at
     * least be visible.
     */
    private final List<NativeButton> renderedRows = new ArrayList<>();

    /**
     * How many seams this render has drawn, which is what their descriptions are identified by.
     *
     * <p>A position in the current render rather than anything derived from the rows, for the same
     * reason the group ids are: store data has no business becoming a DOM id. Reset at the top of
     * every render, so the ids do not climb for the lifetime of the session.
     */
    private int seamsDrawn;

    /**
     * The row the reader is in. Initialised from the conversation the view below is talking to, not
     * from a click: a list where nothing is marked until something is clicked leaves the one piece
     * of state this column exists to carry unsaid on every first paint.
     */
    private String selectedId;

    /**
     * A chat that has been started but has nothing stored behind it yet - the live conversation
     * before its first message, or one opened from the "new chat" action.
     *
     * <p>It is session-local and never written anywhere: creating a conversation means writing to
     * the chat store, which belongs to the view that owns the messages, not to the chrome. The row
     * exists so the reader's place is marked even before the store has heard of it, and it is
     * dropped the moment the store can answer for the same id.
     */
    private ConversationSummary draft;

    /** Live only while this column is on screen; see {@link #onAttach}. */
    private transient Registration directoryChanges;

    /**
     * Told what this column has decided to call the open conversation, or null when nobody asked.
     *
     * <p>Set after construction rather than taken as a fifth constructor argument because the shell
     * that wants it cannot pass a method reference to itself until its own fields exist.
     */
    private transient Consumer<ConversationSummary> onActiveNamed;

    /**
     * Builds the column and draws its first render.
     *
     * <p>The active conversation is looked up immediately, and its absence is not an error: a chat
     * the reader has just started has been written nowhere, so the store has never heard of it. The
     * column stands a draft row in for it rather than showing an empty list, because a reader who
     * has just been given a new chat and cannot see it in the list concludes it was not created.
     *
     * <p>Renders once here rather than waiting for attach, so the column is populated in the same
     * round trip that creates it; the subscription that keeps it up to date is taken on attach,
     * where it can also be released.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param directory where the rows come from, never null
     * @param activeConversationId the conversation the shell is currently on; need not be in the
     *     store, in which case a draft row is stood in for it
     * @param onSelect told which conversation the reader chose; the column never navigates itself
     * @param onCollapse invoked by the column's own close control, so the shell owns the state
     */
    ConversationSidebar(ConversationDirectory directory,
                        String activeConversationId,
                        Consumer<ConversationSummary> onSelect,
                        Runnable onCollapse) {
        this.directory = directory;
        this.onSelect = onSelect;
        this.selectedId = activeConversationId;
        if (directory.find(activeConversationId).isEmpty()) {
            draft = unsavedConversation(activeConversationId);
        }

        addClassName("jc-sidebar");
        getElement().setAttribute("aria-label", "Conversations");

        add(createHead(onCollapse), createTools(), createScroller(), createFoot());
        renderList();
    }

    /**
     * The placeholder row for a chat the store has never heard of.
     *
     * <p>No time. A stored row's time says when the conversation was last written to, and this one
     * has not been written to at all - reporting {@code now} was the row claiming a storage event
     * that had not happened. What marks it instead is {@code data-source="draft"} and the word the
     * row draws where a stored row draws its time, which is a difference a reader can see.
     *
     * @param id the conversation id the reader is currently in
     * @return a summary marked as a draft, carrying no time at all rather than a fabricated one
     */
    private static ConversationSummary unsavedConversation(String id) {
        return new ConversationSummary(id, "New chat", "Nothing sent yet.", "Drafts", List.of(),
                Optional.empty(), ConversationSummary.Source.DRAFT);
    }

    /**
     * Re-reads the directory, keeping the reader's place, their search and their folded groups.
     *
     * <p>Called when the conversation below has changed - a first exchange written, an answer
     * finished - so the column reflects the store rather than whatever it read when the page loaded.
     */
    void refresh() {
        renderList();
    }

    /**
     * Asks to be told what this column is calling the conversation the reader is in.
     *
     * <p>A name is not settled when the column is drawn. It starts as a reduction of the opening
     * question, and is replaced seconds later by one the model was asked for, on a thread of its
     * own. Anything else on screen that names the same conversation has to hear about that, or the
     * interface ends up asserting two names for one thread at the same moment - and a reader
     * scanning the column for the name in the header will not find it.
     *
     * <p>Reported from the column rather than re-read from the directory by the caller, so the two
     * cannot disagree: whatever is drawn in the marked row is what is handed over, in the same
     * redraw, already on the UI thread.
     *
     * @param listener called with the open conversation as this column sees it; replaces any previous
     */
    void whenActiveNamed(Consumer<ConversationSummary> listener) {
        onActiveNamed = listener;
    }

    /**
     * Listens for the directory learning something after this column was drawn.
     *
     * <p>A conversation's real name arrives seconds after the first paint, on a naming thread, and
     * without this the reader would go on looking at the derived title until their next click. The
     * push connection is what makes the redraw reach the browser; {@code ui.access} is what puts it
     * back on the session lock, because the notification arrives on whichever thread named it.
     */
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        directoryChanges = directory.onChanged(() -> redrawOn(ui));
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // The directory outlives this column by design - it is application scoped - so a listener
        // left behind would hold a detached sidebar for the life of the process.
        if (directoryChanges != null) {
            directoryChanges.remove();
            directoryChanges = null;
        }
        super.onDetach(detachEvent);
    }

    /**
     * Redraws on the UI's own thread, and stays quiet when there is no longer a UI to redraw.
     *
     * <p>A browser tab closed between the naming call starting and finishing is the ordinary case,
     * not an error: nothing is lost, because the name is recorded whether or not anyone was looking.
     *
     * @param ui the UI to redraw on, captured while the column was attached
     */
    private void redrawOn(UI ui) {
        try {
            ui.access(this::renderList);
        } catch (UIDetachedException e) {
            if (directoryChanges != null) {
                directoryChanges.remove();
                directoryChanges = null;
            }
        }
    }

    // ------------------------------------------------------------------ structure

    /**
     * The top of the column: the wordmark and the control that shuts it.
     *
     * <p>The close control is wired to the shell's callback rather than to anything here, because
     * the column does not own whether it is showing - the shell does, and it stores the choice
     * against the current arrangement. A column that hid itself would leave the shell's toggle
     * announcing the wrong state.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param onCollapse what the close control invokes; supplied by the shell
     * @return the head, already classed and wired; called once, from the constructor
     */
    private Component createHead(Runnable onCollapse) {
        Span mark = new Span("JC");
        mark.addClassNames("jc-mark", "jc-mark--s");
        mark.getElement().setAttribute("aria-hidden", "true");

        Span name = new Span("JavaClaw");
        name.addClassName("jc-brand__name");

        Div lockup = new Div(mark, name);
        lockup.addClassName("jc-sidebar__brand");

        collapse.setIcon(Icons.glyph(Icons.PANEL));
        collapse.addClassNames("jc-icon-button", "jc-sidebar__collapse");
        collapse.setDisableOnClick(false);
        collapse.setAriaLabel("Hide the conversation list");
        collapse.setTooltipText(Shortcut.TOGGLE_SIDEBAR.tooltip("Hide conversations"));
        Shortcut.TOGGLE_SIDEBAR.announceOn(collapse.getElement());
        collapse.addClickListener(event -> onCollapse.run());

        Div head = new Div(lockup, collapse);
        head.addClassName("jc-sidebar__head");
        return head;
    }

    /**
     * The two controls above the list: start a new chat, and filter the ones that exist.
     *
     * <p>Both carry their keystroke twice - as a tooltip a sighted reader sees and as
     * {@code aria-keyshortcuts} a screen reader announces - because a shortcut nobody can find is a
     * trap rather than a feature, and the help dialog only reaches readers who open it.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @return the tool strip, already classed and wired; called once, from the constructor
     */
    private Component createTools() {
        NativeButton newChat = new NativeButton();
        newChat.addClassName("jc-new-chat");
        newChat.getElement().appendChild(Icons.glyph(Icons.NEW_CHAT).getElement());
        Span label = new Span("New chat");
        newChat.getElement().appendChild(label.getElement());
        newChat.setTitle(Shortcut.NEW_CHAT.tooltip("Start a new chat"));
        Shortcut.NEW_CHAT.announceOn(newChat.getElement());
        newChat.addClickListener(event -> startNewChat());

        search.setPlaceholder("Search chats");
        search.setAriaLabel("Search conversations");
        search.setClearButtonVisible(true);
        search.addClassName("jc-search");
        search.setPrefixComponent(Icons.glyph(Icons.SEARCH));
        search.setTitle(Shortcut.SEARCH.tooltip("Search conversations"));
        Shortcut.SEARCH.announceOn(search.getElement());
        // Filtering has to track the keystroke, not the blur: a search box that only answers when
        // focus leaves it reads as broken long before the reader works out why.
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> renderList());

        Div tools = new Div(newChat, search);
        tools.addClassName("jc-sidebar__tools");
        return tools;
    }

    /**
     * Wraps the row list in the box that scrolls.
     *
     * <p>Two elements rather than one because the list has to be emptied and refilled on every
     * render, and the scroll position belongs to the box, not to its contents: scrolling the element
     * that gets {@code removeAll()} would jump the reader back to the top on every keystroke in the
     * search box.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @return the scroll box containing the list; called once, from the constructor
     */
    private Component createScroller() {
        list.addClassName("jc-sidebar__list");
        // Deliberately no role. It used to declare itself a list, and its items were the group
        // headings and the rows - which are buttons. role="listitem" does not describe a button, it
        // replaces it, so every conversation was announced as a line of text the reader was never
        // told they could press. What the column is is said once, by the nav landmark above.

        Div scroller = new Div(list);
        scroller.addClassName("jc-sidebar__scroll");
        return scroller;
    }

    /**
     * The bottom of the column, which is the session menu and nothing else.
     *
     * <p>A wrapper for one child looks redundant and is not: the foot is pinned below the scroll box
     * by the stylesheet, so it has to be a sibling of the scroller rather than a child of it - the
     * menu placed directly would scroll away with the rows.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @return the foot; called once, from the constructor
     */
    private Component createFoot() {
        Div foot = new Div(userMenu);
        foot.addClassName("jc-sidebar__foot");
        return foot;
    }

    // ------------------------------------------------------------------ list

    /**
     * Rebuilds the list from the directory, the search box and the fold state.
     *
     * <p>Rebuilding wholesale rather than patching rows is the right trade here: the list is tens of
     * items, not thousands, and every alternative means keeping a second copy of the model in the
     * DOM and hoping the two stay in step.
     */
    private void renderList() {
        list.removeAll();
        renderedRows.clear();
        seamsDrawn = 0;

        List<ConversationSummary> stored = directory.conversations();
        // Once the store can answer for the same id, the placeholder has done its job: leaving it
        // would print the reader's own conversation twice, under two different titles.
        if (draft != null && stored.stream().anyMatch(row -> row.id().equals(draft.id()))) {
            draft = null;
        }

        // Before the search box is consulted, deliberately: a query that hides the marked row does
        // not change what the open conversation is called, and reporting nothing there would blank
        // the name in the bar for as long as the reader was typing.
        reportActiveName(stored);

        String query = Optional.ofNullable(search.getValue()).orElse("");
        List<ConversationSummary> rows = new ArrayList<>();
        if (draft != null && draft.matches(query)) {
            rows.add(draft);
        }
        stored.stream().filter(row -> row.matches(query)).forEach(rows::add);

        if (rows.isEmpty()) {
            list.add(query.isBlank() ? noConversations() : noMatches(query));
            return;
        }

        // Labels are decided over the whole visible set, before it is split into groups. Two rows
        // filed under different headings are still two rows on one screen, so a collision between
        // them is as unreadable as one inside a group - and the set that is visible depends on the
        // search box, which is why this cannot be settled once when the column is built.
        Map<String, String> labels = ConversationTitles.forColumn(rows, TITLE_WIDTH);

        // The draft belongs to no folder; it sits above the headings where a reader looks for the
        // thing they just created rather than hunting for it inside a group.
        if (draft != null && rows.contains(draft)) {
            rows.remove(draft);
            addRow(list, draft, labels);
        }

        Map<String, List<ConversationSummary>> groups = new LinkedHashMap<>();
        for (ConversationSummary row : rows) {
            groups.computeIfAbsent(row.group(), key -> new ArrayList<>()).add(row);
        }

        // A heading over the entire list separates nothing from nothing. On the ordinary install
        // every conversation arrived on the same channel, and the column spent a row of its height,
        // a fold control and a count saying so. The headings are worth their space the moment they
        // tell two kinds of conversation apart - a chat from Telegram against one from this window -
        // and they appear then.
        if (groups.size() < 2) {
            addRows(list, rows, labels);
        } else {
            // A search is a request to see matches, not to remember which drawers were shut.
            boolean forceOpen = !query.isBlank();
            // The index is what the two halves of a disclosure are tied together with. It is a
            // position in this render rather than anything derived from the heading, because a
            // heading is store data and store data has no business becoming a DOM id.
            int index = 0;
            for (Map.Entry<String, List<ConversationSummary>> entry : groups.entrySet()) {
                list.add(group(entry.getKey(), entry.getValue(), forceOpen, labels, index++));
            }
        }
        ensureOneTabStop();
    }

    /**
     * Guarantees the column has exactly one way in from the keyboard.
     *
     * <p>The stop belongs to the row the reader is in, which is the rule that makes tabbing back into
     * the column return them where they were. It is not a rule that can stand alone: a search can
     * hide the marked row, and so can a conversation being deleted underneath the reader, and with
     * only that rule every remaining row would be at -1 and the whole column unreachable without a
     * pointer. That is a worse failure than the 114 tab stops this replaces, and a silent one - there
     * is nothing on screen to say the list has gone.
     *
     * <p>The first row rather than the nearest one, because with the marked row gone there is no
     * nearest: what the reader is looking at is the top of a list that has just changed under them.
     *
     * <h4>Why this asks about rendered rows and not about visible ones</h4>
     *
     * <p>It cannot ask about visible ones. Whether a row is on screen was decided by
     * {@code .jc-group[data-collapsed="true"] .jc-group__items {display: none}} in a stylesheet, and
     * nothing in a component tree can read that. For a while the column drew the rows of a folded
     * group anyway and let the stylesheet hide them, and this method - which asks only whether some
     * row carries a non-negative tabindex - was then satisfied by a row nobody could reach. Fold the
     * group holding the conversation you have open, a single click, and the whole list left the
     * keyboard: the marked row kept the only tab stop and it was invisible. That is precisely the
     * outcome the paragraph above calls worse than 114 tab stops, arrived at by a different route.
     *
     * <p>The answer was not a better test here. A visibility test written in Java would be a second
     * opinion about what the stylesheet did, and the two would drift the first time either changed.
     * So the disagreement is removed instead: a folded group contributes no rows to the tree, which
     * makes {@link #renderedRows} and what is on screen the same set by construction, and leaves this
     * method asking the only question it was ever able to answer.
     *
     * <p>Nothing is done for a column that drew no rows - every group folded, or a search that
     * matched nothing. There is no row to carry a stop and inventing one would mean drawing a row the
     * reader asked to be rid of. It is not a trap: group headings are ordinary buttons with ordinary
     * tab stops, so a folded column still costs a keyboard one stop per folder and every one of them
     * gives its rows back.
     *
     * <p>Must run on the Vaadin UI thread; it writes to components in the tree.
     */
    private void ensureOneTabStop() {
        if (renderedRows.isEmpty()) {
            return;
        }
        if (renderedRows.stream().anyMatch(row -> row.getTabIndex() >= 0)) {
            return;
        }
        renderedRows.getFirst().setTabIndex(0);
    }

    /**
     * Moves the caret one row along, and takes the tab stop with it.
     *
     * <p>The stop follows the caret so that leaving the column and coming back returns the reader to
     * the row they were actually on rather than to the row that happened to be selected when the
     * column was last drawn. The next render puts the stop back on the marked row, which is right:
     * by then the reader has either chosen a row or gone somewhere else entirely.
     *
     * <p>Deliberately does not wrap. A list that loops has no way of telling a reader they have
     * reached the end of their own history, and this one is several screens long.
     *
     * <p>Deliberately does not select, either. Arrowing is looking; Enter and Space are choosing, and
     * a row is a button, so the browser already answers both without anything here.
     *
     * <p>Every row it can reach is a row on screen, because {@link #renderedRows} holds only those.
     * That is load-bearing rather than incidental: this drops the previous row's tab stop before it
     * asks for the new one, so a target the browser refuses to focus - which is what a hidden element
     * is - would leave the column with no focus and no tab stop at all, from one press of End.
     *
     * <p>Must run on the Vaadin UI thread; Vaadin only delivers DOM events there.
     *
     * @param from the position of the row the keystroke came from
     * @param step  -1 for up, 1 for down
     */
    private void moveCaret(int from, int step) {
        int to = from + step;
        if (to < 0 || to >= renderedRows.size()) {
            return;
        }
        NativeButton target = renderedRows.get(to);
        renderedRows.forEach(row -> row.setTabIndex(-1));
        target.setTabIndex(0);
        target.focus();
    }

    /**
     * Answers the keys a list of rows is expected to answer.
     *
     * <p>The other half of the roving stop, and without it the stop is a regression: one way into a
     * list the reader then has no way of moving through. Registered per row rather than once on the
     * scroller because a keystroke has to be answered relative to where it came from, and the DOM
     * event Vaadin hands back names the listener's element rather than the row the caret was on.
     *
     * <p>The default is prevented on all four: every one of them scrolls the column on its own, and a
     * caret that moves one row while the list moves a screen is worse than either alone.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param row      the row being built
     * @param position where it sits in the current render
     */
    private void answerArrowKeys(NativeButton row, int position) {
        onKey(row, "ArrowDown", () -> moveCaret(position, 1));
        onKey(row, "ArrowUp", () -> moveCaret(position, -1));
        onKey(row, "Home", () -> moveCaret(position, -position));
        onKey(row, "End", () -> moveCaret(position, renderedRows.size() - 1 - position));
    }

    /**
     * One keystroke, filtered in the browser so a key this does not answer costs no round trip.
     *
     * @param row    the row to listen on
     * @param key    the {@code KeyboardEvent.key} value to answer
     * @param action what to do when it arrives
     */
    private static void onKey(NativeButton row, String key, Runnable action) {
        row.getElement().addEventListener("keydown", event -> action.run())
                .setFilter("event.key === '" + key + "'")
                .preventDefault();
    }

    /**
     * Hands the open conversation, as this column now sees it, to whoever asked for it.
     *
     * <p>The draft wins over the store for the same id it always does: while a chat is being written
     * for the first time the placeholder row is the only one that exists, and the row drawn is the
     * row reported. A selected id that matches nothing at all - a conversation deleted underneath
     * the reader - reports nothing rather than reporting the wrong row.
     *
     * @param stored the directory's answer for this render
     */
    private void reportActiveName(List<ConversationSummary> stored) {
        if (onActiveNamed == null) {
            return;
        }
        if (draft != null && draft.id().equals(selectedId)) {
            onActiveNamed.accept(draft);
            return;
        }
        stored.stream()
                .filter(row -> row.id().equals(selectedId))
                .findFirst()
                .ifPresent(onActiveNamed);
    }

    /**
     * What the column says when the filter matched nothing.
     *
     * <p>Quotes the query back, and quotes it stripped. Showing the reader what was actually
     * searched for is what turns "nothing here" into something they can act on - a stray trailing
     * space or a word they did not mean to type is invisible in the box and obvious in the message.
     *
     * <p>The query is set as text, never as markup, so a search for {@code <b>} shows those
     * characters.
     *
     * @param query the raw contents of the search box, never null
     * @return the empty-state note; a fresh component, since one belongs to one place in one DOM
     */
    private Component noMatches(String query) {
        return emptyNote("No chats match", "Nothing here matches “" + query.strip() + "”.");
    }

    /**
     * What the column says when there is genuinely nothing yet.
     *
     * <p>Distinct from a search that found nothing, because the two are different problems and the
     * reader can only act on one of them. Telling someone with an empty store that their query
     * matched nothing invites them to go on clearing a box that was never the reason.
     *
     * @return the empty-state note; a fresh component each call
     */
    private Component noConversations() {
        return emptyNote("No chats yet", "Ask something and this is where it will be kept.");
    }

    /**
     * The shared shape of an empty state: a heading and a line under it.
     *
     * <p>Shared so the two cases that use it are visually identical and differ only in what they
     * say. They are genuinely different problems - an empty store and a query that matched nothing -
     * and the reader can act on only one of them, so the words differ; but a reader who has seen one
     * should recognise the other immediately, and two hand-built layouts would drift.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param heading the short line, set as text and never as markup
     * @param detail the sentence under it, likewise
     * @return a fresh component each call
     */
    private Component emptyNote(String heading, String detail) {
        Span title = new Span(heading);
        title.addClassName("jc-sidebar__empty-title");

        Span hint = new Span(detail);
        hint.addClassName("jc-sidebar__empty-hint");

        Div empty = new Div(title, hint);
        empty.addClassName("jc-sidebar__empty");
        return empty;
    }

    /**
     * One folder of conversations, as a disclosure.
     *
     * <p>The heading and the rows under it are joined by id in both directions -
     * {@code aria-controls} down, {@code aria-labelledby} up - because {@code aria-expanded} on its
     * own says a control expands something without ever saying what. A reader who folded a group
     * away was told "collapsed" and left to work out which of the four headings had just swallowed
     * the row they were looking for.
     *
     * <h4>A folded group is empty, not hidden</h4>
     *
     * <p>The rows used to be built regardless and hidden by the stylesheet, so a folded group left
     * conversations in the tab ring and in the arrow-key sequence that no reader could see - and the
     * server had no way of telling, because {@code display: none} is not something a component tree
     * can read. Folding the group you had a conversation open in took the entire list off the
     * keyboard in one click; {@link #ensureOneTabStop()} has the whole account.
     *
     * <p>So the decision is taken once, here, where it is already being taken for the chevron and for
     * {@code aria-expanded}, and the rows are simply not built. Two things follow. The keyboard model
     * is exact rather than approximately right: {@link #renderedRows} is what is on screen, so no
     * separate rule about visibility has to be written or kept in step. And the stylesheet can no
     * longer disagree with the server about which conversations exist - if this were ever regressed,
     * the rows would appear in a folded group, which is a defect a reader reports in a second, rather
     * than silently leave the column unreachable from the keyboard.
     *
     * <p>The count on the heading still comes from {@code items}, not from what was drawn: a folded
     * group must go on saying how much it is holding.
     *
     * <p>Costs nothing to reopen. {@link #toggleGroup} re-renders the whole column anyway, which is
     * what it did when the rows were merely being hidden.
     *
     * @param index this group's position in the current render, which is what the ids are built
     *              from; see {@link #renderList()} for why they are not built from the name
     *
     * @param name the group heading, which is also what {@link #collapsedGroups} records
     * @param items every conversation in the group, drawn or not - the count on the heading comes
     *     from this rather than from what was built
     * @param forceOpen true when a filter is active, since a folded group would hide matches
     * @param labels the column-wide labels, so a row's text is decided once for the whole render
     *     rather than per group
     * @return the group as a disclosure; its rows are absent, not hidden, when it is folded
     */
    private Component group(String name, List<ConversationSummary> items, boolean forceOpen,
                            Map<String, String> labels, int index) {
        boolean collapsed = !forceOpen && collapsedGroups.contains(name);
        String headId = "jc-group-" + index + "-head";
        String itemsId = "jc-group-" + index + "-items";

        Span chevron = Icons.glyph(Icons.CHEVRON_DOWN);
        chevron.addClassName("jc-group__chevron");

        Span label = new Span(name);
        label.addClassName("jc-group__label");

        Span count = new Span(String.valueOf(items.size()));
        count.addClassName("jc-group__count");

        NativeButton head = new NativeButton();
        head.addClassName("jc-group__head");
        // Name first, controls after. A leading chevron pushed the folder's own name 13px inboard of
        // the conversations filed under it, which is the one place in this column a stagger cannot
        // mean anything: a parent indented past its children.
        head.getElement().appendChild(label.getElement(), count.getElement(), chevron.getElement());
        head.getElement().setAttribute("aria-expanded", String.valueOf(!collapsed));
        head.setId(headId);
        head.getElement().setAttribute("aria-controls", itemsId);
        head.addClickListener(event -> toggleGroup(name));

        Div body = new Div();
        body.addClassName("jc-group__items");
        body.setId(itemsId);
        // A group, not a list: what is inside are buttons, and a role that turned them into list
        // items would take the press away from every one of them.
        body.getElement().setAttribute("role", "group");
        body.getElement().setAttribute("aria-labelledby", headId);
        if (!collapsed) {
            addRows(body, items, labels);
        }

        Div group = new Div(head, body);
        group.addClassName("jc-group");
        group.getElement().setAttribute("data-collapsed", String.valueOf(collapsed));
        return group;
    }

    /**
     * Folds a group away or unfolds it, then redraws the column.
     *
     * <p>Held by group name rather than by index, which is what makes the fold survive a render: the
     * list is rebuilt whole on every change, so a positional record would move under the reader as
     * conversations are added or filtered.
     *
     * <p>The full redraw is deliberate rather than lazy. A folded group is genuinely empty - its
     * rows leave the document instead of being hidden - so there is nothing to toggle a class on,
     * and that emptiness is what keeps folded rows out of the tab order and out of the accessibility
     * tree, which hiding them with CSS would not.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param name the group heading, as drawn
     */
    private void toggleGroup(String name) {
        if (!collapsedGroups.remove(name)) {
            collapsedGroups.add(name);
        }
        renderList();
    }

    /**
     * Draws a run of rows, and marks the point where the column stops knowing when.
     *
     * <p>The seam is drawn from the rows themselves rather than from a flag passed in, because it is
     * a property of the sequence: it belongs above the first row that has no time. That is the same
     * place inside a group as it is in an ungrouped list.
     *
     * <p>It used to require a dated row above it, which meant it was absent from the one column that
     * most needs it. Times are held in this process, so the first render after a restart is a list
     * where <em>no</em> row has one - and several rows carry the same derived title told apart only
     * by a leading ordinal, because names are held in this process too. A reader looking at that
     * has every reason to think the column failed to load. The marker exists to say the list is
     * complete and ordered by name; a rule that hides it whenever the whole list is in that state
     * is a rule that withholds the explanation from everyone who needs it and shows it only to
     * readers who could already see half a column of times.
     *
     * <p>A draft is not part of the seam either way. It carries a badge rather than a time, and it
     * is deliberately above the rest of the list, so counting it would put the marker in front of
     * the first stored row on every empty install.
     *
     * <p>Reached only for a run of rows the column is actually drawing - {@link #group} does not call
     * this for a folder that is folded - so the seam is not drawn for rows nobody can see either, and
     * {@link #seamsDrawn} counts markers rather than groups.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param target where the rows and the marker go
     * @param items  the conversations to draw, in the order they are to appear; may be empty, which
     *               draws nothing at all rather than an empty marker
     * @param labels the column's decided labels, passed through to each row
     */
    private void addRows(Div target, List<ConversationSummary> items, Map<String, String> labels) {
        boolean seamDrawn = false;
        for (ConversationSummary item : items) {
            if (item.source() != ConversationSummary.Source.DRAFT
                    && item.updatedAt().isEmpty() && !seamDrawn) {
                target.add(seam(seamsDrawn++));
                seamDrawn = true;
            }
            addRow(target, item, labels);
        }
    }

    /**
     * The marker itself: a rule, a label, and the whole explanation for whoever wants it.
     *
     * <p>The name is the two words on screen and the explanation is a description hanging off it.
     * They used to be the same string, which meant a hundred and ninety-nine characters of prose
     * were read out in full every time a reader crossed this element while navigating - and
     * navigating past things is exactly what element navigation is for. A name answers "what is
     * this", and it should be short enough to be worth hearing on the way past; a description
     * answers "why", and assistive technology is entitled to skip it or to read it on request.
     *
     * @param index this seam's position in the current render, which the description's id is built
     *              from - a grouped column can draw one per group, and two elements cannot share an
     *              id without the reference resolving to whichever came first
     *
     * @return the marker; a fresh component each call
     */
    private static Component seam(int index) {
        Span label = new Span(SEAM_LABEL);
        label.addClassName("jc-seam__label");

        String explanationId = "jc-seam-" + index + "-why";
        Span explanation = new Span(SEAM_EXPLANATION);
        // Off screen rather than absent: the sighted reader has the tooltip and does not need this
        // sentence occupying a column that is short of width for conversation titles.
        explanation.addClassName("jc-sr-only");
        explanation.setId(explanationId);

        Div seam = new Div(label, explanation);
        seam.addClassName("jc-seam");
        // A separator rather than a heading: the rows below it are not a category, they are the
        // same conversations with one fact missing, and calling it a heading would file them under
        // something the store never said.
        seam.getElement().setAttribute("role", "separator");
        seam.getElement().setAttribute("aria-label", SEAM_LABEL);
        seam.getElement().setAttribute("aria-describedby", explanationId);
        seam.setTitle(SEAM_EXPLANATION);
        return seam;
    }

    /**
     * One conversation, as a row, put on screen and into the keyboard model in the same act.
     *
     * <p>It builds the row and adds it rather than returning it, and that is the point of the shape
     * rather than a convenience. Registering a row in {@link #renderedRows} is what puts it in the
     * tab ring and in the arrow-key sequence, so a row registered and not added is a conversation the
     * keyboard can reach and the reader cannot - which is the defect this column had, arrived at by
     * leaving the rows of a folded group in the tree for the stylesheet to hide. Returning the
     * component let the two halves be written apart and drift; joined here they cannot, and the
     * invariant on {@link #renderedRows} holds without anyone having to remember it.
     *
     * <p>Callers therefore decide whether a row is drawn <em>before</em> calling, never after.
     *
     * <p>Must run on the Vaadin UI thread; it writes to the tree.
     *
     * @param target  the container the row belongs in - the list itself for the draft, a group's body
     *                for everything else; must already be part of this render's tree
     * @param summary the conversation
     * @param labels  the column's decided labels; a missing one falls back to the row's own title,
     *                so a row is never drawn nameless because the labelling disagreed about the set
     */
    private void addRow(Div target, ConversationSummary summary, Map<String, String> labels) {
        boolean selected = summary.id().equals(selectedId);

        String label = labels.getOrDefault(summary.id(),
                ConversationTitles.shorten(summary.title(), TITLE_WIDTH));
        Span title = new Span(label);
        title.addClassName("jc-conv__title");

        // No icon in front of the title. It was the same speech bubble on every row, so it told the
        // reader nothing about which conversation they were looking at, and it took twenty-four
        // pixels out of a two-hundred-and-eighty pixel column where the titles were already being
        // cut off mid-word. Which row is the current one is said by the accent rail and the tinted
        // ground, neither of which costs the title any width.
        NativeButton row = new NativeButton();
        row.addClassName("jc-conv");
        // One stop for the whole column, on the row the reader is in, and the arrow keys to move
        // between rows - the composite-widget pattern a long list of same-kind controls is meant to
        // use. Measured before this, on the running application with 115 stored conversations: 125
        // Tab presses to reach the composer, 114 of them conversation rows, against 19 for the
        // product this is measured against. The cost grew with the reader's own history, so the more
        // they had used the application the further away everything else was.
        // The position this row is about to take. Read before the row joins the model so that the
        // model and the tree can be joined in one statement at the bottom of this method.
        int position = renderedRows.size();
        row.setTabIndex(selected ? 0 : -1);
        answerArrowKeys(row, position);
        row.getElement().appendChild(title.getElement());
        announceWholeTitle(row, title, summary);
        // A row says one thing on its trailing edge, and which one it says is the difference between
        // a conversation the store holds and one it has never heard of. A draft that also showed a
        // time would be indistinguishable from a chat saved a moment ago.
        if (summary.source() == ConversationSummary.Source.DRAFT) {
            Span badge = new Span("Draft");
            badge.addClassName("jc-conv__badge");
            row.getElement().appendChild(badge.getElement());
        } else {
            relativeTime(summary.updatedAt()).ifPresent(text -> {
                Span time = new Span(text);
                time.addClassName("jc-conv__time");
                row.getElement().appendChild(time.getElement());
            });
        }

        row.getElement().setAttribute("data-source", summary.source().name().toLowerCase(Locale.ROOT));
        // aria-current marks the one row that is the reader's current place, which is exactly what
        // the accent rail says visually. Selection here is not a form control, so it is not checked.
        row.getElement().setAttribute("aria-current", String.valueOf(selected));
        row.setTitle(tooltip(summary));
        row.addClickListener(event -> select(summary));

        // On screen and in the keyboard model, together, as the last thing that happens. Neither is
        // reachable without the other, which is the whole invariant this method exists to keep.
        target.add(row);
        renderedRows.add(row);
    }

    /**
     * Gives the row the whole title as its name, when the column could only draw part of one.
     *
     * <p>A conversation row is a control whose entire purpose is to say which conversation it opens,
     * and it was saying whatever survived a 231px box: an audit heard "…s 8 to 25" read out for
     * "Java Features Versions 8 to 25". The rest of the name was in the {@code title} attribute,
     * which is a tooltip - a pointer affordance, and a <em>description</em> rather than a name, so
     * assistive technology is entitled to skip it and a keyboard reader could not reach it at all.
     *
     * <p>The whole title therefore goes into the row off-screen, and the fitted label is taken out of
     * the accessibility tree so the two are not read one after the other. {@code aria-label} was the
     * obvious alternative and is the wrong one: it replaces everything in the control, so the row's
     * trailing edge - the relative time, or the word "Draft" - would stop being announced, and a
     * name that has to be rebuilt from three sources is a name that drifts from what is drawn.
     *
     * <p>Nothing is added when the label already is the whole title. A row the column draws in full
     * would otherwise be read out twice.
     *
     * <h4>Why the occurrence marker comes with it</h4>
     *
     * <p>Two threads that really did open with the same words have the same title, and the column
     * separates them with a leading "(2) " because there is nothing else honest to say about them.
     * Replacing the drawn label with the bare title therefore threw away the only part of those rows
     * that differed: a blind review photographed sixteen such rows, and while a sighted reader could
     * at least count them, a screen reader read sixteen controls claiming to be the same one.
     *
     * <p>So the marker is carried across, verbatim rather than reworded. What is spoken and what is
     * drawn stay the same string, which is the same reason this uses an off-screen span rather than
     * an {@code aria-label} assembled from three sources.
     *
     * <p>Must run on the Vaadin UI thread; it writes to components being assembled for it.
     *
     * @param row     the row being built
     * @param label   the fitted label already inside it
     * @param summary the conversation the row stands for
     */
    private static void announceWholeTitle(NativeButton row, Span label, ConversationSummary summary) {
        String drawn = label.getText();
        String whole = summary.title() == null || summary.title().isBlank()
                ? drawn
                : occurrenceMarker(drawn) + summary.title().strip();
        if (whole.equals(drawn)) {
            return;
        }
        label.getElement().setAttribute("aria-hidden", "true");
        Span spoken = new Span(whole);
        spoken.addClassName("jc-sr-only");
        row.getElement().appendChild(spoken.getElement());
    }

    /**
     * The "(2) " a label opens with when the column had to number two threads that read the same.
     *
     * <p>Matched rather than recomputed, because the number belongs to the labelling pass that saw
     * the whole visible set and this method sees one row. Recomputing it here would be a second
     * opinion about which row this is, and the two would disagree the moment a search narrowed the
     * column.
     *
     * <p>The pattern is anchored and bounded on both the digits and the shape, and anything that is
     * not exactly that shape yields nothing rather than being repaired: a label is text that has
     * passed through a model-supplied title, so the only safe thing to lift out of it is a form that
     * was fully specified in advance. A missed marker costs one row a distinguishing prefix it never
     * had; a mis-parsed one would announce the wrong ordinal, which is worse than announcing none.
     *
     * @param label the fitted label the column is drawing, never null
     * @return the marker including its trailing space, or an empty string when there is not one
     */
    private static String occurrenceMarker(String label) {
        Matcher marker = OCCURRENCE_MARKER.matcher(label);
        return marker.lookingAt() ? marker.group() : "";
    }

    /**
     * The rest of what the row could not fit.
     *
     * <p>A supplement, not a fallback. It used to be the only place a reader could tell two rows
     * apart, which meant they could not be told apart at all on a phone; now the label itself is
     * unique and this carries the untruncated topic and the excerpt for whoever hovers.
     *
     * @param summary the row being described
     * @return the untruncated topic and excerpt, never null and never blank
     */
    private static String tooltip(ConversationSummary summary) {
        String preview = summary.preview().isBlank() ? summary.title() : summary.preview();
        String detail = summary.title().isBlank() ? preview : summary.title() + "\n\n" + preview;
        if (summary.tags().isEmpty()) {
            return detail;
        }
        return detail + "\n\n" + String.join(" · ", summary.tags());
    }

    // ------------------------------------------------------------------ selection

    /**
     * Names the row the reader is in, once there is something to name it after.
     *
     * <p>Only a row this column invented can be renamed here. A stored conversation's title comes
     * from the store's own adapter, and overwriting it from the chrome would make the sidebar and
     * the store disagree about the same conversation.
     *
     * @param title the conversation's new name, already shortened for the column
     */
    void renameActive(String title) {
        if (draft == null || !draft.id().equals(selectedId) || title.isBlank()) {
            return;
        }
        draft = new ConversationSummary(draft.id(), title, draft.preview(), draft.group(),
                draft.tags(), draft.updatedAt(), draft.source());
        renderList();
    }

    /**
     * Starts a chat that is real from the first click.
     *
     * <p>The id is minted here rather than when the first message is sent, because the view has to
     * be bound to something before the reader types: an id invented at send time would mean the
     * first question of every new chat went to whichever conversation was open before it.
     */
    /**
     * Opens a chat that does not exist yet.
     *
     * <p>Reached from the button at the top of the column and from the keyboard, which is why it is
     * visible to the shell: a shortcut that had to reach in and click a button would be a second
     * path to the same act, and the two would drift.
     */
    void startNewChat() {
        draft = unsavedConversation(ConversationIds.newConversationId());
        select(draft);
    }

    /**
     * What a click on a row does: mark it, then tell the shell.
     *
     * <p>In that order, and both halves are needed. Marking first means the column is already
     * showing the reader's new place by the time the shell reacts, so a shell that navigates and
     * re-renders finds the column agreeing with it rather than flipping a moment later.
     *
     * <p>The counterpart is {@link #showConversation}, which does only the first half - see its note
     * for why the shell resolving an address must not come back through here.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param summary the row the reader pressed
     */
    private void select(ConversationSummary summary) {
        showConversation(summary);
        onSelect.accept(summary);
    }

    /**
     * Marks a row as the reader's place without reporting a choice they did not make.
     *
     * <p>Split out from {@link #select} for the one caller that is not a click: the shell resolving
     * a conversation out of the address bar on a reload. Reporting that through {@code onSelect}
     * would announce a selection to a shell that is in the middle of making it, and on a phone it
     * would close the drawer the reader has not opened yet.
     *
     * <p>A row the column has never heard of is accepted and drawn as a draft, which is what a chat
     * looks like before its first exchange reaches the store.
     *
     * <p>Must run on the Vaadin UI thread; it rebuilds the list.
     *
     * @param summary the conversation to mark
     */
    void showConversation(ConversationSummary summary) {
        selectedId = summary.id();
        // Selecting anything else abandons an untouched draft rather than leaving a stale row that
        // no longer corresponds to where the reader is.
        if (draft != null && !draft.id().equals(selectedId)) {
            draft = null;
        }
        // A conversation the store cannot answer for still needs a row, or the marked place would be
        // the one place the column does not show.
        if (draft == null && directory.find(selectedId).isEmpty()) {
            draft = summary.source() == ConversationSummary.Source.DRAFT
                    ? summary
                    : unsavedConversation(selectedId);
        }
        renderList();
    }

    /** Moves focus into the search box, for the keyboard shortcut the shell registers. */
    void focusSearch() {
        search.focus();
    }

    /**
     * Takes the caret into the drawer that has just opened over the content.
     *
     * <p>The close control rather than the first conversation, for the same reason a dialog focuses
     * its own dismiss: the reader has been moved somewhere they did not previously exist, and the
     * first thing they should be able to do is leave.
     */
    void focusDrawer() {
        collapse.focus();
    }

    /**
     * Says whether this column is currently covering the page or sitting beside it.
     *
     * <p>The column is one element in two arrangements. Beside the content it is navigation, and it
     * is the page's only navigation landmark - the thing a screen reader user jumps to when they
     * want the conversation list. Over the content it is a modal dialog in everything but its
     * announcement: the shell makes the page behind it inert, draws a scrim across it and closes it
     * on Escape, and until this existed it said none of that, so a reader was moved into an unnamed
     * region with no way to hear that everything else had gone away.
     *
     * <p>The roles are exclusive, which is why this is one call and not two. {@code role="dialog"}
     * on a {@code <nav>} replaces the landmark rather than adding to it, so writing it at desktop
     * width would cost the page the navigation landmark it should have there - and claiming
     * modality for a column that is not covering anything would be false.
     *
     * <p>Must run on the Vaadin UI thread. Called by the shell, which owns the arrangement.
     *
     * @param overlaying true while the column is drawn over the content as a drawer
     */
    void presentAsDrawer(boolean overlaying) {
        if (overlaying) {
            getElement().setAttribute("role", "dialog");
            getElement().setAttribute("aria-modal", "true");
            return;
        }
        getElement().removeAttribute("role");
        getElement().removeAttribute("aria-modal");
    }

    /** Opens the list of keystrokes, from the menu that owns it or from the keyboard. */
    void showKeyboardShortcuts() {
        userMenu.showKeyboardShortcuts();
    }

    // ------------------------------------------------------------------ time

    /**
     * A conversation time at the coarseness a sidebar can use: minutes, hours, days, then a date.
     *
     * <p>Present only where {@link ConversationActivity} watched the conversation being used, which
     * is what this process can honestly say: the chat-memory interface exposes no timestamp of its
     * own, so a conversation last touched before the application started has none. Rows without one
     * simply give their title more room rather than showing a fabricated time.
     *
     * @param updatedAt when the conversation was last used, empty when this process never saw it
     * @return the phrase to draw, or empty when there is no time to draw - never a fabricated one
     */
    private static Optional<String> relativeTime(Optional<Instant> updatedAt) {
        if (updatedAt.isEmpty()) {
            return Optional.empty();
        }
        Instant then = updatedAt.get();
        Duration age = Duration.between(then, Instant.now());
        if (age.isNegative() || age.toMinutes() < 1) {
            return Optional.of("now");
        }
        if (age.toHours() < 1) {
            return Optional.of(age.toMinutes() + "m");
        }
        if (age.toDays() < 1) {
            return Optional.of(age.toHours() + "h");
        }
        if (age.toDays() < 7) {
            return Optional.of(age.toDays() + "d");
        }
        return Optional.of(ABSOLUTE_DAY.format(then.atZone(ZoneId.systemDefault())));
    }
}
