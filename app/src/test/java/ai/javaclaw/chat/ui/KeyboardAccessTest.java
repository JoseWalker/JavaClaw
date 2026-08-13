package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateTree;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this interface promises to somebody who never touches the pointer.
 *
 * <p>Every claim here was a hole first. The shell answered to two keystrokes and told nobody about
 * one of them; the transcript was a live region with no name, so it was announced as "log" and
 * nothing else; the conversation list and the three suggested questions both declared themselves
 * lists whose items were buttons, which is a role that replaces the button rather than describing
 * it, so a screen reader offered the reader eight things to read and nothing to press; there was no
 * skip link, so reaching the answer meant tabbing the whole navigation column first; and the only
 * signal that a turn had started or stopped was a shape changing inside a button.
 *
 * <p>None of that is visible in a screenshot, which is exactly why it is asserted here. The tests
 * are written against the DOM the browser is handed rather than against the Java that produces it,
 * because the DOM is what assistive technology reads.
 */
class KeyboardAccessTest {

    // ------------------------------------------------------------------ the shortcut table

    @Test
    @DisplayName("no shortcut is a secret: every one of them is in the list the UI can show")
    void everyShortcutIsDiscoverable() {
        // The dialog is built from the same table the registrations are, so a shortcut added
        // without a line in the help is not possible rather than merely discouraged. This asserts
        // the wiring that guarantees it, because the guarantee is the point.
        List<String> listed = rows(Shortcut.helpRows());

        assertThat(listed).hasSize(Shortcut.values().length);
        for (Shortcut shortcut : Shortcut.values()) {
            assertThat(listed).contains(shortcut.keys() + " " + shortcut.description());
        }
    }

    @Test
    @DisplayName("two shortcuts never answer to the same keystroke")
    void everyChordIsUnique() {
        List<String> chords = Arrays.stream(Shortcut.values()).map(Shortcut::keys).toList();

        assertThat(chords).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a shortcut says what it is in the words assistive technology uses")
    void everyShortcutNamesItselfForAssistiveTechnology() {
        assertThat(Shortcut.TOGGLE_SIDEBAR.ariaKeyShortcuts()).isEqualTo("Control+B Meta+B");
        assertThat(Shortcut.NEW_CHAT.ariaKeyShortcuts()).isEqualTo("Control+Shift+O Meta+Shift+O");
        assertThat(Shortcut.SEND.ariaKeyShortcuts()).isEqualTo("Enter");
        assertThat(Shortcut.ESCAPE.ariaKeyShortcuts()).isEqualTo("Escape");
    }

    @Test
    @DisplayName("a shortcut the shell never registers refuses to pretend it did")
    void aComposerKeyCannotBeRegisteredGlobally() {
        // Enter and Shift + Enter belong to the field the caret is in. Registering them on the
        // shell would send a message from anywhere on the page, including from the search box.
        assertThat(Shortcut.SEND.isGlobal()).isFalse();
        assertThat(Shortcut.NEW_LINE.isGlobal()).isFalse();
        assertThat(Shortcut.NEW_CHAT.isGlobal()).isTrue();
    }

    // ------------------------------------------------------------------ the document

    /**
     * The served page declared {@code lang="es"}, and it declared it to everybody: the same value
     * came back under three different {@code Accept-Language} headers, because Vaadin had nothing
     * to go on but the JVM default locale of whoever started the process. Every string in this
     * interface is English, so a screen reader was being told to read English prose with Spanish
     * pronunciation rules.
     */
    @Test
    @DisplayName("the document says what language it is in, and does not guess")
    void theDocumentDeclaresItsLanguage() {
        Document served = Jsoup.parse("<html><head></head><body></body></html>");

        DocumentLanguage.pin(served);

        assertThat(served.selectFirst("html").attr("lang")).isEqualTo("en");
    }

    @Test
    @DisplayName("a language already declared by the document is replaced rather than left to chance")
    void theDeclaredLanguageIsAuthoritative() {
        Document served = Jsoup.parse("<html lang=\"es\"><head></head><body></body></html>");

        DocumentLanguage.pin(served);

        assertThat(served.selectFirst("html").attr("lang")).isEqualTo("en");
    }

    // ------------------------------------------------------------------ the shell

    @Test
    @DisplayName("the first thing a keyboard reaches is the way past the navigation")
    void theSkipLinkOpensTheTabOrder() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());

        Element first = descendants(shell.getElement()).findFirst().orElseThrow();

        assertThat(first.getClassList()).contains("jc-skip-link");
        assertThat(first.getTag()).isEqualTo("a");
        // A fragment the browser resolves itself. Routed through Vaadin it would be a navigation to
        // a view that does not exist, which is a skip link that empties the page it skips into.
        assertThat(first.getAttribute("href")).isEqualTo("#jc-main-content");
        assertThat(first.getTextRecursively()).isNotBlank();
    }

    @Test
    @DisplayName("the region the skip link jumps to is a landmark that can hold focus")
    void theContentRegionIsAFocusableLandmark() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());

        Element main = byId(shell.getElement(), "jc-main-content");

        assertThat(main.getTag()).isEqualTo("main");
        // Without this the fragment moves the viewport and leaves focus at the top of the document,
        // so the next Tab starts again from the skip link the reader has just used.
        assertThat(main.getAttribute("tabindex")).isEqualTo("-1");
    }

    @Test
    @DisplayName("the conversation column is a navigation landmark, not an unnamed box")
    void theSidebarIsANavigationLandmark() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());

        Element sidebar = byClass(shell.getElement(), "jc-sidebar");

        assertThat(sidebar.getTag()).isEqualTo("nav");
        assertThat(sidebar.getAttribute("aria-label")).isEqualTo("Conversations");
    }

    @Test
    @DisplayName("the conversation on screen is the page's heading, not a styled span")
    void theOpenConversationIsAHeading() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());

        assertThat(byClass(shell.getElement(), "jc-topbar__title").getTag()).isEqualTo("h1");
    }

    @Test
    @DisplayName("a control that has a shortcut says so where assistive technology looks")
    void theControlsCarryTheirOwnShortcuts() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());

        assertThat(byClass(shell.getElement(), "jc-topbar__toggle").getAttribute("aria-keyshortcuts"))
                .isEqualTo(Shortcut.TOGGLE_SIDEBAR.ariaKeyShortcuts());
        assertThat(byClass(shell.getElement(), "jc-new-chat").getAttribute("aria-keyshortcuts"))
                .isEqualTo(Shortcut.NEW_CHAT.ariaKeyShortcuts());
        assertThat(byClass(shell.getElement(), "jc-search").getAttribute("aria-keyshortcuts"))
                .isEqualTo(Shortcut.SEARCH.ariaKeyShortcuts());
    }

    // ------------------------------------------------------------------ the chords, driven

    /**
     * Ctrl/Cmd + K was in the help dialog and did nothing. With the column collapsed - which is the
     * default on a tablet and the state a desktop reader leaves it in when they want the measure -
     * the shortcut focused a field inside a hidden column: the active element stayed on the body,
     * {@code data-sidebar} stayed "closed", and nothing was announced. A key that is documented and
     * dead is worse than one that is undocumented, because the help is what sent the reader to it.
     */
    @Test
    @DisplayName("the search shortcut opens the column it searches before reaching into it")
    void searchOpensTheColumnFirst() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web-abc", "Build tools")),
                new MockEnvironment());
        ui.add(shell);
        UiHarness.press(ui.getElement(), "KeyB");
        assertThat(shell.getElement().getAttribute("data-sidebar")).isEqualTo("closed");

        UiHarness.press(ui.getElement(), "KeyK");

        assertThat(shell.getElement().getAttribute("data-sidebar")).isEqualTo("open");
    }

    /**
     * The same audit applied to the other row of the table that reaches into the column. Starting a
     * chat from the keyboard emptied the transcript, left the caret on the document body and said
     * nothing at all - {@code role="log"} announces additions, so removing every message produces
     * no output whatsoever, and the one thing a reader needs after starting a chat is to be in the
     * box they are about to type in.
     */
    @Test
    @DisplayName("starting a chat from the keyboard says so and puts the caret where the typing goes")
    void aNewChatIsAnnouncedAndFocused() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web-abc", "Build tools")),
                new MockEnvironment());
        ChatView view = new ChatView(new SilentAgent(), new SameThreadExecutor(),
                new EmptyTranscripts(), new ConversationActivity());
        shell.showRouterLayoutContent(view);
        ui.add(shell);

        UiHarness.press(ui.getElement(), "KeyO");

        assertThat(byClass(view.getElement(), "jc-announcer").getTextRecursively()).isEqualTo("New chat");
    }

    /**
     * Collapsing the column while the caret was on a conversation row left it on the document body,
     * because the row it was sitting on had just been hidden - so the reader's next Tab restarted
     * the whole order. Expanding did the same in reverse. The 390px drawer already hands focus over
     * in both directions; this is that behaviour, no longer conditional on the drawer overlaying
     * anything.
     */
    @Test
    @DisplayName("the sidebar toggle takes the caret with it, in both directions")
    void togglingTheSidebarMovesTheCaret() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web-abc", "Build tools")),
                new MockEnvironment());
        ui.add(shell);

        UiHarness.press(ui.getElement(), "KeyB");
        List<String> afterCollapsing = focusCalls(ui);
        assertThat(afterCollapsing).isNotEmpty();
        assertThat(afterCollapsing.getLast()).contains("jc-topbar__toggle");

        UiHarness.press(ui.getElement(), "KeyB");
        List<String> afterExpanding = focusCalls(ui);
        assertThat(afterExpanding).isNotEmpty();
        assertThat(afterExpanding.getLast()).contains("jc-sidebar__collapse");
    }

    @Test
    @DisplayName("the conversation list does not claim to be a list of things that are not items")
    void theSidebarDoesNotMisdeclareItsRows() {
        // role="list" over a run of buttons is not a description of them, it is a replacement: a
        // row announced as a list item is a row the reader is never told they can press.
        MainLayout shell = new MainLayout(
                new FakeDirectory(stored("web-abc", "Comparing build tools")),
                new MockEnvironment());

        List<String> roles = descendants(byClass(shell.getElement(), "jc-sidebar"))
                .map(element -> element.getAttribute("role"))
                .filter(role -> role != null)
                .toList();

        assertThat(roles).doesNotContain("list", "listitem");
    }

    @Test
    @DisplayName("a folded group says what it folds")
    void aGroupIsADisclosureOverSomethingNamed() {
        // Two folders, because one heading over the whole list separates nothing and the column
        // rightly does not draw it.
        MainLayout shell = new MainLayout(
                new FakeDirectory(stored("web-abc", "Comparing build tools", "Web"),
                        stored("tg-abc", "From the phone", "Telegram")),
                new MockEnvironment());

        Element head = byClass(shell.getElement(), "jc-group__head");
        Element items = byClass(shell.getElement(), "jc-group__items");

        assertThat(head.getAttribute("aria-expanded")).isEqualTo("true");
        assertThat(head.getAttribute("aria-controls")).isEqualTo(items.getAttribute("id"));
        assertThat(items.getAttribute("role")).isEqualTo("group");
        assertThat(items.getAttribute("aria-labelledby")).isEqualTo(head.getAttribute("id"));
    }

    // ------------------------------------------------------------------ the session menu

    /**
     * It declared {@code role="menu"} over three {@code role="menuitem"} children and then ignored
     * ArrowDown, ArrowUp and Home in four attempts out of four, which is what a reader told they
     * are in a menu will press. It also nested two menu roles, so the accessibility tree carried an
     * unnamed inner menu level nobody put there on purpose. Half a menu is worse than no menu: the
     * role is a promise about which keys work, and these three buttons only ever answered to Enter
     * and Space - which is exactly what plain buttons answer to.
     */
    @Test
    @DisplayName("the session menu does not promise menu keys it does not answer to")
    void theSessionMenuIsNotHalfAMenu() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);
        // The panel is inside a popover, and Vaadin adds a popover to the UI rather than leaving it
        // under the control it is anchored to - so it is not a descendant of the shell at all.
        Element panel = byClass(ui.getElement(), "jc-menu");

        List<String> roles = Stream.concat(Stream.of(panel), descendants(panel))
                .map(element -> element.getAttribute("role"))
                .filter(role -> role != null)
                .toList();

        assertThat(roles).doesNotContain("menu", "menuitem");
        // The trigger still says something opens, because something does - it just is not a menu.
        assertThat(byClass(shell.getElement(), "jc-user__trigger").getAttribute("aria-expanded"))
                .isEqualTo("false");
        assertThat(byClass(shell.getElement(), "jc-user__trigger").getAttribute("aria-haspopup"))
                .isNotEqualTo("menu");
    }

    /**
     * Opened from the user menu, Escape dropped focus on the body: the control that opened the
     * dialog was a menu item, and the menu item had been destroyed with the menu, so the browser's
     * own restore target no longer existed. Opened with Ctrl/Cmd + / the same dialog returned focus
     * correctly. One dialog, two behaviours, decided by which door the reader came through.
     */
    @Test
    @DisplayName("closing the shortcuts dialog gives focus back to wherever it came from")
    void theShortcutsDialogRestoresFocus() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);

        UiHarness.press(ui.getElement(), "Slash");
        // Opening an overlay is deferred to the client response like everything else, so the queue
        // is drained before looking for it - and drained again below, so the only scripts read
        // after the close are the ones the close produced.
        scripts(ui);
        Dialog help = ui.getChildren()
                .filter(Dialog.class::isInstance)
                .map(Dialog.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ctrl + / opened no dialog"));
        assertThat(help.isOpened()).isTrue();

        help.close();

        assertThat(String.join("\n", scripts(ui))).contains("__jcFocusReturn");
    }

    // ------------------------------------------------------------------ the transcript

    @Test
    @DisplayName("the transcript is a named log, announced politely")
    void theTranscriptIsNamed() {
        ChatView view = new ChatView(new SilentAgent(), new SameThreadExecutor(),
                new EmptyTranscripts(), new ConversationActivity());

        Element transcript = byClass(view.getElement(), "jc-transcript");

        assertThat(transcript.getAttribute("role")).isEqualTo("log");
        assertThat(transcript.getAttribute("aria-live")).isEqualTo("polite");
        // "log" alone is what it was announced as. A region with no name is a region a reader
        // cannot choose to jump to, because there is nothing in the list to choose.
        assertThat(transcript.getAttribute("aria-label")).isEqualTo("Conversation transcript");
    }

    @Test
    @DisplayName("the suggested questions stay buttons rather than becoming list items")
    void theSuggestionsKeepTheirButtonSemantics() {
        ChatView view = new ChatView(new SilentAgent(), new SameThreadExecutor(),
                new EmptyTranscripts(), new ConversationActivity());

        Element starters = byClass(view.getElement(), "jc-starters");
        List<Element> buttons = descendants(starters)
                .filter(element -> element.getClassList().contains("jc-starter"))
                .toList();

        assertThat(starters.getAttribute("role")).isEqualTo("group");
        assertThat(starters.getAttribute("aria-label")).isEqualTo("Suggested questions");
        assertThat(buttons).isNotEmpty();
        assertThat(buttons).allSatisfy(button ->
                assertThat(button.getAttribute("role")).isNull());
    }

    @Test
    @DisplayName("a turn starting and stopping is announced, and the transcript goes quiet while it streams")
    void streamingIsAnnouncedPolitely() {
        ChatView view = new ChatView(new RecordingAgent(), new SameThreadExecutor(),
                new EmptyTranscripts(), new ConversationActivity());
        new UI().add(view);
        Element transcript = byClass(view.getElement(), "jc-transcript");
        Element announcer = byClass(view.getElement(), "jc-announcer");

        assertThat(announcer.getAttribute("role")).isEqualTo("status");
        assertThat(announcer.getAttribute("aria-live")).isEqualTo("polite");
        assertThat(announcer.getClassList()).contains("jc-sr-only");

        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        // Busy while the tokens land, so a screen reader is read the finished answer once instead
        // of a word at a time, and the reader is told a turn has started without losing their place.
        assertThat(transcript.getAttribute("aria-busy")).isEqualTo("true");
        assertThat(announcer.getTextRecursively()).isEqualTo("Answering");

        click(action(view, "Stop responding"));

        assertThat(transcript.getAttribute("aria-busy")).isEqualTo("false");
        assertThat(announcer.getTextRecursively()).isEqualTo("Answer stopped");
    }

    @Test
    @DisplayName("the send control carries the key that works it, in both of its two jobs")
    void theSendControlNamesItsKey() {
        ChatView view = new ChatView(new RecordingAgent(), new SameThreadExecutor(),
                new EmptyTranscripts(), new ConversationActivity());
        new UI().add(view);

        assertThat(action(view, "Send message").getAttribute("aria-keyshortcuts"))
                .isEqualTo(Shortcut.SEND.ariaKeyShortcuts());

        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));

        assertThat(action(view, "Stop responding").getAttribute("aria-keyshortcuts"))
                .isEqualTo(Shortcut.ESCAPE.ariaKeyShortcuts());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The elements the server has told the browser to focus, oldest first.
     *
     * <p>Focus is the one half of keyboard behaviour that has no server-side state to assert on:
     * {@code focus()} is a queued call into the browser, so the queue is what there is to read.
     * Each call is identified by the class of the element it was made on, which is how the rest of
     * these tests name controls too.
     *
     * @param ui the UI whose queue to drain; draining it empties it, so a test that presses twice
     *           reads twice and sees each move on its own
     * @return one entry per focus call, as the space-joined class list of its target
     */
    private static List<String> focusCalls(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(pending -> pending.getInvocation().getExpression().contains(".focus("))
                .map(pending -> pending.getInvocation().getParameters().stream()
                        .filter(Element.class::isInstance)
                        .map(Element.class::cast)
                        .map(element -> String.join(" ", element.getClassList()))
                        .findFirst()
                        .orElse(""))
                .toList();
    }

    /**
     * The scripts the server has queued for the browser since this was last called.
     *
     * <p>Focus restoration has no server-side state to assert on - it is a call into the browser,
     * so the queue is what there is to read. Draining it is deliberate: a test reads once to
     * discard everything up to the moment it is interested in, then reads again.
     *
     * @param ui the UI whose queue to drain
     * @return one entry per queued script, in the order they will run
     */
    private static List<String> scripts(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(pending -> pending.getInvocation().getExpression())
                .toList();
    }

    private static List<String> rows(Component help) {
        return descendants(help.getElement())
                .filter(element -> element.getClassList().contains("jc-shortcuts__row"))
                .map(row -> row.getChildren()
                        .map(Element::getTextRecursively)
                        .reduce((keys, description) -> keys + " " + description)
                        .orElse(""))
                .toList();
    }

    private static ConversationSummary stored(String id, String title) {
        return stored(id, title, "Web");
    }

    private static ConversationSummary stored(String id, String title, String group) {
        return new ConversationSummary(id, title, title, group, List.of(), Optional.empty(),
                ConversationSummary.Source.STORED);
    }

    private static Element byClass(Element root, String className) {
        return descendants(root)
                .filter(element -> element.getClassList().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with class " + className));
    }

    private static Element byId(Element root, String id) {
        return descendants(root)
                .filter(element -> id.equals(element.getAttribute("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with id " + id));
    }

    private static Element action(ChatView view, String label) {
        return descendants(view.getElement())
                .filter(element -> label.equals(element.getAttribute("aria-label")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No control labelled " + label));
    }

    private static com.vaadin.flow.component.textfield.TextArea composer(ChatView view) {
        return descendants(view.getElement())
                .map(Element::getComponent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(com.vaadin.flow.component.textfield.TextArea.class::isInstance)
                .map(com.vaadin.flow.component.textfield.TextArea.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No composer"));
    }

    private static void click(Element element) {
        element.getComponent()
                .ifPresent(component -> ComponentUtil.fireEvent(component,
                        new ClickEvent<>(component, true, 0, 0, 0, 0, 1, 0, false, false, false, false)));
    }

    private static Stream<Element> descendants(Element root) {
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

    private static final class FakeDirectory implements ConversationDirectory {

        private final List<ConversationSummary> rows;

        private FakeDirectory(ConversationSummary... initial) {
            rows = List.of(initial);
        }

        @Override
        public List<ConversationSummary> conversations() {
            return rows;
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

    /** A transcript store that holds whatever it is given and never refuses a write. */
    private static final class EmptyTranscripts implements ConversationTranscripts {

        private final Map<String, List<TranscriptTurn>> byConversation = new LinkedHashMap<>();

        @Override
        public List<TranscriptTurn> turns(String id) {
            return List.copyOf(byConversation.getOrDefault(id, List.of()));
        }

        @Override
        public Optional<TranscriptTurn> append(String id, ConversationMessage.Role role, String text) {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            List<TranscriptTurn> turns = byConversation.computeIfAbsent(id, key -> new ArrayList<>());
            TranscriptTurn turn = new TranscriptTurn(id + "-" + turns.size(), role, text);
            turns.add(turn);
            return Optional.of(turn);
        }

        @Override
        public Optional<List<TranscriptTurn>> rewindTo(String id, String turnId) {
            return Optional.empty();
        }
    }

    private static final class SilentAgent implements Agent {

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }
    }

    /** Records the question and answers nothing, so the turn stays open for the assertions. */
    private static final class RecordingAgent implements Agent {

        @Override
        public String respondTo(String conversationId, String question) {
            return "";
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            return "";
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }
    }

    private static final class SameThreadExecutor extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
