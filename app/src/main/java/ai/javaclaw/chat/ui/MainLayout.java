package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The application shell every routed view is rendered inside.
 *
 * <p>Three responsibilities, and no more: it owns the sidebar, it owns the bar above the content,
 * and it owns the one piece of state the two share - whether the sidebar is showing. The view it
 * hosts knows nothing about any of it.
 *
 * <h2>How the responsive behaviour is split</h2>
 *
 * <p>The stylesheet decides what the shell looks like at a given width, using the breakpoints
 * mirrored in {@link LayoutMode}. That is what makes the first paint correct: the server has not
 * heard from the browser yet, and a shell that guessed would show a phone a desktop sidebar for a
 * round trip. Until the reader touches the toggle, no state is written to the DOM at all and CSS
 * alone decides - persistent on a desktop, collapsed on a tablet, closed on a phone.
 *
 * <p>The toggle is what needs Java. Flipping the sidebar means knowing whether it is currently
 * showing, and that depends on the viewport. So the shell learns the width once on attach and again
 * on every resize, keeps the mode, and only from the first toggle onwards writes an explicit
 * {@code data-sidebar} value that overrides the per-breakpoint default. Crossing a breakpoint asks
 * {@link SidebarPreference} for the answer the reader gave about <em>that</em> shape, and falls back
 * to its default when there is none: a choice made about a phone drawer should not decide what a
 * desktop does, and should not be forgotten either.
 *
 * <h2>How a selection reaches the transcript</h2>
 *
 * <p>The shell owns which conversation the reader has chosen, because it owns the sidebar where the
 * choosing happens. It does not own the messages. So the routed view is asked, through
 * {@link ConversationHost}, to show what was picked - and the bar's title and the marked row are
 * updated from the same event, which is what stops the heading and the transcript disagreeing.
 */
public class MainLayout extends Div implements RouterLayout {

    private static final String DEFAULT_TITLE = "New chat";

    /**
     * The one id in this shell, and it exists for the skip link alone.
     *
     * <p>A fragment is the only way to say "past the navigation" that works before any script has
     * run, which is the only kind of skip link worth having.
     */
    private static final String CONTENT_ID = "jc-main-content";

    private final transient ConversationSidebar sidebar;
    private final Main content = new Main();
    private final Div scrim = new Div();
    /**
     * The name of the conversation on screen, and the page's only level-one heading.
     *
     * <p>It was a span, which meant this application shipped a document with no headings at all: a
     * reader navigating by heading - the first thing most screen reader users try on an unfamiliar
     * page - was told there was nothing to navigate.
     */
    private final H1 title = new H1(DEFAULT_TITLE);
    /**
     * Present tense, and deliberately so.
     *
     * <p>This line is read from configuration when the shell is laid out, and it is drawn directly
     * under the name of the thread on screen. A bare model name in that position is read as a
     * property of the conversation - "this is what wrote these answers" - which is a claim nothing
     * in this application is in a position to make: no turn records which model produced it, so a
     * reader who switches models mid-thread, or reloads a thread from before a switch, would be
     * told something false by chrome that never changed.
     *
     * <p>Recording the model on each turn is the answer to that and is the right one; until it
     * exists, the line says only what it can check, which is what will answer the next question.
     */
    private static final String MODEL_LINE_PREFIX = "Answering with ";

    private static final String MODEL_LINE_SCOPE =
            "The model configured now, which is what will answer the next question. Earlier answers "
                    + "in this conversation are not recorded with the model that wrote them.";

    private final Span subtitle = new Span();
    private final Button drawerToggle = new Button();

    private LayoutMode mode = LayoutMode.DESKTOP;

    /**
     * False until the browser has reported its width.
     *
     * <p>Without it the first report would be indistinguishable from "nothing changed" whenever the
     * window really is a desktop, and a preference stored for the desktop would never be applied.
     */
    private boolean modeKnown;

    /**
     * The conversation the reader is in. Never null: there is always somewhere to be, and a shell
     * that could not name where would have nothing to hand the view it hosts.
     */
    private transient ConversationSummary active;

    /** The routed view, when it is one that can be told to show a conversation. */
    private transient ConversationHost host;

    /**
     * The reader's explicit choice, or null while the per-breakpoint default still applies.
     *
     * <p>Null is not "closed": it means no attribute is written and the stylesheet decides. That
     * distinction is the whole reason the first paint needs no round trip.
     */
    private Boolean sidebarOpen;

    private transient Registration resizeRegistration;

    public MainLayout(ConversationDirectory directory, Environment environment) {
        // Opening on a new chat rather than resuming one. The shell used to drop the reader straight
        // into the shared web conversation, which was the only way to reach any history at all; now
        // that every conversation is one click away in the column, resuming one means the first
        // thing typed lands in the middle of a thread nobody asked to continue. Nothing is written
        // for a chat that is never spoken to, so this costs the store nothing.
        this.active = newChat();
        this.sidebar = new ConversationSidebar(directory, active.id(),
                this::onConversationSelected, this::closeSidebar);
        if (!active.title().isBlank()) {
            title.setText(active.title());
        }
        // The column is the one thing that hears a conversation being renamed, and until this line
        // existed it kept the news to itself: the bar was named once from the opening question and
        // never again, so one thread carried two names on one screen and a reload swapped which of
        // them was on top. Registered after the fields above are set, because the column reports
        // immediately on its first render.
        sidebar.whenActiveNamed(this::adoptActiveName);

        addClassName("jc-shell");
        activeModelLabel(environment).ifPresent(label -> {
            subtitle.setText(MODEL_LINE_PREFIX + label);
            subtitle.setTitle(MODEL_LINE_SCOPE);
        });

        scrim.addClassName("jc-scrim");
        scrim.getElement().setAttribute("aria-hidden", "true");
        scrim.addClickListener(event -> closeSidebar());

        content.addClassName("jc-main__content");
        content.setId(CONTENT_ID);
        // A landmark a fragment can land on. Without the tabindex the browser scrolls the region
        // into view and leaves focus where it was, so the reader's next Tab starts again from the
        // skip link they have just used - which is a skip link that skips nothing.
        content.getElement().setAttribute("tabindex", "-1");

        Div main = new Div(createTopBar(), content);
        main.addClassName("jc-main");

        // First in the DOM, because a skip link that is not the first stop is a stop the reader has
        // to tab past the navigation to reach, which is what it exists to avoid.
        add(createSkipLink(), sidebar, scrim, main);
    }

    /**
     * The way past the conversation column, for whoever is not using a pointer.
     *
     * <p>Off-screen until it is focused, which is the whole convention: it costs a sighted reader
     * nothing and it is the first thing a keyboard finds. It is a real anchor to a real fragment
     * rather than a button that calls {@code focus}, so it works on the first Tab of a cold page -
     * before the server has heard anything from the browser at all.
     *
     * <p>Router-ignored on purpose: Vaadin claims same-origin links, and a routed
     * {@code /ui/#jc-main-content} is a navigation to a view that does not exist. The reader would
     * be skipped into an empty page.
     */
    private static Component createSkipLink() {
        Anchor link = new Anchor("#" + CONTENT_ID, "Skip to the conversation");
        link.addClassName("jc-skip-link");
        link.setRouterIgnore(true);
        return link;
    }

    /**
     * A conversation that exists but has nothing in it yet.
     *
     * <p>The store has no record of a chat nobody has written to, but the reader is still in one,
     * and both the marked row and the bar's title are meant to say where. Marked as a draft so the
     * column can tell it apart from the conversations that are genuinely stored.
     *
     * <p>It carries no time. It used to report {@code now}, which is the same thing every stored row
     * says about a conversation the store really did write - a modification claim on a conversation
     * that has never been modified, because nothing has happened to it yet.
     */
    private static ConversationSummary newChat() {
        return new ConversationSummary(ConversationIds.newConversationId(), DEFAULT_TITLE,
                "Nothing sent yet.", "Drafts", List.of(), Optional.empty(),
                ConversationSummary.Source.DRAFT);
    }

    // ------------------------------------------------------------------ structure

    private Component createTopBar() {
        drawerToggle.setIcon(Icons.glyph(Icons.MENU));
        drawerToggle.addClassNames("jc-icon-button", "jc-topbar__toggle");
        drawerToggle.setDisableOnClick(false);
        drawerToggle.setAriaLabel("Show the conversation list");
        drawerToggle.setTooltipText(Shortcut.TOGGLE_SIDEBAR.tooltip("Show conversations"));
        // Said on the control as well as in the help, so a screen reader announces the keystroke
        // with the button rather than only where somebody thought to look for it.
        Shortcut.TOGGLE_SIDEBAR.announceOn(drawerToggle.getElement());
        drawerToggle.addClickListener(event -> toggleSidebar());

        title.addClassName("jc-topbar__title");
        subtitle.addClassName("jc-topbar__subtitle");

        Div heading = new Div(title, subtitle);
        heading.addClassName("jc-topbar__heading");

        Div actions = new Div(new ThemeToggle());
        actions.addClassName("jc-topbar__actions");

        // The bar's band is full bleed; what is in it sits on the same measure as the transcript and
        // the composer, so the title and the first word of every answer start on one x at every
        // width. Without this the header missed the content column by a different amount at each
        // breakpoint - including by 11px, which reads as a bug rather than as a decision.
        Div inner = new Div(drawerToggle, heading, actions);
        inner.addClassNames("jc-topbar__inner", "jc-measure");

        Header bar = new Header(inner);
        bar.addClassName("jc-topbar");
        return bar;
    }

    /**
     * Describes the model actually answering, for the second line of the bar.
     *
     * <p>Read from the environment rather than from the agent, which exposes no such accessor. Any
     * gap in the configuration means the bar simply drops the line: half a model name is worse
     * chrome than none.
     *
     * @param environment application configuration
     * @return provider and model as one label, or empty when no chat model is configured
     */
    private static Optional<String> activeModelLabel(Environment environment) {
        String provider = environment.getProperty("spring.ai.model.chat", "").trim();
        if (provider.isEmpty() || provider.equalsIgnoreCase("unknown") || provider.equalsIgnoreCase("none")) {
            return Optional.empty();
        }
        String name = provider.substring(0, 1).toUpperCase(Locale.ROOT) + provider.substring(1);
        String model = environment.getProperty("spring.ai." + provider + ".chat.options.model", "").trim();
        // Escaped rather than literal so the separator survives a build with a non-UTF-8 default.
        return Optional.of(model.isEmpty() ? name : name + " · " + model);
    }

    @Override
    public void showRouterLayoutContent(HasElement routerContent) {
        content.getElement().appendChild(routerContent.getElement());
        // A view that cannot show a conversation is hosted all the same; it simply never hears about
        // one. That is what keeps the shell reusable for a settings page later on.
        host = routerContent instanceof ConversationHost conversationHost ? conversationHost : null;
        if (host != null) {
            host.openConversation(active);
        }
    }

    @Override
    public void removeRouterLayoutContent(HasElement oldContent) {
        content.getElement().removeAllChildren();
        host = null;
    }

    // ------------------------------------------------------------------ viewport

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // window.innerWidth rather than a client-details round trip: it is the same width the
        // stylesheet's media queries are evaluated against, scrollbar included, so the mode the
        // shell believes it is in cannot disagree with the one the reader is looking at.
        getElement().executeJs("return window.innerWidth")
                .then(Integer.class, width -> applyViewportWidth(width == null ? 0 : width));

        Page page = attachEvent.getUI().getPage();
        resizeRegistration = page.addBrowserWindowResizeListener(event -> applyViewportWidth(event.getWidth()));

        // Every chord comes out of one table, which is the same table the help dialog is generated
        // from - see Shortcut. Nothing here chooses a key, so nothing here can ship a key the
        // reader has no way of finding.
        Shortcut.TOGGLE_SIDEBAR.register(this, this::toggleSidebar);
        // Every chord that reaches into the column has to open it first. Ctrl/Cmd + K used to call
        // focusSearch directly, which on a collapsed column focuses a field inside something the
        // stylesheet has set visibility:hidden - the caret stayed on the body, the column stayed
        // shut, and the reader was told nothing. It was in the help dialog the whole time, which
        // makes it worse than an undocumented key rather than better: the help is what sent them.
        Shortcut.SEARCH.register(this, this::searchConversations);
        Shortcut.NEW_CHAT.register(this, this::startNewChat);
        Shortcut.HELP.register(this, sidebar::showKeyboardShortcuts);
        installFocusMemory();
        // Escape belongs to whatever is on top - a dialog, a menu - before it belongs to the shell.
        // Both defaults have to be relaxed for that: a shortcut normally stops propagation and calls
        // preventDefault, and an overlay that sees either of those stops closing on Escape.
        Shortcut.ESCAPE.register(this, this::closeOverlay).forEach(registration -> {
            registration.setEventPropagationAllowed(true);
            registration.setBrowserDefaultAllowed(true);
        });
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // The listener is registered on the page, which outlives this component; leaving it attached
        // would keep pushing resize events at a detached shell.
        if (resizeRegistration != null) {
            resizeRegistration.remove();
            resizeRegistration = null;
        }
        super.onDetach(detachEvent);
    }

    private void applyViewportWidth(int width) {
        LayoutMode next = LayoutMode.forWidth(width);
        if (modeKnown && next == mode) {
            return;
        }
        modeKnown = true;
        mode = next;
        // A window that has just changed shape wants the answer the reader gave about that shape -
        // including on a reload, which is the first time this runs - and that shape's default when
        // they have not given one.
        sidebarOpen = SidebarPreference.stored(session(), mode).orElse(null);
        // Not a choice the reader has just made, so nothing moves the caret: a phone that reloads
        // into a remembered drawer must not take focus off whatever the reader does next.
        writeSidebarState(false);
    }

    // ------------------------------------------------------------------ sidebar state

    private void toggleSidebar() {
        rememberSidebarState(!isSidebarOpen());
    }

    /**
     * Opens the conversation column and puts the caret in its search box.
     *
     * <p>Two steps, in this order, and the order is the fix: the field lives inside a column the
     * stylesheet hides outright when it is shut, and focusing a hidden element is a no-op the
     * browser reports as success. The state is written first, so by the time the queued
     * {@code focus()} reaches the browser the column is showing and the field can take it. It is
     * the same two-step the drawer toggle already performs.
     *
     * <p>Deliberately not conditional on the column being shut. Opening an already-open column is
     * a no-op that costs nothing, and a branch here would be a second place for the two paths to
     * disagree.
     */
    private void searchConversations() {
        rememberSidebarState(true);
        sidebar.focusSearch();
    }

    /**
     * Starts a chat, and leaves the reader in it rather than merely in front of it.
     *
     * <p>The keystroke used to call straight into the column, which emptied the transcript and
     * stopped there: the caret was left on the document body and nothing was said out loud, because
     * {@code role="log"} announces what is <em>added</em> to a region and removing every message
     * adds nothing at all. So the one action whose whole purpose is "I want to type something else"
     * ended with the reader nowhere, in silence.
     *
     * <p>The view is asked to finish the job because the view owns the composer and the announcer;
     * a shell that reached in to focus a field would be the shell knowing there is a field.
     */
    private void startNewChat() {
        sidebar.startNewChat();
        if (host != null) {
            host.enterNewConversation();
        }
    }

    /**
     * Remembers where focus was before an overlay took it, for whatever has to give it back.
     *
     * <p>A dialog opened from the user menu used to drop focus on the body when it closed, because
     * the control that opened it - a menu item - had been destroyed along with the menu, and the
     * browser's own restore target no longer existed. The same dialog opened with Ctrl/Cmd + /
     * returned correctly. Two entry points, two behaviours, from one dialog.
     *
     * <p>So the last element focused <em>outside</em> any overlay is kept, and that is what closing
     * an overlay returns to. It is the trigger in the menu case and the reader's previous position
     * in the keyboard case, which is the same rule stated once rather than two special cases.
     *
     * <p>Installed once per document and guarded on its own flag: this shell can be re-attached,
     * and a second listener would not be wrong so much as pointless. Client-side because it is the
     * only side that knows what has focus.
     */
    private void installFocusMemory() {
        getElement().executeJs("""
                if (window.__jcFocusMemory) {
                    return;
                }
                window.__jcFocusMemory = true;
                // Capture phase: focusin bubbles, but an overlay's own handlers may stop it.
                document.addEventListener('focusin', (event) => {
                    const target = event.target;
                    if (!(target instanceof HTMLElement) || typeof target.focus !== 'function') {
                        return;
                    }
                    // Anything inside an overlay is where focus is going, not where it came from.
                    if (target.closest('vaadin-dialog-overlay, vaadin-popover-overlay')) {
                        return;
                    }
                    window.__jcFocusReturn = target;
                }, true);
                """);
    }

    private void closeSidebar() {
        rememberSidebarState(false);
    }

    /**
     * Applies a choice the reader made, and keeps it for the rest of the session.
     *
     * <p>Stored against the arrangement it was made in, so returning to a width is returning to the
     * layout that was left there rather than to the default.
     */
    private void rememberSidebarState(boolean open) {
        sidebarOpen = open;
        SidebarPreference.store(session(), mode, open);
        writeSidebarState(true);
    }

    /** The session, or null outside a UI - which is how the shell is instantiated in tests. */
    private VaadinSession session() {
        return getUI().map(UI::getSession).orElse(null);
    }

    /** Escape is only an exit when the sidebar is covering something. */
    private void closeOverlay() {
        if (mode.overlaysContent() && isSidebarOpen()) {
            closeSidebar();
        }
    }

    private boolean isSidebarOpen() {
        return sidebarOpen != null ? sidebarOpen : mode.sidebarOpenByDefault();
    }

    /**
     * Writes the drawer's state into the DOM, and moves the caret with it when a reader asked for
     * the change.
     *
     * <p>The focus half is what keeps the phone drawer usable without a pointer. Opening it makes
     * the content inert, and the control that opened it lives in that content - so the reader's
     * focus was on an element that had just been taken out of play, which browsers resolve by
     * dropping focus to the document body. Their next Tab then started from the top of the page
     * rather than from the drawer they had just opened, and nothing announced that a drawer had
     * appeared at all. Focus therefore follows the drawer in, and is handed back to the control
     * that opened it on the way out, which is what makes the trip reversible.
     *
     * <p>Deliberately not a focus trap. The drawer is one Tab away from the end of its own list, and
     * a reader who wants the browser's chrome should get it; Escape and the close control are both
     * exits, and a caged drawer would be a worse failure than the one being fixed.
     *
     * <p>The handover is no longer conditional on the drawer overlaying anything, and that was a
     * defect rather than a scope. On a desktop, collapsing the column while the caret sat on a
     * conversation row hid the element it was on, so the browser dropped focus to the document body
     * and the reader's next Tab restarted the entire order - eighty-odd stops away from where they
     * had been. Expanding did the same in reverse. The rule is the same at every width: the control
     * that takes over from the one that just disappeared gets the caret.
     *
     * @param byChoice true when this follows a toggle the reader worked, false when it follows a
     *                 resize or the first report of the viewport width - neither of which is a
     *                 reason to move anybody's caret
     */
    private void writeSidebarState(boolean byChoice) {
        if (sidebarOpen == null) {
            getElement().removeAttribute("data-sidebar");
        } else {
            getElement().setAttribute("data-sidebar", sidebarOpen ? "open" : "closed");
        }
        boolean open = isSidebarOpen();
        boolean overlays = mode.overlaysContent();
        // A drawer that covers the content also takes it out of play: without this the Tab key walks
        // straight past the drawer into a transcript the reader cannot see behind it.
        content.getElement().setAttribute("inert", overlays && open);
        // Behaviourally the drawer already is a modal dialog - it makes the content inert, draws a
        // scrim over it and answers Escape - and it announced none of that. A reader was moved into
        // an unnamed navigation landmark with no way to hear that the page behind it had gone away.
        // Only in the arrangement where it is true: at desktop width the column pushes the content
        // aside rather than covering it, and calling that a dialog would be a lie that also costs
        // the page its navigation landmark.
        sidebar.presentAsDrawer(overlays && open);
        drawerToggle.getElement().setAttribute("aria-expanded", String.valueOf(open));
        String action = open ? "Hide the conversation list" : "Show the conversation list";
        drawerToggle.setAriaLabel(action);
        drawerToggle.setTooltipText(Shortcut.TOGGLE_SIDEBAR
                .tooltip(open ? "Hide conversations" : "Show conversations"));
        if (!byChoice) {
            return;
        }
        if (open) {
            sidebar.focusDrawer();
        } else {
            drawerToggle.focus();
        }
    }

    // ------------------------------------------------------------------ selection

    /**
     * Opens what the sidebar just reported, everywhere at once.
     *
     * <p>The bar is renamed and the view below it is rebound in the same call, because those two are
     * the same claim: a title that changed while the transcript did not is the shell telling the
     * reader they are somewhere they are not.
     */
    private void onConversationSelected(ConversationSummary summary) {
        active = summary;
        showTitle(summary.title(), summary.preview());
        if (host != null) {
            host.openConversation(summary);
        }
        if (mode.overlaysContent()) {
            // On a phone the drawer is covering the thing that was just chosen.
            closeSidebar();
        }
    }

    /**
     * Writes a conversation's name into the bar.
     *
     * <p>One place, because the bar ellipsises a long topic and the column shortens it further: the
     * tooltip leads with the whole name and then the excerpt, so the untruncated title has somewhere
     * it can be read. It used to carry the excerpt alone, which meant that place was nowhere.
     *
     * @param named   the conversation's name, blank for one that has none yet
     * @param excerpt what the conversation is about, blank when there is nothing to show
     */
    private void showTitle(String named, String excerpt) {
        String shown = named.isBlank() ? DEFAULT_TITLE : named;
        title.setText(shown);
        title.setTitle(excerpt.isBlank() ? shown : shown + "\n\n" + excerpt);
    }

    /**
     * Renames the bar when the column settles on what the open conversation is called.
     *
     * <p>This is the join that was missing. A name is not decided when the page is drawn: it starts
     * as a reduction of the opening question and is replaced, seconds later and on a naming thread,
     * by one the model was asked for. That replacement reached the row and stopped there, so the
     * screen carried two names for one conversation at the same moment - and after a reload the bar
     * showed the other one for an identical transcript.
     *
     * <p>The cached summary is replaced as well as the text, so leaving the conversation and coming
     * back to it does not restore the name the shell was holding from before the rename.
     *
     * @param current the open conversation as the column now draws it
     */
    private void adoptActiveName(ConversationSummary current) {
        // The column reports whichever row it has marked; a report about anything other than where
        // the shell believes the reader is would be renaming the wrong conversation.
        if (!current.id().equals(active.id())) {
            return;
        }
        active = current;
        showTitle(current.title(), current.preview());
    }

    /**
     * Re-reads the conversation list after the transcript below has changed it.
     *
     * <p>Called by the view when a turn is finished with. Until the exchange is written there is
     * nothing in the store to read, which is why this is not done when the question is sent. The
     * bar follows from the same redraw, through {@link #adoptActiveName}.
     */
    void activeConversationUpdated() {
        sidebar.refresh();
    }

    /**
     * Names the live conversation from the first thing asked in it.
     *
     * <p>The chat store records no title, and the sidebar's own adapter derives one from the first
     * question - but not until the exchange has been written, which is long after the reader wants
     * to see where they are. Until then both the bar and the marked row would read "New chat"
     * through an entire answer. Called by the view that sent the message.
     *
     * @param question the message that opened the conversation
     */
    void activeConversationTitled(String question) {
        // Whatever named it first keeps the name: a conversation is what it was opened with.
        if (!DEFAULT_TITLE.equals(title.getText())) {
            return;
        }
        // The same derivation the store's own adapter will apply once the exchange is written, so
        // the name the reader watches appear is the name that survives the reload.
        String named = ConversationTitles.topic(question);
        if (named.isEmpty()) {
            return;
        }
        // The column first, then the bar. Renaming the row redraws it, and a redraw reports the
        // placeholder back here - which carries no excerpt yet, the exchange not being written. The
        // question is a better one than that, and this is the only moment anything has it.
        sidebar.renameActive(named);
        showTitle(named, question.strip());
        // Kept in step so a later re-selection of the same row does not put the old name back.
        active = new ConversationSummary(active.id(), named, question.strip(), active.group(),
                active.tags(), active.updatedAt(), active.source());
    }
}
