package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.ShortcutRegistration;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.shared.Registration;
import org.springframework.core.env.Environment;

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
 * {@code data-sidebar} value that overrides the per-breakpoint default. Crossing a breakpoint drops
 * that override again: a choice made about a phone drawer should not decide what a desktop does.
 */
public class MainLayout extends Div implements RouterLayout {

    private static final String DEFAULT_TITLE = "New chat";

    /** Above this a title stops being a title and starts being the message. */
    private static final int TITLE_LENGTH = 42;

    private final transient ConversationSidebar sidebar;
    private final Div content = new Div();
    private final Div scrim = new Div();
    private final Span title = new Span(DEFAULT_TITLE);
    private final Span subtitle = new Span();
    private final Button drawerToggle = new Button();

    private LayoutMode mode = LayoutMode.DESKTOP;

    /**
     * The reader's explicit choice, or null while the per-breakpoint default still applies.
     *
     * <p>Null is not "closed": it means no attribute is written and the stylesheet decides. That
     * distinction is the whole reason the first paint needs no round trip.
     */
    private Boolean sidebarOpen;

    private transient Registration resizeRegistration;

    public MainLayout(ConversationDirectory directory, Environment environment) {
        // The shell is told which conversation the view below it is talking to, so the row the
        // reader is actually in is marked from the first paint and the bar can name it. Reading the
        // view's own constant is the honest coupling: there is exactly one live conversation, and
        // both the sidebar's aria-current and this title are meant to say which.
        this.sidebar = new ConversationSidebar(directory, ChatView.CONVERSATION_ID,
                this::onConversationSelected, this::closeSidebar);
        directory.find(ChatView.CONVERSATION_ID)
                .filter(conversation -> !conversation.title().isBlank())
                .ifPresent(conversation -> title.setText(conversation.title()));

        addClassName("jc-shell");
        activeModelLabel(environment).ifPresent(label -> {
            subtitle.setText(label);
            // Ellipsized on a narrow window, so the full value stays reachable.
            subtitle.setTitle(label);
        });

        scrim.addClassName("jc-scrim");
        scrim.getElement().setAttribute("aria-hidden", "true");
        scrim.addClickListener(event -> closeSidebar());

        content.addClassName("jc-main__content");

        Div main = new Div(createTopBar(), content);
        main.addClassName("jc-main");

        add(sidebar, scrim, main);
    }

    // ------------------------------------------------------------------ structure

    private Component createTopBar() {
        drawerToggle.setIcon(Icons.glyph(Icons.MENU));
        drawerToggle.addClassNames("jc-icon-button", "jc-topbar__toggle");
        drawerToggle.setDisableOnClick(false);
        drawerToggle.setAriaLabel("Show the conversation list");
        drawerToggle.setTooltipText("Show conversations");
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
    }

    @Override
    public void removeRouterLayoutContent(HasElement oldContent) {
        content.getElement().removeAllChildren();
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

        // Ctrl/Cmd+B and Ctrl/Cmd+K are what these two controls are called everywhere else; Escape
        // is the only way out of a drawer that covers the content it is meant to navigate.
        Shortcuts.addShortcutListener(this, this::toggleSidebar, Key.KEY_B, KeyModifier.CONTROL);
        Shortcuts.addShortcutListener(this, this::toggleSidebar, Key.KEY_B, KeyModifier.META);
        Shortcuts.addShortcutListener(this, sidebar::focusSearch, Key.KEY_K, KeyModifier.CONTROL);
        Shortcuts.addShortcutListener(this, sidebar::focusSearch, Key.KEY_K, KeyModifier.META);
        // Escape belongs to whatever is on top - a dialog, a menu - before it belongs to the shell.
        // Both defaults have to be relaxed for that: a shortcut normally stops propagation and calls
        // preventDefault, and an overlay that sees either of those stops closing on Escape.
        ShortcutRegistration escape = Shortcuts.addShortcutListener(this, this::closeOverlay, Key.ESCAPE);
        escape.setEventPropagationAllowed(true);
        escape.setBrowserDefaultAllowed(true);
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
        if (next == mode) {
            return;
        }
        mode = next;
        // A window that has just changed shape wants that shape's default, not the choice its
        // occupant made about a different one.
        sidebarOpen = null;
        writeSidebarState();
    }

    // ------------------------------------------------------------------ sidebar state

    private void toggleSidebar() {
        sidebarOpen = !isSidebarOpen();
        writeSidebarState();
    }

    private void closeSidebar() {
        sidebarOpen = false;
        writeSidebarState();
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

    private void writeSidebarState() {
        if (sidebarOpen == null) {
            getElement().removeAttribute("data-sidebar");
        } else {
            getElement().setAttribute("data-sidebar", sidebarOpen ? "open" : "closed");
        }
        boolean open = isSidebarOpen();
        // A drawer that covers the content also takes it out of play: without this the Tab key walks
        // straight past the drawer into a transcript the reader cannot see behind it.
        content.getElement().setAttribute("inert", mode.overlaysContent() && open);
        drawerToggle.getElement().setAttribute("aria-expanded", String.valueOf(open));
        String action = open ? "Hide the conversation list" : "Show the conversation list";
        drawerToggle.setAriaLabel(action);
        drawerToggle.setTooltipText(open ? "Hide conversations" : "Show conversations");
    }

    // ------------------------------------------------------------------ selection

    /**
     * Reflects the sidebar's selection in the bar above the content.
     *
     * <p>It does not yet change which conversation the chat view is talking to: that view is bound
     * to a single conversation id and rebinding it means owning its transcript, which is another
     * element's work. The title is honest about what was picked; the transcript below it is not
     * pretending to have changed.
     */
    private void onConversationSelected(ConversationSummary summary) {
        title.setText(summary.title().isBlank() ? DEFAULT_TITLE : summary.title());
        if (mode.overlaysContent()) {
            // On a phone the drawer is covering the thing that was just chosen.
            closeSidebar();
        }
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
        String named = shorten(question);
        if (named.isEmpty()) {
            return;
        }
        title.setText(named);
        title.setTitle(question.strip());
        sidebar.renameActive(named);
    }

    /** One line of it, at the length the bar and a 280px column can both show. */
    private static String shorten(String question) {
        String flat = question.strip().replaceAll("\\s+", " ");
        if (flat.length() <= TITLE_LENGTH) {
            return flat;
        }
        String cut = flat.substring(0, TITLE_LENGTH);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > TITLE_LENGTH / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }
}
