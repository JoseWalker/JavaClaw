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
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import org.springframework.core.env.Environment;

import java.util.LinkedHashSet;
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
 *
 * <h2>Where the reader's place is kept</h2>
 *
 * <p>In the address bar, which is the only place that survives a reload. It used to be kept nowhere:
 * an audit with a full answer on screen pressed reload and came back to an empty transcript titled
 * "New chat", because clicking a row had never changed the URL and there was nothing for the second
 * page load to read. So the shell does both halves of that here - it reads the conversation a
 * navigation named before the view is shown, and it writes the conversation the reader moved to back
 * into the address afterwards.
 *
 * <p>Both halves belong to the shell rather than to the view for the same reason the sidebar does:
 * the shell is what knows which conversation is open. A view that read its own route parameter would
 * be overwritten a moment later by {@link #showRouterLayoutContent}, which hands it whatever the
 * shell believes - so the two would have to agree, and the way that ends is with the URL and the
 * transcript naming different threads.
 */
public class MainLayout extends Div implements RouterLayout, BeforeEnterObserver {

    /**
     * What the bar says before a conversation has a name. Deliberately not "Untitled" or blank: the
     * heading is the page's only level-one heading, and a reader navigating by heading has to land
     * on something that says where they are.
     */
    private static final String DEFAULT_TITLE = "New chat";

    /**
     * The one id in this shell, and it exists for the skip link alone.
     *
     * <p>A fragment is the only way to say "past the navigation" that works before any script has
     * run, which is the only kind of skip link worth having.
     */
    private static final String CONTENT_ID = "jc-main-content";

    /**
     * The conversation list, kept because an address can name a conversation nobody clicked.
     *
     * <p>The column reads it too, and reads it again on every render; this reference exists only so
     * a navigation can be resolved to a real summary - with the real title, so the bar is right on
     * the first paint rather than a redraw later.
     */
    private final transient ConversationDirectory directory;

    /**
     * The conversation column. Transient because it holds a reference to the directory, which is an
     * application bean and has no business being written into a passivated session.
     */
    private final transient ConversationSidebar sidebar;

    /**
     * The landmark the routed view is placed into, and the target of the skip link.
     *
     * <p>A {@code <main>} rather than a div, because that is what makes the skip link's destination
     * a landmark rather than an arbitrary anchor - and there is exactly one of them per document,
     * which is why the routed view is swapped inside it rather than replacing it.
     */
    private final Main content = new Main();

    /**
     * Everything that is not the column: the bar and the region under it.
     *
     * <p>Held as a field for one reason, and it is the reason {@link #writeSidebarState} explains -
     * this is the box that goes {@code inert} while the drawer covers it. It used to be the content
     * region alone, which is a child of this and not the whole of it, so the bar's two controls
     * stayed reachable behind the scrim.
     */
    private final Div main = new Div();

    /**
     * The first tab stop, and therefore one that has to go out of play with the rest of the page.
     *
     * <p>It is outside {@link #main} by construction - a skip link that is not the first stop is a
     * stop the reader has to tab past the navigation to reach - so it needs saying to separately.
     * Left reachable, it would offer a keyboard reader a jump into a region that is inert.
     */
    private final Anchor skipLink = createSkipLink();

    /**
     * The dimming layer behind the drawer on a narrow window, and the thing a tap outside it closes.
     *
     * <p>Present in the document at every width rather than added when the drawer opens: it is
     * hidden by the stylesheet outside the mobile band, and creating it on open would mean the
     * element that closes the drawer does not exist until after the drawer has finished animating.
     */
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
     * Future tense, and deliberately so.
     *
     * <p>This line is read from configuration when the shell is laid out, and it is drawn directly
     * under the name of the thread on screen. A bare model name in that position is read as a
     * property of the conversation - "this is what wrote these answers" - which is a claim nothing
     * in this application is in a position to make: no turn records which model produced it, so a
     * reader who switches models mid-thread, or reloads a thread from before a switch, would be
     * told something false by chrome that never changed.
     *
     * <p>It said "Answering with" until four independent reviews measured the same thing: the
     * string is byte-identical on an empty chat, on a settled transcript and mid-answer, so a
     * present-progressive verb was reporting a state it was not reading. The correction was in the
     * element's {@code title}, which is reachable by neither keyboard nor touch - a caveat most of
     * the people it is for cannot get at, sitting under a sentence that contradicts it.
     *
     * <p>Recording the model on each turn is the answer to that and is the right one; until it
     * exists, the line says only what it can check, which is what will answer the next question -
     * and now says it in the tense the tooltip always used.
     */
    private static final String MODEL_LINE_PREFIX = "Next answer: ";

    /**
     * The caveat that goes with {@link #MODEL_LINE_PREFIX}, stated in full rather than implied.
     *
     * <p>It says the one thing the line cannot: that earlier answers in this conversation were not
     * recorded with the model that wrote them. That limitation is real and this shell cannot fix it,
     * so the honest response is to say so where the claim is made rather than to let the reader
     * infer something stronger from a bare model name.
     */
    private static final String MODEL_LINE_SCOPE =
            "The model configured now, which is what will answer the next question. Earlier answers "
                    + "in this conversation are not recorded with the model that wrote them.";

    /**
     * The line under the heading that names the configured model. Empty when no model is configured,
     * because a subtitle that says nothing is better than one that names a model that is not there.
     */
    private final Span subtitle = new Span();

    /**
     * What the shell says when the browser has lost its connection.
     *
     * <p>Present from the first paint and never removed, for the reason the transcript's announcer
     * is: assistive technology reports changes to regions that already existed when the change
     * happened, so a live region created at the moment it has something to say is the one shape of
     * live region that cannot work. Empty until there is something to report, and hidden by the
     * stylesheet until then, so it costs a reader on a working connection nothing at all.
     *
     * <p>Its text is written from the browser rather than from here, which is not a style choice: the
     * state it describes is exactly the state in which nothing can be written from here.
     */
    private final Span connection = new Span();

    /** The sentence {@link #connection} carries, passed to the browser so it exists in one place. */
    private static final String OFFLINE_NOTICE =
            "You are offline. JavaClaw cannot be reached until the connection returns.";

    /**
     * The control that shows and hides the conversation column, at every width.
     *
     * <p>One control rather than one per arrangement, because it is the same action: what changes
     * between the drawer and the persistent column is where the column goes, not what the button
     * does. Held as a field because its accessible name and {@code aria-expanded} have to be
     * rewritten whenever the column's state changes - see {@link #writeSidebarState}.
     */
    private final Button drawerToggle = new Button();

    /**
     * The arrangement currently on screen. Seeded with desktop, which is what the stylesheet assumes
     * before the browser has reported a width, so the server's belief and the first paint agree.
     * Only meaningful once {@link #modeKnown} is true.
     */
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

    /**
     * The browser-resize subscription, kept so it can be cancelled on detach.
     *
     * <p>Vaadin does not remove a page-level resize listener when the component that added it goes
     * away, so a shell that detached without cancelling would leave a listener holding this instance
     * for the life of the session - one per navigation. Transient because a registration belongs to
     * a live connection and means nothing in a deserialised session.
     */
    private transient Registration resizeRegistration;

    /**
     * The address this shell has already asked the browser to show, or null before it has asked.
     *
     * <p>Kept so a redraw that changes nothing costs nothing. The column reports the open
     * conversation on every render - a name arriving from the naming thread, a search box being
     * typed into - and each of those would otherwise queue another history call carrying the address
     * the browser is already on.
     */
    private String shownAddress;

    /**
     * Which conversation {@link #shownAddress} belongs to, which is what decides push from replace.
     *
     * <p>Not derivable from the address itself, because the address of a chat that has not been
     * written is the empty path - the same empty path every other unwritten chat has. Two different
     * chats can therefore sit at the same address, and the second one acquiring an address of its
     * own is still the reader standing still rather than moving.
     */
    private String shownConversationId;

    /**
     * Conversations the view has reported writing, oldest first.
     *
     * <p>A chat becomes somewhere worth returning to when its first turn is on disk, and the view is
     * the only party that knows the moment that happens. The column cannot be that party: it reads
     * the agent's own store, which is written on its own schedule, and an audit measured the cost of
     * waiting for it - a full answer on screen, forty-five seconds later, with the address still on
     * the application root and a reload landing on an empty "New chat".
     *
     * <p>Bounded, and the bound is not decoration. This grows with what the reader does and nothing
     * ever removes from it, so an unbounded set here would be a session-lifetime leak driven from
     * outside. Losing the oldest entry costs nothing that matters: by the time a reader has opened
     * sixty-four further conversations, the store the column reads has long since caught up and
     * reports the row as stored on its own.
     */
    private final LinkedHashSet<String> written = new LinkedHashSet<>();

    /** How many written conversations {@link #written} remembers before it drops its oldest. */
    private static final int WRITTEN_REMEMBERED = 64;

    /**
     * Builds the whole shell and opens it on a fresh chat. Vaadin and Spring supply the arguments.
     *
     * <p>Everything is assembled here rather than on attach because the shell has no state to wait
     * for: the column reads the directory itself and the model line comes from configuration. What
     * is deliberately <em>not</em> done here is deciding the sidebar's state - that needs the
     * browser's width, which is only known after the first round trip, and guessing it is what
     * would produce a visible flip on the first paint.
     *
     * <p>Ordering inside is load bearing in one place: the rename subscription is registered only
     * after the active conversation and the title are set, because the column reports immediately on
     * its first render and a callback that fired into half-built state would name the wrong thread.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param directory the conversation directory, used to resolve an address that names a
     *     conversation nobody clicked on; never null
     * @param environment application configuration, read once for the model line; never null
     */
    public MainLayout(ConversationDirectory directory, Environment environment) {
        // Opening on a new chat rather than resuming one. The shell used to drop the reader straight
        // into the shared web conversation, which was the only way to reach any history at all; now
        // that every conversation is one click away in the column, resuming one means the first
        // thing typed lands in the middle of a thread nobody asked to continue. Nothing is written
        // for a chat that is never spoken to, so this costs the store nothing.
        this.active = newChat();
        // The browser is at the application root and this chat is what is drawn there, so the root
        // entry belongs to it. Recording that here is what makes the chat's own address, when it
        // acquires one, a replacement of the entry the reader is standing on rather than a second
        // entry for the screen they never left.
        this.shownConversationId = active.id();
        this.directory = directory;
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

        main.add(createTopBar(), content);
        main.addClassName("jc-main");

        // First in the DOM, because a skip link that is not the first stop is a stop the reader has
        // to tab past the navigation to reach, which is what it exists to avoid.
        add(skipLink, sidebar, scrim, main);
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
     *
     * @return the link, detached; built once, from the field initialiser
     */
    private static Anchor createSkipLink() {
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
     *
     * @return a summary marked as a draft, with a fresh conversation id and no time
     */
    private static ConversationSummary newChat() {
        return new ConversationSummary(ConversationIds.newConversationId(), DEFAULT_TITLE,
                "Nothing sent yet.", "Drafts", List.of(), Optional.empty(),
                ConversationSummary.Source.DRAFT);
    }

    // ------------------------------------------------------------------ structure

    /**
     * Assembles the bar above the content: the drawer toggle, the heading, and the theme control.
     *
     * <p>Split out of the constructor because it is the one part of the shell whose internal
     * ordering is dictated by the stylesheet rather than by logic - the three children are what the
     * narrow arrangement's grid tracks are numbered against, so their order is load bearing and
     * belongs somewhere a reader can see it whole.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @return the bar, already classed and wired; called once, from the constructor
     */
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
        // the composer. Without this the header missed the content column by a different amount at
        // each breakpoint - including by 11px, which reads as a bug rather than as a decision. The
        // order of the three is what the narrow arrangement's grid tracks are numbered against, so
        // it is load bearing rather than incidental: see "Where the heading starts" in the sheet.
        Div inner = new Div(drawerToggle, heading, actions);
        inner.addClassNames("jc-topbar__inner", "jc-measure");

        connection.addClassName("jc-connection");
        // A status rather than an alert: losing the connection is not worth interrupting a reader
        // mid-word for, and the sentence is on screen for as long as it is true.
        connection.getElement().setAttribute("role", "status");
        connection.getElement().setAttribute("aria-live", "polite");

        // Under the bar's own contents rather than inside them, so it spans the whole band: it is a
        // statement about the application, not about the conversation named beside it.
        Header bar = new Header(inner, connection);
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

    /**
     * Opens whatever conversation the address bar named, before the view is shown.
     *
     * <p>This is the half of the fix that makes a reload land where the reader was. Vaadin fires this
     * on the whole navigation chain before {@link #showRouterLayoutContent} runs, which is exactly the
     * order that is needed: the shell settles what is open, and the view is then handed it through
     * the same path a click on a row uses. Doing it the other way round - the view reading its own
     * parameter - would have the shell overwrite it a moment later with the chat it opened on.
     *
     * <p>An id the store has never heard of is still honoured, because that is the ordinary state of
     * a chat whose first exchange has not been written yet - the alternative is a reload that
     * silently moves the reader to a different thread. An id that could not be a conversation at all
     * is refused by {@link ConversationAddress#from(com.vaadin.flow.router.RouteParameters)} and
     * never reaches the store; the reader then gets a new chat, which is what the plain route means.
     *
     * <p>A navigation naming no conversation is a new chat, because that is what the plain route
     * draws, and since a selection now leaves a history
     * entry behind it, the root is an entry the reader can come back to. This used to be ignored
     * outright - correct for the one navigation that is not really one, the first page load, where
     * the shell has just built the chat the reader is in, and wrong for every other, where it would
     * leave a stored conversation on screen underneath a URL that says otherwise.
     *
     * <p>The two are told apart by what the shell is holding rather than by anything in the event,
     * because there is nothing in the event to tell them apart by: a shell already sitting in a chat
     * with nothing written in it is already showing what the root means, so it stays put.
     *
     * <p>Must run on the Vaadin UI thread; Vaadin only calls it there.
     *
     * @param event the navigation being entered
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Optional<ConversationSummary> named = ConversationAddress.from(event.getRouteParameters())
                .map(id -> directory.find(id).orElseGet(() -> unwrittenChat(id)));
        if (named.isPresent()) {
            openFromAddress(named.get());
            return;
        }
        if (inAnUnwrittenChat()) {
            return;
        }
        openNewChatFromAddress();
    }

    /**
     * Whether the reader is already in a chat that has nothing in it, which is what the root shows.
     *
     * <p>Read from the shell's own state rather than from the store: the store is behind by design
     * for exactly the window this matters in, and a chat the view has reported writing is a
     * conversation whether or not the column has noticed yet.
     *
     * @return true when the open conversation is a chat with no turn written for it
     */
    private boolean inAnUnwrittenChat() {
        return active.source() == ConversationSummary.Source.DRAFT && !written.contains(active.id());
    }

    /**
     * Puts the reader in a fresh chat because the address bar now names none.
     *
     * <p>Everything {@link #openFromAddress} does, for a conversation that does not exist yet rather
     * than one the address named. The address is recorded without being written back: the browser is
     * already at the root, and asking it to go there would add an entry for the page it is showing.
     *
     * <p>The view is told twice over - opened, then entered - because arriving at the root by
     * pressing Back is the reader asking for a new chat as deliberately as pressing the control is,
     * and a new chat that leaves the caret nowhere in silence is the defect that control already
     * had to fix once.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void openNewChatFromAddress() {
        ConversationSummary chat = newChat();
        active = chat;
        shownAddress = "";
        shownConversationId = chat.id();
        showTitle(chat.title(), chat.preview());
        sidebar.showConversation(chat);
        if (host != null) {
            host.openConversation(chat);
            host.enterNewConversation();
        }
    }

    /**
     * A conversation the address named and the store has no record of.
     *
     * <p>Not an error and not a miss: a chat is minted the moment the reader asks for one, and the
     * store hears about it only when its first exchange is written. Between those two moments the
     * address is valid and the store is empty, and a reload in that window has to come back to the
     * same empty chat rather than to a different one.
     *
     * @param id a well-formed conversation id from the address bar
     * @return a placeholder summary for it, marked as a draft exactly as the sidebar's own is
     */
    private static ConversationSummary unwrittenChat(String id) {
        return new ConversationSummary(id, DEFAULT_TITLE, "Nothing sent yet.", "Drafts", List.of(),
                Optional.empty(), ConversationSummary.Source.DRAFT);
    }

    /**
     * Moves the shell onto a conversation the reader did not click, because the URL asked for it.
     *
     * <p>Everything a click does. The column is marked without its selection callback being fired,
     * because that callback exists to report a choice the reader made and this is not one.
     *
     * <p>The view is told when there is one, and that is a repair rather than a flourish. On a cold
     * load there is no view yet and {@link #showRouterLayoutContent} hands it {@link #active} the
     * moment there is, which is why this used to say nothing to it. Pressing Back is not a cold
     * load: the view is already attached and Flow does not rebuild it for a route that resolves to
     * the same component, so nothing would hand it anything - the bar and the marked row would
     * follow the address and the messages under them would not.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param conversation the conversation the address named
     */
    private void openFromAddress(ConversationSummary conversation) {
        active = conversation;
        // The browser is already at this address; recording it stops the first redraw from asking it
        // to go there again, and stops a chat that arrived by address from having that address taken
        // away before its first exchange is written. See addressOf.
        shownAddress = ConversationAddress.of(conversation.id());
        shownConversationId = conversation.id();
        showTitle(conversation.title(), conversation.preview());
        sidebar.showConversation(conversation);
        if (host != null) {
            host.openConversation(conversation);
        }
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
        installConnectionWatch();
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

    /**
     * Adopts the arrangement a reported viewport width calls for.
     *
     * <p>Guarded by {@link #modeKnown} rather than by the width alone, and the guard is the point:
     * the first report from a desktop-sized window produces the same mode the field was seeded with,
     * so a plain equality check would return early and the reader's stored preference for that
     * width would never be applied.
     *
     * <p>Re-reads the preference on every real change instead of carrying the old one across,
     * because a choice is stored per arrangement - returning to a width means returning to the
     * layout that was left there, not to whatever the last width was set to.
     *
     * <p>Must run on the Vaadin UI thread; it is called from the browser's resize callback.
     *
     * @param width the viewport width in CSS pixels, as reported by the browser
     */
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

    /**
     * Flips the conversation column, treating the flip as a choice the reader has made.
     *
     * <p>Routed through {@link #rememberSidebarState} rather than writing the DOM directly, so a
     * press of the toggle is stored for the current arrangement like any other choice. The negation
     * is taken from {@link #isSidebarOpen()}, which resolves the null "no preference yet" case to
     * the arrangement's default - so the first press flips away from what is on screen rather than
     * from an unset field.
     *
     * <p>Must run on the Vaadin UI thread.
     */
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
     * Remembers where focus was before an overlay took it, and gives it back when one closes.
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
     * <h4>Why the overlay is found in the composed path and not with closest()</h4>
     *
     * <p>The rule was right and the test for it was not, which is why the shortcuts dialog went on
     * losing focus after this existed. A Vaadin dialog keeps its overlay in the dialog element's
     * own shadow root, and a listener on the document is outside that shadow tree - so the browser
     * retargets the event and hands this listener the {@code <vaadin-dialog>} host rather than the
     * control that was focused. Asking the host for its closest overlay ancestor finds nothing,
     * because the overlay is below it rather than above it. Every control in the dialog therefore
     * passed the guard and was remembered as somewhere to come back to, and the memory the dialog
     * was about to read had been overwritten by the dialog itself. Restoring it then focused an
     * element that was on its way out, or the host element, which cannot take focus at all - and
     * the browser resolves that by leaving the reader on the document body.
     *
     * <p>{@code composedPath()} crosses shadow boundaries, so it sees both ends: the element really
     * focused at its head, and every overlay between there and the document. Any tag ending in
     * {@code -overlay} disqualifies the whole path, which covers the dialog, the popover and
     * anything Vaadin adds later without this having to be edited again.
     *
     * <h4>Why the memory starts somewhere rather than empty</h4>
     *
     * <p>The reader most likely to press Ctrl/Cmd + / is the one who has touched nothing yet, and
     * with an empty memory closing the dialog restored nothing and left them on the document body.
     * The content landmark is passed in as the starting value, so there is always somewhere to come
     * back to - and it is passed as the element rather than as a selector, so the id cannot be
     * renamed out from under it. The same value covers a remembered element that has since been
     * removed from the document, which is the other way this used to end in silence.
     *
     * <h4>Why the same memory also reports facts</h4>
     *
     * <p>{@code __jcRestoreFocus} is a command: an overlay closing knows the caret is its to give
     * back. Nothing else on this page knows that. A view whose turn has just ended would like the
     * caret back and has no right to it - the model finishing is not something the reader did, and
     * taking focus on an event the reader did not cause is WCAG 2.1 SC 3.2.1 - so what it needs is
     * not a way to move the caret but a way to find out whether moving it would take anything away
     * from anybody. Answering that needs this memory, so the report is published from here, where
     * the memory means what this script says it means.
     *
     * <p>{@code __jcFocusFacts} reports and decides nothing, and the difference is the whole point.
     * A first version of this decided here, in one function that looked around and answered yes or
     * no, and nothing in this repository could execute a line of it: the rule could be inverted or
     * pinned to a constant with the entire test suite still green. What it returns now is eight
     * property reads, named for the components of {@code CaretPlace}, which is where the rule lives
     * and where every clause of it is a case a test can state.
     *
     * <p>The line is drawn at composition, and the second attempt at drawing it had to move. It
     * first reported {@code caretHeld}, computed here by a five-clause {@code usable()} predicate -
     * one name, one browser property mentioned, and a whole policy behind it. That passes the only
     * check this side can be given, which is that an observation still names the property it is
     * about, while an inverted {@code !usable(...)} hands the caret to the composer on every
     * finished turn. So the four {@code caret*} observations are reported raw and the composition
     * is {@code CaretPlace#caretHeld}, where dropping any one clause fails a named test.
     *
     * <p>Those four are a snapshot, and their meaning depends on the send control's disable having
     * reached the browser before the question runs; see {@code ChatView#offerTheCaretBack} for why
     * that holds and what must not be done to it.
     *
     * <p>{@code readerSteppedAway} exists because two situations are otherwise identical and want
     * opposite answers. A reader dropped when the composer disabled the control they were on, and a
     * reader who gave the caret up themselves before that happened, both end with a loose caret and
     * a memory still naming a control inside the composer. Only the departure separates them, so
     * the departure is recorded as it happens: a {@code focusout} with no {@code relatedTarget} is
     * focus going nowhere, and it is read as the reader letting go only when what they left still
     * worked. A control disabled under a reader has already stopped being usable by the time it
     * lets go, so it does not register - and if the browser does not blur it at all, no
     * {@code focusout} fires and there is nothing to register either. Both readings arrive at the
     * same place, which is what the discrimination needs.
     *
     * <p>The selection is reported collapsed or not, and the server reads an uncollapsed one as
     * somebody reading. Focusing anything collapses a selection, and a reader halfway through
     * dragging over an answer to copy it has not asked for that.
     *
     * <h4>What a browser was observed doing, rather than assumed to do</h4>
     *
     * <p>None of the above had ever been run when it was first written, and two of its three
     * claims about browsers were wrong. It was measured against this application in Chromium 136,
     * driving real turns and reading what this script returned at the moment the server asked; the
     * cases are recorded in {@link CaretPlace}. Three results are worth keeping next to the code
     * that depends on them.
     *
     * <p>A reader who clicks a paragraph of an answer is <em>not</em> left holding nothing. The
     * transcript carries {@code tabindex="0"} because it is a log region, so the click focuses the
     * transcript itself, the {@code focusout} carries it as {@code relatedTarget}, and no departure
     * is recorded. That reader is turned away by {@code lastChoiceInRegion} - which the same click
     * has just moved out of the composer - and not by {@code readerSteppedAway} at all. The earlier
     * claim that no {@code focusin} fires on that path was simply false.
     *
     * <p>A reader standing on the send control when the end of a turn disables it is <em>not</em>
     * blurred. The control stays {@code document.activeElement} carrying {@code disabled === true}.
     * That is the case {@code caretDisabled} exists for, and it is the case a rule built on
     * {@code caretOnRoot} alone would miss - it would report the caret as held, and the reader
     * would never get it back.
     *
     * <p>Focus that genuinely goes nowhere does arrive as {@code relatedTarget === null} and is
     * recorded. That is the one arrangement in which every other clause of the rule says yes and
     * only {@code readerSteppedAway} says no, so it is the fact's whole justification, and it holds.
     *
     * <p>Installed once per document and guarded on its own flag: this shell can be re-attached,
     * and a second listener would not be wrong so much as pointless. Client-side because it is the
     * only side that knows what has focus.
     *
     * <p>Must run on the Vaadin UI thread. The script it installs runs only on the browser's own
     * event loop, and nothing on the server reads what it remembers.
     */
    private void installFocusMemory() {
        getElement().executeJs("""
                // Somewhere to come back to before the reader has been anywhere, and after a
                // remembered control has been removed from the document. Written on every install
                // rather than only on the first: the listener outlives a re-attached shell, and the
                // landmark it points at does not, so a stale one would fail the same isConnected
                // check the remembered element just failed and leave the reader nowhere again.
                window.__jcFocusFallback = $0;
                if (window.__jcFocusMemory) {
                    return;
                }
                window.__jcFocusMemory = true;
                // An overlay in a shadow root retargets the event to its host, so the path is what
                // has to be searched - the target alone is the host, and a host has no overlay
                // above it to find.
                const inOverlay = (path) => path.some((node) => typeof node.localName === 'string'
                        && node.localName.endsWith('-overlay'));
                // Capture phase: focusin bubbles, but an overlay's own handlers may stop it.
                document.addEventListener('focusin', (event) => {
                    const path = typeof event.composedPath === 'function'
                            ? event.composedPath()
                            : [event.target];
                    // Anything inside an overlay is where focus is going, not where it came from.
                    if (inOverlay(path)) {
                        return;
                    }
                    const target = path[0];
                    if (!(target instanceof HTMLElement) || typeof target.focus !== 'function') {
                        return;
                    }
                    window.__jcFocusReturn = target;
                    // The reader is holding something again, so whatever they gave up before is no
                    // longer what describes them.
                    window.__jcFocusGivenUp = false;
                }, true);
                // Whether a node is something the reader could still be acting through. Anything
                // that is not an element, is out of the document, is one of the two document roots,
                // or has been disabled counts as nothing. Vaadin disables a control by setting both
                // the property and aria-disabled, and a custom element with the attribute is not
                // natively disabled at all, so both are asked.
                //
                // This is the last judgement left on this side, and it is here because it cannot be
                // anywhere else: it answers a question about the moment a focusout fired, and by
                // the time the server could be asked that moment is gone. The same question at
                // report time is answered on the server, from the four caret observations below.
                const usable = (node) => !!node
                        && typeof node.getAttribute === 'function'
                        && node.isConnected === true
                        && node !== document.body
                        && node !== document.documentElement
                        && node.disabled !== true
                        && node.getAttribute('aria-disabled') !== 'true';
                // The reader letting go of the caret, which is the only thing that separates being
                // dropped from walking off. A focusout with no relatedTarget is focus going nowhere:
                // a click on text that cannot take it, or the page losing focus entirely. Recorded
                // only when what they left still worked - a control disabled under a reader has
                // already stopped being usable by the time it lets go, and that is the case this
                // must not read as a departure.
                document.addEventListener('focusout', (event) => {
                    if (event.relatedTarget) {
                        return;
                    }
                    const path = typeof event.composedPath === 'function'
                            ? event.composedPath()
                            : [event.target];
                    if (inOverlay(path)) {
                        return;
                    }
                    if (!usable(path[0])) {
                        return;
                    }
                    window.__jcFocusGivenUp = true;
                }, true);
                // What the browser can see and the server cannot, reported and not judged. The rule
                // these observations feed lives on the server, where it can be executed by a test;
                // this side reads properties, and every name here is a component of the record that
                // decodes it.
                //
                // One observation per name, each a single property read. That is a requirement and
                // not a style: KeyboardAccessTest checks that every one still names the browser
                // property it claims to be about, which is the only thing on this side that notices
                // an observation quietly replaced by a constant - and a text check can go no
                // further than that, so there must be nothing further here for it to miss. The four
                // caret observations used to be one 'caretHeld: usable(document.activeElement)',
                // which passed exactly that check while an inverted '!usable' would have handed the
                // caret to the composer on every finished turn with the whole suite green. Nothing
                // may be composed back into this object: an && added here is a clause no test can
                // reach. Nothing here may throw either, because a script that throws leaves the
                // answer unsent and an unsent answer is a caret that never comes back.
                window.__jcFocusFacts = (region) => ({
                    regionAttached: !!region && region.isConnected === true,
                    caretConnected: document.activeElement?.isConnected === true,
                    caretOnRoot: document.activeElement === document.body
                            || document.activeElement === document.documentElement,
                    caretDisabled: document.activeElement?.disabled === true,
                    caretAriaDisabled:
                            document.activeElement?.getAttribute?.('aria-disabled') === 'true',
                    selectionCollapsed: window.getSelection()?.isCollapsed === true,
                    lastChoiceInRegion: !!window.__jcFocusReturn
                            && typeof region?.contains === 'function'
                            && region.contains(window.__jcFocusReturn) === true,
                    readerSteppedAway: window.__jcFocusGivenUp === true
                });
                // The one way back, so an overlay that closes does not get to invent its own.
                window.__jcRestoreFocus = () => {
                    const remembered = window.__jcFocusReturn;
                    const target = remembered && remembered.isConnected
                            ? remembered
                            : window.__jcFocusFallback;
                    if (target && target.isConnected && typeof target.focus === 'function') {
                        target.focus();
                    }
                };
                """, content.getElement());
    }

    /**
     * Watches the browser's own connection, and marks the shell when it goes.
     *
     * <p>Offline was not a designed state anywhere in this application. An audit went offline and
     * pressed Enter: for fourteen seconds nothing was added, the composer kept the text, the send
     * control kept its resting treatment and the interface said nothing at all. The only signal was
     * Vaadin's own connection indicator - a five-pixel striped band whose sentence is in the DOM and
     * is not painted readably - so the reader's evidence that anything had happened was a key that
     * appeared to be broken.
     *
     * <p>Entirely client side, and that is forced rather than preferred: the server is precisely
     * what is unreachable while this is true, so anything that had to be decided here would be
     * decided after the state was over. The browser's own {@code online} and {@code offline} events
     * are what is listened for, because they are the events the platform fires and the ones a test
     * harness taking the network away triggers.
     *
     * <p>Deny by default in the safe direction: the mark is written only when {@code navigator}
     * says outright that it is offline. A browser that will not answer is treated as connected,
     * because a false offline band on a working connection is a worse lie than a missing one - it
     * would tell the reader their working application is broken.
     *
     * <p>The attribute is written on the shell rather than a class toggled, so it cannot collide
     * with the class list Vaadin owns from the server, and it sits alongside {@code data-sidebar},
     * which the server does own. The two never contend: Flow sends only the attributes it changed.
     *
     * <p>Installed once per document and guarded on a window flag, in the same shape as
     * {@link #installFocusMemory}: the listeners are registered on {@code window}, which outlives
     * this shell, so a re-attached shell re-points the elements the closure writes to instead of
     * registering a second pair.
     *
     * <p>Must run on the Vaadin UI thread. The script it installs runs only on the browser's own
     * event loop, and nothing on the server reads what it writes.
     */
    private void installConnectionWatch() {
        getElement().executeJs("""
                // Re-pointed on every install rather than only on the first: the listeners outlive a
                // re-attached shell and the elements they write to do not, so a stale reference here
                // would leave the band unwritten on exactly the shell the reader is looking at.
                window.__jcConnectionShell = this;
                window.__jcConnectionBand = $0;
                window.__jcOfflineNotice = $1;
                const mark = () => {
                    const shell = window.__jcConnectionShell;
                    const band = window.__jcConnectionBand;
                    if (!shell || !shell.isConnected || !band) {
                        return;
                    }
                    // Only an explicit false is offline. An undefined navigator.onLine - or one on a
                    // browser that does not implement it - reads as connected, because claiming an
                    // outage that is not happening is the worse of the two mistakes.
                    const offline = navigator.onLine === false;
                    // The attribute first, so the band is on screen before its text changes: a live
                    // region written to while it is still display:none is a region nothing reports.
                    shell.setAttribute('data-connection', offline ? 'offline' : 'online');
                    band.textContent = offline ? window.__jcOfflineNotice : '';
                };
                if (!window.__jcConnectionWatch) {
                    window.__jcConnectionWatch = true;
                    window.addEventListener('online', () => mark());
                    window.addEventListener('offline', () => mark());
                }
                mark();
                """, connection.getElement(), OFFLINE_NOTICE);
    }

    /**
     * Shuts the conversation column, as a choice rather than as a side effect.
     *
     * <p>Handed to the column itself and to the scrim, so that closing from inside the drawer and
     * tapping outside it take the same path as the toggle and are stored the same way. Unconditional
     * on purpose: closing an already-closed column costs nothing, and a guard here would be a second
     * place for the three entry points to disagree.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void closeSidebar() {
        rememberSidebarState(false);
    }

    /**
     * Applies a choice the reader made, and keeps it for the rest of the session.
     *
     * <p>Stored against the arrangement it was made in, so returning to a width is returning to the
     * layout that was left there rather than to the default.
     *
     * @param open whether the column should be showing from now on, at this width
     */
    private void rememberSidebarState(boolean open) {
        sidebarOpen = open;
        SidebarPreference.store(session(), mode, open);
        writeSidebarState(true);
    }

    /**
     * The session, or null outside a UI - which is how the shell is instantiated in tests.
     *
     * @return the session, or null when the shell is not attached to a UI - every caller tolerates
     *     null rather than guarding, which is what keeps the shell constructible in a test
     */
    private VaadinSession session() {
        return getUI().map(UI::getSession).orElse(null);
    }

    /** Escape is only an exit when the sidebar is covering something. */
    private void closeOverlay() {
        if (mode.overlaysContent() && isSidebarOpen()) {
            closeSidebar();
        }
    }

    /**
     * Whether the column is showing, resolving "no preference yet" to the arrangement's default.
     *
     * <p>The one place that collapses the three-valued {@link #sidebarOpen} to a boolean. Null is
     * not "closed" - it means nothing is written to the DOM and the stylesheet decides - so a caller
     * that read the field directly would treat a first-visit desktop, where the column is open, as
     * shut. Everything that needs a yes or no asks here instead.
     *
     * @return true when the column is on screen right now
     */
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
        // A drawer that covers the page also takes the page out of play: without this the Tab key
        // walks straight past the drawer into things the reader cannot see behind the scrim.
        //
        // The whole page, and that is a repair rather than a restatement. This used to be set on the
        // content region alone, which is the transcript and the composer - the bar above them is a
        // sibling of that region rather than a child of it, so the drawer toggle and the theme
        // control went on taking focus and went on being announced for the whole time the drawer was
        // open, behind a shade that had already told the reader they were gone. The skip link is the
        // third piece, outside both: reachable, it would offer a keyboard reader a jump into a region
        // that is inert.
        boolean covered = overlays && open;
        main.getElement().setAttribute("inert", covered);
        skipLink.getElement().setAttribute("inert", covered);
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
     *
     * @param summary the conversation the column reported, never null
     */
    private void onConversationSelected(ConversationSummary summary) {
        active = summary;
        showTitle(summary.title(), summary.preview());
        addressActiveConversation();
        if (host != null) {
            host.openConversation(summary);
        }
        if (mode.overlaysContent()) {
            // On a phone the drawer is covering the thing that was just chosen.
            closeSidebar();
        }
    }

    /**
     * Writes the open conversation into the address bar, so a reload comes back to it.
     *
     * <h4>Why a selection is pushed and a promotion is replaced</h4>
     *
     * <p>Every selection used to be replaced, on the argument that choosing a row is moving sideways
     * within one screen rather than going anywhere. The argument is reasonable and the measurement
     * against it is not close: {@code history.length} read 2 at load and stayed 2 after three
     * conversations were opened in turn, so one Back landed on {@code about:blank} with an empty body
     * - the entire application gone in one keystroke, from the control every browser puts within a
     * thumb's reach. A history the reader can walk back through is a smaller cost than a Back button
     * that is a trapdoor, and it is what the product this is measured against does.
     *
     * <p>The distinction that keeps that honest is the conversation, not the address. Moving to a
     * different conversation is a place the reader can want back and is pushed. The same conversation
     * acquiring an address - a chat that has just been written for the first time - is the reader
     * standing still while the URL catches up, and pushing that would put an entry in the history for
     * the screen they are already looking at, so Back would appear to do nothing.
     *
     * <p>Silent when nothing has changed, because this is reached from every redraw the column
     * reports through and a repeated history call is a round trip that says nothing. Silent too on
     * the first address of a session when that address is the root, which is where the browser
     * already is. And silent when there is no session behind the UI, which is a component tree in a
     * test rather than a browser: there is no address bar to write to and no reader to disappoint.
     *
     * <p>Must run on the Vaadin UI thread.
     */
    private void addressActiveConversation() {
        String address = addressOf(active);
        // Read before the record is updated: whether this is a move or a promotion is a fact about
        // where the browser was, and the browser is where it was until this method says otherwise.
        boolean sameConversation = active.id().equals(shownConversationId);
        boolean firstOfTheSession = shownAddress == null;
        shownConversationId = active.id();
        if (address.equals(shownAddress)) {
            return;
        }
        shownAddress = address;
        if (firstOfTheSession && address.isEmpty()) {
            // The application's root, on a page that opened at the application's root. There is
            // nothing to ask for, and asking would spend a history entry saying so.
            return;
        }
        getUI().filter(ui -> ui.getSession() != null && ui.getSession().getService() != null)
                .ifPresent(ui -> moveTo(ui, address, sameConversation));
    }

    /**
     * Asks the browser to show an address, in whichever of the two ways this move deserves.
     *
     * <p>Split out so {@link #addressActiveConversation} reads as the decision it is and this reads
     * as the one instruction it is. Both calls carry no state object: the shell reconstructs
     * everything it needs from the address itself when the navigation comes back, and a state object
     * would be a second copy of that to keep in step.
     *
     * <p>Must run on the Vaadin UI thread.
     *
     * @param ui               the UI whose page to address; must have a session and a service
     * @param address          where to go, relative to the application's base URL
     * @param sameConversation true when this is the conversation already on screen acquiring its own
     *                         address, which overwrites the entry rather than adding one
     */
    private static void moveTo(UI ui, String address, boolean sameConversation) {
        Location location = new Location(address);
        if (sameConversation) {
            ui.getPage().getHistory().replaceState(null, location);
        } else {
            ui.getPage().getHistory().pushState(null, location);
        }
    }

    /**
     * Where a conversation should be found, or the plain route when it should not be found anywhere.
     *
     * <p>A chat with nothing written in it does not get an address of its own. One is minted the
     * moment a reader asks for a new chat, before they have typed anything, and addressing that would
     * put a link to an empty thread in the address bar and in their history for a conversation that
     * will never exist if they navigate away.
     *
     * <p>What counts as written is asked of {@link #written} first and of the column's row second,
     * and that order is the fix. The column reads the agent's own store, which is written on its own
     * schedule; the view knows the moment the turn is on disk. Waiting for the column was measured
     * costing the reader the one conversation they are most likely to reload - the one they are in
     * the middle of - because nothing ever asked again.
     *
     * <p>The other exception is a chat the browser is <em>already</em> showing the address of, which
     * is a reload landing on a conversation whose first exchange has not been written. Taking that
     * address away would move the reader off the place they arrived at, and put them somewhere they
     * would have to find their way back from - a worse version of the defect this mechanism fixes.
     *
     * @param conversation the conversation the shell believes is open
     * @return its address, or the empty path meaning the application's own root
     */
    private String addressOf(ConversationSummary conversation) {
        String own = ConversationAddress.of(conversation.id());
        boolean stored = conversation.source() != ConversationSummary.Source.DRAFT
                || written.contains(conversation.id());
        return stored || own.equals(shownAddress) ? own : "";
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
        // The moment a chat stops being a draft it acquires an address, and this is where the shell
        // finds that out: the column reports the stored row as soon as the first exchange is on disk.
        // Without this line the one conversation a reader is most likely to reload - the one they are
        // in the middle of - would be the one with no address.
        addressActiveConversation();
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
     * Records that a conversation now has a turn on disk, and addresses it if it is the open one.
     *
     * <p>This is the moment a chat stops being a chat and becomes somewhere a reader can come back
     * to, and the view is the only party that knows when it arrives. The shell used to learn it from
     * the conversation column instead, which reads the agent's own store rather than the transcript
     * the turn was written to - so it heard about the change on that store's schedule, or, as an
     * audit measured, not at all: a full answer on screen, forty-five seconds later, the address
     * still on the application root, and a reload landing on an empty "New chat" while the thread sat
     * safely in the column below.
     *
     * <p>A report about any other conversation is recorded and goes no further. A turn can be filed
     * after the reader has clicked away from the thread it belongs to - the view files it under the
     * conversation it was asked of, deliberately - and addressing that would put the reader back
     * somewhere they had just left.
     *
     * <p>Must run on the Vaadin UI thread; it writes to the address bar through the UI.
     *
     * @param conversationId the conversation whose first turn has been written, possibly null and
     *                       possibly not the one on screen
     */
    void activeConversationStored(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        remember(conversationId);
        if (!conversationId.equals(active.id())) {
            return;
        }
        addressActiveConversation();
    }

    /**
     * Adds a conversation to the bounded memory of what has been written, dropping the oldest first.
     *
     * <p>The bound is what stops a set fed by the reader's own actions from growing for the lifetime
     * of a session. Dropping the oldest is safe in a way dropping the newest would not be: the
     * entries this loses are conversations from long enough ago that the store the column reads has
     * caught up and reports them as stored without any help from here.
     *
     * @param conversationId the conversation to remember, already known non-blank
     */
    private void remember(String conversationId) {
        if (written.contains(conversationId)) {
            return;
        }
        if (written.size() >= WRITTEN_REMEMBERED) {
            written.removeFirst();
        }
        written.add(conversationId);
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
