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
 */
class ConversationSidebar extends Nav {

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

    private static final String SEAM_EXPLANATION =
            "This session has not seen the conversations below being used, so there is no recency "
                    + "to sort them by and they are ordered by name instead. They are no older and "
                    + "no less complete than the ones above.";

    private final transient ConversationDirectory directory;
    private final transient Consumer<ConversationSummary> onSelect;

    private final TextField search = new TextField();
    private final Div list = new Div();
    private final Button collapse = new Button();
    private final UserMenu userMenu = new UserMenu();

    /** Groups the reader has folded away. Held by name, so the state survives a re-render. */
    private final Set<String> collapsedGroups = new LinkedHashSet<>();

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
            list.add(row(draft, labels));
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
            return;
        }

        // A search is a request to see matches, not to remember which drawers were shut.
        boolean forceOpen = !query.isBlank();
        // The index is what the two halves of a disclosure are tied together with. It is a
        // position in this render rather than anything derived from the heading, because a heading
        // is store data and store data has no business becoming a DOM id.
        int index = 0;
        for (Map.Entry<String, List<ConversationSummary>> entry : groups.entrySet()) {
            list.add(group(entry.getKey(), entry.getValue(), forceOpen, labels, index++));
        }
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

    private Component noMatches(String query) {
        return emptyNote("No chats match", "Nothing here matches “" + query.strip() + "”.");
    }

    /**
     * What the column says when there is genuinely nothing yet.
     *
     * <p>Distinct from a search that found nothing, because the two are different problems and the
     * reader can only act on one of them. Telling someone with an empty store that their query
     * matched nothing invites them to go on clearing a box that was never the reason.
     */
    private Component noConversations() {
        return emptyNote("No chats yet", "Ask something and this is where it will be kept.");
    }

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
     * @param index this group's position in the current render, which is what the ids are built
     *              from; see {@link #renderList()} for why they are not built from the name
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
        addRows(body, items, labels);

        Div group = new Div(head, body);
        group.addClassName("jc-group");
        group.getElement().setAttribute("data-collapsed", String.valueOf(collapsed));
        return group;
    }

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
     */
    private void addRows(Div target, List<ConversationSummary> items, Map<String, String> labels) {
        boolean seamDrawn = false;
        for (ConversationSummary item : items) {
            if (item.source() != ConversationSummary.Source.DRAFT
                    && item.updatedAt().isEmpty() && !seamDrawn) {
                target.add(seam(seamsDrawn++));
                seamDrawn = true;
            }
            target.add(row(item, labels));
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
     * One conversation, as a row.
     *
     * @param summary the conversation
     * @param labels  the column's decided labels; a missing one falls back to the row's own title,
     *                so a row is never drawn nameless because the labelling disagreed about the set
     */
    private Component row(ConversationSummary summary, Map<String, String> labels) {
        boolean selected = summary.id().equals(selectedId);

        Span title = new Span(labels.getOrDefault(summary.id(),
                ConversationTitles.shorten(summary.title(), TITLE_WIDTH)));
        title.addClassName("jc-conv__title");

        // No icon in front of the title. It was the same speech bubble on every row, so it told the
        // reader nothing about which conversation they were looking at, and it took twenty-four
        // pixels out of a two-hundred-and-eighty pixel column where the titles were already being
        // cut off mid-word. Which row is the current one is said by the accent rail and the tinted
        // ground, neither of which costs the title any width.
        NativeButton row = new NativeButton();
        row.addClassName("jc-conv");
        row.getElement().appendChild(title.getElement());
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
        return row;
    }

    /**
     * The rest of what the row could not fit.
     *
     * <p>A supplement, not a fallback. It used to be the only place a reader could tell two rows
     * apart, which meant they could not be told apart at all on a phone; now the label itself is
     * unique and this carries the untruncated topic and the excerpt for whoever hovers.
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

    private void select(ConversationSummary summary) {
        selectedId = summary.id();
        // Selecting anything else abandons an untouched draft rather than leaving a stale row that
        // no longer corresponds to where the reader is.
        if (draft != null && !draft.id().equals(selectedId)) {
            draft = null;
        }
        renderList();
        onSelect.accept(summary);
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
