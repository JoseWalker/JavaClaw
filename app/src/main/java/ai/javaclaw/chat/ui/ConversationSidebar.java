package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

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
import java.util.UUID;
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
 */
class ConversationSidebar extends Div {

    private static final DateTimeFormatter ABSOLUTE_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final transient ConversationDirectory directory;
    private final transient Consumer<ConversationSummary> onSelect;

    private final TextField search = new TextField();
    private final Div list = new Div();

    /** Groups the reader has folded away. Held by name, so the state survives a re-render. */
    private final Set<String> collapsedGroups = new LinkedHashSet<>();

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

    private static ConversationSummary unsavedConversation(String id) {
        return new ConversationSummary(id, "New chat", "Nothing sent yet.", "Drafts", List.of(),
                Optional.of(Instant.now()), ConversationSummary.Source.DRAFT);
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

        Button collapse = new Button(Icons.glyph(Icons.PANEL));
        collapse.addClassNames("jc-icon-button", "jc-sidebar__collapse");
        collapse.setDisableOnClick(false);
        collapse.setAriaLabel("Hide the conversation list");
        collapse.setTooltipText("Hide conversations");
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
        newChat.addClickListener(event -> startDraft());

        search.setPlaceholder("Search chats");
        search.setAriaLabel("Search conversations");
        search.setClearButtonVisible(true);
        search.addClassName("jc-search");
        search.setPrefixComponent(Icons.glyph(Icons.SEARCH));
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
        list.getElement().setAttribute("role", "list");

        Div scroller = new Div(list);
        scroller.addClassName("jc-sidebar__scroll");
        return scroller;
    }

    private Component createFoot() {
        Div foot = new Div(new UserMenu());
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

        List<ConversationSummary> stored = directory.conversations();
        // Once the store can answer for the same id, the placeholder has done its job: leaving it
        // would print the reader's own conversation twice, under two different titles.
        if (draft != null && stored.stream().anyMatch(row -> row.id().equals(draft.id()))) {
            draft = null;
        }

        String query = Optional.ofNullable(search.getValue()).orElse("");
        List<ConversationSummary> rows = new ArrayList<>();
        if (draft != null && draft.matches(query)) {
            rows.add(draft);
        }
        stored.stream().filter(row -> row.matches(query)).forEach(rows::add);

        if (rows.isEmpty()) {
            list.add(noMatches(query));
            return;
        }

        // The draft belongs to no folder; it sits above the headings where a reader looks for the
        // thing they just created rather than hunting for it inside a group.
        if (draft != null && rows.contains(draft)) {
            rows.remove(draft);
            list.add(row(draft));
        }

        Map<String, List<ConversationSummary>> groups = new LinkedHashMap<>();
        for (ConversationSummary row : rows) {
            groups.computeIfAbsent(row.group(), key -> new ArrayList<>()).add(row);
        }
        // A search is a request to see matches, not to remember which drawers were shut.
        boolean forceOpen = !query.isBlank();
        groups.forEach((name, items) -> list.add(group(name, items, forceOpen)));
    }

    private Component noMatches(String query) {
        Span title = new Span("No chats match");
        title.addClassName("jc-sidebar__empty-title");

        Span hint = new Span("Nothing here matches “" + query.strip() + "”.");
        hint.addClassName("jc-sidebar__empty-hint");

        Div empty = new Div(title, hint);
        empty.addClassName("jc-sidebar__empty");
        return empty;
    }

    private Component group(String name, List<ConversationSummary> items, boolean forceOpen) {
        boolean collapsed = !forceOpen && collapsedGroups.contains(name);

        Span chevron = Icons.glyph(Icons.CHEVRON_DOWN);
        chevron.addClassName("jc-group__chevron");

        Span label = new Span(name);
        label.addClassName("jc-group__label");

        Span count = new Span(String.valueOf(items.size()));
        count.addClassName("jc-group__count");

        NativeButton head = new NativeButton();
        head.addClassName("jc-group__head");
        head.getElement().appendChild(chevron.getElement(), label.getElement(), count.getElement());
        head.getElement().setAttribute("aria-expanded", String.valueOf(!collapsed));
        head.addClickListener(event -> toggleGroup(name));

        Div body = new Div();
        body.addClassName("jc-group__items");
        items.forEach(item -> body.add(row(item)));

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

    private Component row(ConversationSummary summary) {
        boolean selected = summary.id().equals(selectedId);

        Span mark = Icons.glyph(Icons.BUBBLE);
        mark.addClassName("jc-conv__mark");

        Span title = new Span(summary.title());
        title.addClassName("jc-conv__title");

        NativeButton row = new NativeButton();
        row.addClassName("jc-conv");
        row.getElement().appendChild(mark.getElement(), title.getElement());
        relativeTime(summary.updatedAt()).ifPresent(text -> {
            Span time = new Span(text);
            time.addClassName("jc-conv__time");
            row.getElement().appendChild(time.getElement());
        });

        row.getElement().setAttribute("data-source", summary.source().name().toLowerCase(Locale.ROOT));
        // aria-current marks the one row that is the reader's current place, which is exactly what
        // the accent rail says visually. Selection here is not a form control, so it is not checked.
        row.getElement().setAttribute("aria-current", String.valueOf(selected));
        row.setTitle(tooltip(summary));
        row.addClickListener(event -> select(summary));
        return row;
    }

    private static String tooltip(ConversationSummary summary) {
        String preview = summary.preview().isBlank() ? summary.title() : summary.preview();
        if (summary.tags().isEmpty()) {
            return preview;
        }
        return preview + "\n\n" + String.join(" · ", summary.tags());
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

    private void startDraft() {
        draft = new ConversationSummary(
                "draft-" + UUID.randomUUID(),
                "New chat",
                "Nothing sent yet.",
                "Drafts",
                List.of(),
                Optional.of(Instant.now()),
                ConversationSummary.Source.DRAFT);
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

    // ------------------------------------------------------------------ time

    /**
     * A conversation time at the coarseness a sidebar can use: minutes, hours, days, then a date.
     *
     * <p>Absent for every stored conversation today, because the chat-memory interface does not
     * expose the timestamp the files actually carry. Rows without one simply give their title more
     * room rather than showing a fabricated time.
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
