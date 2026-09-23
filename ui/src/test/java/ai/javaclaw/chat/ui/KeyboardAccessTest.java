package ai.javaclaw.chat.ui;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.ResponseListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.Command;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

    /**
     * That the page behind an open drawer is out of play, all of it.
     *
     * <p>The drawer already behaved as a modal - a scrim over the content, Escape as an exit, and
     * {@code role="dialog"} on the column - and only part of what it covered was taken out of play.
     * {@code inert} went on the content region, which is the transcript and the composer; the bar
     * above them is a sibling of that region, not a child, so the drawer toggle and the theme control
     * stayed focusable and stayed in the accessibility tree the whole time the drawer was open,
     * behind a shade that had already told the reader they were gone.
     *
     * <p>Asserted over everything outside the column rather than over the two controls that happened
     * to be reachable, because the property is that the drawer covers the page, not that it covers
     * the header. A control added to the bar later must not reintroduce this.
     *
     * <p>Fails on the original on both of the bar's controls.
     */
    @Test
    @DisplayName("nothing behind an open drawer can be reached or heard")
    void theDrawerTakesTheWholePageBehindItOutOfPlay() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web-abc", "Comparing build tools")),
                new MockEnvironment());
        ui.add(shell);
        // A phone, which is the one arrangement where the column covers what it navigates.
        UiHarness.resizeTo(ui, 390, 844);

        UiHarness.press(ui.getElement(), "KeyB");

        Element column = byClass(shell.getElement(), "jc-sidebar");
        assertThat(reachableOutside(shell, column))
                .as("controls behind the scrim that a Tab or a screen reader can still reach")
                .isEmpty();
        // And the drawer itself is not caught by its own rule, which would leave nothing reachable
        // anywhere and is the way this fix fails.
        assertThat(inertAbove(shell.getElement(), byClass(column, "jc-search"))).isFalse();
    }

    /**
     * The other half: closing the drawer gives the page back.
     *
     * <p>An inert attribute that is set and never cleared is a worse defect than the one it fixes,
     * because it is silent - the page is simply dead to the keyboard with nothing on screen to say
     * why.
     */
    @Test
    @DisplayName("closing the drawer gives the page behind it back")
    void closingTheDrawerReleasesThePage() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web-abc", "Comparing build tools")),
                new MockEnvironment());
        ui.add(shell);
        UiHarness.resizeTo(ui, 390, 844);

        UiHarness.press(ui.getElement(), "KeyB");
        UiHarness.press(ui.getElement(), "KeyB");

        assertThat(descendants(shell.getElement()).filter(element -> element.hasAttribute("inert")))
                .as("anything still out of play once the drawer has closed")
                .isEmpty();
    }

    /**
     * Every control outside the column that a keyboard or a screen reader could still reach.
     *
     * @param shell  the application shell
     * @param column the conversation column, whose own contents are meant to be reachable
     * @return one description per reachable control, empty when the page behind is properly inert
     */
    private static List<String> reachableOutside(MainLayout shell, Element column) {
        List<Element> insideColumn = descendants(column).toList();
        return descendants(shell.getElement())
                .filter(element -> element != column && !insideColumn.contains(element))
                .filter(KeyboardAccessTest::takesFocus)
                .filter(element -> !inertAbove(shell.getElement(), element))
                .map(element -> "<" + element.getTag() + "> "
                        + String.join(" ", element.getClassList()))
                .toList();
    }

    /**
     * Whether the browser would let focus land on an element.
     *
     * <p>Deliberately generous: anything that declares a non-negative tabindex, and every tag that is
     * focusable without one. A false positive here costs nothing - the whole region behind the drawer
     * is meant to be inert, so a non-control caught by this is still covered - while a false negative
     * would be exactly the hole this test exists to find.
     *
     * @param element the element to judge
     * @return true when a Tab could stop on it
     */
    private static boolean takesFocus(Element element) {
        // A text node has no tag and no attributes, and asking it for either throws.
        if (element.isTextNode()) {
            return false;
        }
        String tabindex = element.getAttribute("tabindex");
        if (tabindex != null) {
            return !tabindex.startsWith("-");
        }
        return List.of("button", "a", "input", "textarea", "select").contains(element.getTag())
                || element.getTag().startsWith("vaadin-");
    }

    /**
     * Whether an element sits inside an inert subtree.
     *
     * @param root    where to stop climbing
     * @param element the element to test, which counts as inert if it carries the attribute itself
     * @return true when the browser would refuse it focus and hide it from assistive technology
     */
    private static boolean inertAbove(Element root, Element element) {
        for (Element at = element; at != null; at = at.getParent()) {
            if (at.hasAttribute("inert")) {
                return true;
            }
            if (at.equals(root)) {
                return false;
            }
        }
        return false;
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
     * A reviewer liked this panel and asked only for the account-identity row the trigger used to
     * show above it to go - an avatar, "This session", "No account needed" - because the
     * application has no accounts behind that row. This pins the split: the trigger renders none of
     * that text and still has a name a screen reader can announce, and the panel it opens still
     * carries all four items - "Settings" joined the other three afterwards, unrelated to this fix.
     */
    @Test
    @DisplayName("the session trigger dropped its identity row but kept its menu")
    void theSessionTriggerIsIconOnly() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);

        Element trigger = byClass(shell.getElement(), "jc-user__trigger");
        assertThat(trigger.getTextRecursively()).doesNotContain("This session", "No account needed");
        assertThat(trigger.getAttribute("aria-label")).isNotBlank();

        // The popover attaches itself to the UI on target attach rather than nesting under the
        // trigger it targets - the same reason the test above reads "jc-menu" from the shell's
        // aria-expanded neighbour rather than from inside it.
        Element panel = byClass(ui.getElement(), "jc-menu");
        List<String> items = descendants(panel)
                .filter(element -> element.getClassList().contains("jc-menu__item"))
                .map(Element::getTextRecursively)
                .toList();
        assertThat(items).containsExactly(
                "Keyboard shortcuts", "Settings", "Open the classic chat", "About JavaClaw");
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

        openShortcuts(ui).close();

        // Through the shell's mechanism rather than through a copy of it. A dialog that reads the
        // remembered element itself has to repeat every check the shell already makes, and the two
        // copies are then free to disagree about what happens when there is nothing remembered -
        // which is the case that dropped the reader on the document body.
        assertThat(String.join("\n", scripts(ui))).contains("__jcRestoreFocus");
    }

    /**
     * Three ways out of the dialog, and the audit found the reader on {@code document.body} after
     * it. Escape and a click on the scrim both arrive at the server as the web component reporting
     * that it closed; the footer control closes it here. If the restore hung off any one of those
     * rather than off the dialog's own state, two of the three exits would be silent about focus
     * and a keyboard-only reader would be back at the top of the page for the rest of the session.
     */
    @Test
    @DisplayName("every way out of the shortcuts dialog hands focus back")
    void everyDismissalOfTheShortcutsDialogRestoresFocus() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);

        // The footer control.
        UiHarness.press(ui.getElement(), "Slash");
        scripts(ui);
        Dialog byButton = openShortcuts(ui);
        click(closeControl(byButton));
        assertThat(byButton.isOpened()).isFalse();
        assertThat(String.join("\n", scripts(ui))).contains("__jcRestoreFocus");

        // Escape, and a click outside: the web component closes itself and reports it, which is the
        // same opened-change this listens on.
        UiHarness.press(ui.getElement(), "Slash");
        scripts(ui);
        Dialog byEscape = openShortcuts(ui);
        dismissFromTheBrowser(byEscape);
        assertThat(byEscape.isOpened()).isFalse();
        assertThat(String.join("\n", scripts(ui))).contains("__jcRestoreFocus");
    }

    /**
     * The reader who has touched nothing yet is the reader most likely to press Ctrl/Cmd + / -
     * they are looking for the keys because they have not started. Nothing had been focused, so
     * nothing was remembered, so closing the dialog restored nothing and left them on the document
     * body: the one entry point whose whole audience is keyboard-only was the one that stranded
     * them. The memory therefore starts pointed at the content landmark rather than at nothing, so
     * there is always somewhere to come back to.
     */
    @Test
    @DisplayName("the focus memory has somewhere to return to before the reader has been anywhere")
    void theFocusMemoryStartsAtTheContent() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);
        Element content = byId(shell.getElement(), "jc-main-content");

        List<Element> seeded = invocations(ui).stream()
                .filter(pending -> pending.getInvocation().getExpression().contains("__jcFocusMemory"))
                .flatMap(pending -> pending.getInvocation().getParameters().stream())
                .filter(Element.class::isInstance)
                .map(Element.class::cast)
                .toList();

        // The element itself, not a selector: a selector would be a second place for the landmark's
        // id to be written down, and the one that nothing checks.
        assertThat(seeded).contains(content);
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

    // ------------------------------------------------------------------ the caret when a turn ends

    /** The fact the browser reported is true, read as a word so the call site says which way. */
    private static final boolean YES = true;

    /** The fact the browser reported is false. */
    private static final boolean NO = false;

    /**
     * Every fact the rule reads is a fact these tests can answer with, and no more.
     *
     * <p>The starting point below is written by hand, so a fact added to the rule would default to
     * whatever {@code CaretPlace} denies with and every test here would go on passing while covering
     * one case fewer than it claims to. Enumerating the record is what makes that a failure. The
     * count is asserted in both directions for the same reason: a fact removed from the rule but
     * left in the report is a name nothing reads any more, and the next person to change the script
     * would keep it alive on the strength of this file mentioning it.
     */
    @Test
    @DisplayName("the report these tests answer with carries exactly the facts the rule reads")
    void theReportTheseTestsAnswerWithCarriesExactlyTheFactsTheRuleReads() {
        ObjectNode baseline = theComposerDroppingTheReader().json();

        assertThat(Stream.of(CaretPlace.class.getRecordComponents()))
                .allSatisfy(fact -> assertThat(baseline.has(fact.getName()))
                        .as("the tests can report " + fact.getName())
                        .isTrue());
        assertThat(baseline.size())
                .as("the tests report nothing the rule does not read")
                .isEqualTo(CaretPlace.class.getRecordComponents().length);
    }

    /**
     * The one arrangement in which the composer is allowed to take the caret.
     *
     * <p>It is the arrangement the composer itself creates. {@code updateSendEnabled} disables the
     * send control the moment a turn ends with an empty field, which drops whoever had tabbed to
     * Stop; the caret is then on nothing, the last place the reader chose is the control that has
     * just died, and no other region on the page has lost anything. That is the only case in which
     * moving the caret gives something back instead of taking it away.
     */
    @Test
    @DisplayName("the composer takes the caret back when the composer is why nobody has it")
    void theComposerTakesTheCaretBackWhenItIsWhyNobodyHasIt() {
        assertThat(theComposerDroppingTheReader().decoded().invitesTheCaretBack())
                .as("a reader dropped by the composer's own housekeeping is given the caret back")
                .isTrue();
    }

    /**
     * The defect itself, stated as a rule.
     *
     * <p>A reader on a control that still works is a reader using it, wherever it is - a row in the
     * conversation column, a control inside the answer that just landed, the composer's own field
     * with a half-written follow-up in it. A model running out of tokens is not a reason to overrule
     * any of that. This is WCAG 2.1 SC 3.2.1 and it is the line the original {@code prompt.focus()}
     * crossed on every single turn.
     *
     * <p>One fact separates this from the case above: the caret is on an element rather than on a
     * document root, and nothing has taken that element out of service. That is the whole of what
     * "somebody is using the page" means here, and inverting it is the single most damaging thing
     * that can be done to this rule.
     */
    @Test
    @DisplayName("a reader still holding the caret is left holding it")
    void aReaderStillHoldingTheCaretIsLeftAlone() {
        CaretPlace place = theComposerDroppingTheReader().but("caretOnRoot", NO).decoded();

        assertThat(place.invitesTheCaretBack())
                .as("the caret is taken off a reader who was using it")
                .isFalse();
    }

    /**
     * A control that has left the document holds nobody, wherever the browser left the caret.
     *
     * <p>The transcript is rebuilt around a turn as it ends, and a reader can be standing on a
     * control inside it when it goes. Some browsers move the caret to the body and some leave
     * {@code document.activeElement} pointing at a node that is no longer in the tree; the second is
     * a reader holding nothing while every naive test says otherwise.
     *
     * <p>Delete {@code caretConnected} from the rule and this reader never gets the caret back.
     */
    @Test
    @DisplayName("a caret left on an element removed from the page is nobody using the page")
    void aCaretOnADetachedElementIsNobodyUsingThePage() {
        CaretPlace place = theComposerDroppingTheReader()
                .but("caretOnRoot", NO)
                .but("caretConnected", NO)
                .decoded();

        assertThat(place.invitesTheCaretBack())
                .as("a caret on a node that has left the document is read as somebody working")
                .isTrue();
    }

    /**
     * The case this whole call was kept for, and the one a browser was measured to produce.
     *
     * <p>A reader tabs to Stop and waits. The turn ends, {@code updateSendEnabled} disables that
     * control, and Chromium 136 does not blur them: the control stays {@code document.activeElement}
     * carrying {@code disabled}. Every other fact reads exactly like a reader who is busy.
     *
     * <p>Delete {@code caretDisabled} from the rule and that reader is stranded on a dead button for
     * the rest of the session, which is the defect deleting {@code prompt.focus()} outright would
     * have left open.
     */
    @Test
    @DisplayName("a caret left on a disabled control is nobody using the page")
    void aCaretOnADisabledControlIsNobodyUsingThePage() {
        CaretPlace place = theComposerDroppingTheReader()
                .but("caretOnRoot", NO)
                .but("caretDisabled", YES)
                .decoded();

        assertThat(place.invitesTheCaretBack())
                .as("a caret on a control that has stopped working is read as somebody working")
                .isTrue();
    }

    /**
     * The same case for a control that is disabled without being natively disabled.
     *
     * <p>A custom element carrying a {@code disabled} attribute is not a disabled form control as
     * far as the platform is concerned, and Vaadin marks such a control {@code aria-disabled} as
     * well. Asking only the property would read one of the two shapes as a working control.
     *
     * <p>Delete {@code caretAriaDisabled} from the rule and the reader is stranded exactly as above,
     * on whichever controls this application happens to build that way.
     */
    @Test
    @DisplayName("a caret left on an aria-disabled control is nobody using the page")
    void aCaretOnAnAriaDisabledControlIsNobodyUsingThePage() {
        CaretPlace place = theComposerDroppingTheReader()
                .but("caretOnRoot", NO)
                .but("caretAriaDisabled", YES)
                .decoded();

        assertThat(place.invitesTheCaretBack())
                .as("a caret on a control marked disabled to assistive technology reads as working")
                .isTrue();
    }

    /**
     * Selection is reading, and focusing anything ends it.
     *
     * <p>Dragging across an answer to copy it leaves the caret nowhere in particular, so every other
     * fact here reads as a reader who has been dropped. They have not: they are halfway through the
     * one gesture on this page that a focus call silently destroys.
     */
    @Test
    @DisplayName("a reader halfway through selecting an answer keeps their selection")
    void aReaderMidSelectionIsLeftAlone() {
        CaretPlace place = theComposerDroppingTheReader().but("selectionCollapsed", NO).decoded();

        assertThat(place.invitesTheCaretBack())
                .as("a selection in progress is collapsed by a caret nobody asked to move")
                .isFalse();
    }

    /**
     * The case the first version of this rule got wrong, and could not even express.
     *
     * <p>Focus that goes nowhere at all. The reader is on a control inside the composer and clicks
     * on something the browser refuses to focus, or the page itself loses focus: the {@code focusout}
     * carries {@code relatedTarget === null}, the memory still names the control they left, and the
     * caret ends up on the document body. Every remaining fact is then identical to the reader who
     * was dropped by the send control being disabled - same loose caret, same last choice, same
     * region - and the two want opposite answers. Only the departure tells them apart, so the
     * departure is recorded as it happens.
     *
     * <p>This is the arrangement in which four clauses of the rule say yes and only
     * {@code readerSteppedAway} says no, which is the whole of that fact's justification. It is not
     * the case of a reader clicking a paragraph of the answer: the transcript carries
     * {@code tabindex="0"}, so that click focuses the transcript, and that reader is turned away by
     * {@code lastChoiceInRegion} instead - see the test below.
     */
    @Test
    @DisplayName("a reader who let the caret go is not dragged back to the composer")
    void aReaderWhoLetTheCaretGoIsNotDraggedBack() {
        CaretPlace place = theComposerDroppingTheReader().but("readerSteppedAway", YES).decoded();

        assertThat(place.invitesTheCaretBack())
                .as("a reader who walked away from the composer is pulled back into it")
                .isFalse();
    }

    /**
     * A loose caret is not the composer's to claim just because it is loose.
     *
     * <p>A control in the transcript or the column can be removed under a reader standing on it, and
     * the browser answers that by putting them on the body. They have been dropped, but not by this
     * composer, and a composer that answers every stranded caret anywhere on the page is a composer
     * that steals focus on events it had nothing to do with. The reader who clicked a paragraph of
     * the answer arrives here too: the click focuses the transcript, which moves the memory out of
     * the composer.
     */
    @Test
    @DisplayName("a caret stranded where the reader was working elsewhere stays where it is")
    void aCaretStrandedElsewhereStaysThere() {
        CaretPlace place = theComposerDroppingTheReader().but("lastChoiceInRegion", NO).decoded();

        assertThat(place.invitesTheCaretBack())
                .as("the composer claims a caret that was lost somewhere else entirely")
                .isFalse();
    }

    /**
     * Nothing to focus is nothing to focus.
     *
     * <p>A turn can finish after the reader has navigated away - the answer arrives on a worker and
     * the view it belongs to is already off the page. Focusing a detached field moves no caret and
     * tells nobody anything; it is only a queued call into a browser that has moved on.
     */
    @Test
    @DisplayName("a composer no longer on the page is not somewhere to send anyone")
    void aComposerOffThePageIsNotSomewhereToSendAnyone() {
        CaretPlace place = theComposerDroppingTheReader().but("regionAttached", NO).decoded();

        assertThat(place.invitesTheCaretBack())
                .as("the caret is sent to a composer that has left the document")
                .isFalse();
    }

    /**
     * No answer is not a yes.
     *
     * <p>The question can go unanswered in more ways than it can be answered: a view routed without
     * the shell, which is what every test of this view on its own builds, leaves the rule undefined
     * and the script returns null. So does a browser that hands back something that is not an object
     * at all. Every one of those is a reader who might be in the middle of something, and the only
     * safe reading of a fact nobody supplied is the one that leaves them alone.
     */
    @Test
    @DisplayName("an answer the browser never gave moves nothing")
    void anAnswerTheBrowserNeverGaveMovesNothing() {
        assertThat(CaretPlace.reportedBy(null).invitesTheCaretBack())
                .as("nothing came back at all")
                .isFalse();
        assertThat(CaretPlace.reportedBy(NullNode.getInstance()).invitesTheCaretBack())
                .as("the script returned null, which is what a page with no shell returns")
                .isFalse();
        assertThat(CaretPlace.reportedBy(BooleanNode.TRUE).invitesTheCaretBack())
                .as("something came back that is not a set of facts")
                .isFalse();
    }

    /**
     * A fact that went missing must not read as the permissive one.
     *
     * <p>The two sides of this question are a Java record and a script in another file, and they
     * agree by name alone. A name that stops matching - a rename on one side, a browser too old for
     * something the script reads - turns a fact into an absence, and an absence that defaulted to
     * false would make "nobody is using the caret" the answer to a question nobody managed to ask.
     * Each fact therefore defaults to whichever value denies.
     */
    @Test
    @DisplayName("an answer missing the facts it was asked for moves nothing")
    void anAnswerMissingTheFactsItWasAskedForMovesNothing() {
        assertThat(CaretPlace.reportedBy(JacksonUtils.createObjectNode()).invitesTheCaretBack())
                .as("an object with none of the facts in it")
                .isFalse();

        ObjectNode wrongTypes = JacksonUtils.createObjectNode();
        Stream.of(CaretPlace.class.getRecordComponents())
                .forEach(fact -> wrongTypes.put(fact.getName(), "yes"));

        assertThat(CaretPlace.reportedBy(wrongTypes).invitesTheCaretBack())
                .as("an object whose facts are not booleans")
                .isFalse();
    }

    /**
     * A fact that did not arrive as a boolean did not arrive, including when Jackson would happily
     * pretend otherwise.
     *
     * <p>The test above spoils every fact with the string {@code "yes"}, which Jackson refuses to
     * read as a boolean anyway - so it passes whether {@code CaretPlace} checks the type or not, and
     * it left the type check itself uncovered. {@code "true"} and {@code 1} are the values that
     * separate the two: {@code asBoolean} converts both, {@code isBoolean} rejects both. Rewriting
     * the guard to coerce was the one mutation of this rule the suite did not notice.
     *
     * <p>It matters because coercion is silent and one-directional. Everything that could damage
     * this report - a browser too old for a property, a shell half-installed, a name that stopped
     * matching - produces a value that is absent or of some other type, and a guess made from one of
     * those is a guess about whether somebody is using the page. The arrangement re-spelled here is
     * deliberately the permissive one, so a decoder that coerced would hand the composer the caret
     * on exactly the reports it understands least.
     */
    @Test
    @DisplayName("a fact spelled as anything but a boolean is a fact that did not arrive")
    void aFactSpelledAsAnythingButABooleanDidNotArrive() {
        assertThat(theComposerDroppingTheReader().decoded().invitesTheCaretBack())
                .as("the arrangement being re-spelled is one that does invite the caret back")
                .isTrue();

        assertThat(CaretPlace.reportedBy(spelled(fact -> fact ? "true" : "false"))
                        .invitesTheCaretBack())
                .as("the same facts as the strings \"true\" and \"false\"")
                .isFalse();

        assertThat(CaretPlace.reportedBy(spelled(fact -> fact ? 1 : 0)).invitesTheCaretBack())
                .as("the same facts as the numbers 1 and 0")
                .isFalse();
    }

    /**
     * The permissive report with every fact written in some other JSON type.
     *
     * <p>Derived from {@link #theComposerDroppingTheReader} rather than written out again so that a
     * fact added to the rule is re-spelled too, instead of quietly staying a boolean and keeping
     * this green.
     *
     * @param spelling how to write one boolean; whatever it returns is put on the node by its
     *                 runtime type, so a {@link String} lands as a JSON string and an
     *                 {@link Integer} as a JSON number
     * @return a fresh report carrying every fact under its own name and none of them as booleans
     */
    private static ObjectNode spelled(Function<Boolean, Object> spelling) {
        ObjectNode booleans = theComposerDroppingTheReader().json();
        ObjectNode respelled = JacksonUtils.createObjectNode();
        booleans.properties().forEach(fact -> {
            Object written = spelling.apply(fact.getValue().booleanValue());
            if (written instanceof Integer number) {
                respelled.put(fact.getKey(), number);
            } else {
                respelled.put(fact.getKey(), String.valueOf(written));
            }
        });
        return respelled;
    }

    /**
     * The model finishing is not something the reader did, and the caret is not the server's to
     * move.
     *
     * <p>A completed turn called {@code focus()} on the composer unconditionally, from a worker
     * thread, at a moment nobody chose. What is asserted is that the server now commands nothing on
     * its own, because a focus call queued for the browser <em>is</em> a caret that moves: there is
     * no such thing as a focus call the reader does not feel.
     */
    @Test
    @DisplayName("an answer finishing on its own never takes the caret off the reader")
    void aFinishedAnswerCommandsNoCaretMove() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        aTurnUnderWay(ui, agent);
        // Whatever sending queued is not what this is about; draining leaves only the end of the
        // turn behind.
        focusTargets(ui);

        agent.complete();

        assertThat(focusTargets(ui))
                .as("controls the server told the browser to focus when the answer finished")
                .isEmpty();
    }

    /**
     * The rule reaching the caret, rather than the rule on its own.
     *
     * <p>The two tests above and below it are about the decision; this is about the wire. A view
     * that asked the right question, applied the right rule and then never acted on the answer
     * would satisfy every assertion about {@link CaretPlace} and leave a keyboard reader stranded
     * on the body for the rest of the session.
     */
    @Test
    @DisplayName("a caret the end of a turn left nowhere comes back to the composer")
    void aStrandedCaretComesBackToTheComposer() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        ChatView view = aTurnUnderWay(ui, agent);
        focusTargets(ui);

        agent.complete();
        answerWhereTheCaretIs(ui, theComposerDroppingTheReader());

        assertThat(focusTargets(ui)).containsExactly(composer(view).getElement());
    }

    /**
     * And the same wire carrying a no.
     *
     * <p>A view that ignored the answer and focused anyway would pass the test above. This is the
     * half that says the answer is obeyed rather than merely collected.
     */
    @Test
    @DisplayName("a reader who has moved on keeps the caret where they put it")
    void aReaderWhoHasMovedOnKeepsTheCaret() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        aTurnUnderWay(ui, agent);
        focusTargets(ui);

        agent.complete();
        answerWhereTheCaretIs(ui, theComposerDroppingTheReader().but("caretOnRoot", NO));

        assertThat(focusTargets(ui))
                .as("controls the server focused after the browser said the reader was elsewhere")
                .isEmpty();
    }

    /**
     * Which element the question is asked about, which is not a detail.
     *
     * <p>{@code lastChoiceInRegion} is measured against whatever element the question carries, and
     * the reader this whole branch exists for was standing on the send control when it was disabled.
     * Ask about the field instead of the composer and that reader falls outside the region, the
     * answer flips to no, and the one case the call was kept for dies - silently, with every other
     * test here still green because they all supply the answer themselves.
     *
     * <p>So the region is asserted by what it has to contain rather than by name: both halves of the
     * composer, because both are places the reader can legitimately be sitting when a turn ends.
     */
    @Test
    @DisplayName("the caret question is asked about the whole composer, not just the field")
    void theCaretQuestionIsAskedAboutTheWholeComposer() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        ChatView view = aTurnUnderWay(ui, agent);
        invocations(ui);

        agent.complete();

        Element field = composer(view).getElement();
        // Named after the turn ends, because for the length of a turn this control is Stop.
        Element sendControl = action(view, "Send message");
        Element region = caretQuestion(ui).getInvocation().getParameters().stream()
                .filter(Element.class::isInstance)
                .map(Element.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The caret question named no region"));
        assertThat(descendants(region))
                .as("what the browser is asked to look inside when it locates the reader")
                .contains(field, sendControl);
    }

    /**
     * The seam this fix fails silently across.
     *
     * <p>Every test above supplies the browser's answer itself, so all of them would go on passing
     * with a view asking for facts no script produces and a rule reading names nothing reports. In a
     * browser that is a caret that never comes back, on exactly the path a keyboard reader depends
     * on, with nothing anywhere to say so.
     *
     * <p>The facts are enumerated from the record rather than listed here, so a fact added to the
     * rule without a line in the script is a failing test rather than a field that is quietly always
     * false. Both sides are read from the code that ships - the script the shell installs and the
     * record the view decodes into - because a copy of either would be a third place to rename and
     * the one nothing checks.
     */
    @Test
    @DisplayName("every fact the rule reads is a fact the shell's script reports")
    void theFactsTheRuleReadsAreTheFactsTheShellReports() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        UI shellUi = UiHarness.ui();
        shellUi.add(shell);

        UI viewUi = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        aTurnUnderWay(viewUi, agent);
        scripts(viewUi);

        agent.complete();

        String installed = String.join("\n", scripts(shellUi));
        assertThat(String.join("\n", scripts(viewUi)))
                .as("what the view asks the browser when a turn ends")
                .contains("window.__jcFocusFacts");
        assertThat(installed)
                .as("what the shell installs for it to ask")
                .contains("window.__jcFocusFacts =");
        assertThat(Stream.of(CaretPlace.class.getRecordComponents()))
                .allSatisfy(fact -> assertThat(installed)
                        .as("the shell's script reports " + fact.getName())
                        .contains(fact.getName() + ":"));
    }

    /**
     * Which browser observation each fact is computed from, which is the half a name check misses.
     *
     * <p>The test above proves every fact is <em>mentioned</em> by the script. That is satisfied by
     * a script that mentions them and reports nothing: a fact pinned to a constant is a well-named
     * fact, passes a name check, and reinstates the defect this whole path exists to close. Two such
     * pinnings were demonstrated as silent regressions against an earlier version of this file.
     *
     * <p>So each fact is required to be computed from the one thing in the browser it claims to be
     * about, and the mapping below is the contract. Every fact names a single browser observation,
     * which is what makes this check worth anything: the composition that used to sit in the script
     * - four clauses deciding whether the caret was held - is now on the server in
     * {@link CaretPlace}, where each clause is a case with a test of its own above. What is left
     * here is one property read per fact, and the mapping says which property.
     *
     * <p>This is a claim about the script's text and not about what a browser does with it, which
     * is the limit of what a test on this side can reach; {@code CaretPlace} says in full what that
     * leaves uncovered and what was observed in a browser instead.
     */
    @Test
    @DisplayName("each fact is computed from the browser observation it names")
    void eachFactIsComputedFromTheObservationItNames() {
        Map<String, String> observations = Map.of(
                "regionAttached", "region.isConnected",
                "caretConnected", "document.activeElement?.isConnected",
                "caretOnRoot", "document.activeElement === document.body",
                "caretDisabled", "document.activeElement?.disabled",
                "caretAriaDisabled", "aria-disabled",
                "selectionCollapsed", "window.getSelection()?.isCollapsed",
                "lastChoiceInRegion", "window.__jcFocusReturn",
                "readerSteppedAway", "window.__jcFocusGivenUp");

        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        UI shellUi = UiHarness.ui();
        shellUi.add(shell);
        String installed = String.join("\n", scripts(shellUi));

        assertThat(Stream.of(CaretPlace.class.getRecordComponents()))
                .allSatisfy(fact -> {
                    String observation = observations.get(fact.getName());
                    assertThat(observation)
                            .as("this test knows which observation " + fact.getName() + " is about")
                            .isNotNull();
                    assertThat(factExpression(installed, fact.getName()))
                            .as(fact.getName() + " is read from " + observation)
                            .contains(observation);
                });
    }

    // -------------------------------------------------------- the caret when the reader navigates

    /**
     * Opening another conversation is the reader working in the list, not asking for the composer.
     *
     * <p>{@code openConversation} stops whatever is streaming, because the remaining tokens belong
     * to the thread being left. Stopping used to mean the caret came back to the composer, which is
     * right when the reader pressed Stop and wrong here: they clicked a row, they are still in the
     * column, and the next Down arrow was going to take them to the row below. Instead the caret
     * jumped the page - but only when something happened to be streaming, so the same click behaved
     * two different ways depending on the model's timing.
     *
     * <p>{@code enterNewConversation} states the rule this restores, in this file: "Choosing a
     * conversation must leave the caret in the list the reader is browsing."
     */
    @Test
    @DisplayName("opening another conversation mid-answer leaves the caret in the list")
    void openingAnotherConversationMidAnswerLeavesTheCaretInTheList() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        ChatView view = aTurnUnderWay(ui, agent);
        focusTargets(ui);

        view.openConversation(stored("web-abc", "Comparing build tools"));

        assertThat(focusTargets(ui))
                .as("controls the server focused when the reader picked a row in the list")
                .isEmpty();
    }

    /**
     * The same act, arriving inside the round trip the caret question takes.
     *
     * <p>Asking the browser where the caret is costs a request out and an answer back, and the
     * reader keeps working for the whole of it. A row clicked in that window reaches the server
     * first: {@code openConversation} runs, the transcript is replaced, the reader is now in the
     * column - and only then do the facts land, describing a page that stopped existing before they
     * arrived. Acting on them focuses the composer, which is the violation this element was written
     * to close, on the exact act the test above makes safe. The window is a network round trip and
     * clicking a row the moment an answer lands is ordinary behaviour, not an exotic one.
     *
     * <p>What the browser answers here is deliberately the one arrangement that invites the caret
     * back, because a guard that only refuses answers that were already going to refuse proves
     * nothing.
     *
     * <p>This closes the half of the window the server can see. A Tab pressed in the same window
     * never reaches the server at all and remains outside what any rule here can know.
     */
    @Test
    @DisplayName("a conversation opened while the caret question is in flight keeps the caret")
    void aConversationOpenedWhileTheCaretQuestionIsInFlightKeepsTheCaret() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        ChatView view = aTurnUnderWay(ui, agent);
        focusTargets(ui);

        agent.complete();
        view.openConversation(stored("web-abc", "Comparing build tools"));
        answerWhereTheCaretIs(ui, theComposerDroppingTheReader());

        assertThat(focusTargets(ui))
                .as("controls focused on facts gathered before the reader picked a row")
                .isEmpty();
    }

    /**
     * The other half, without which the fix above is a regression rather than a fix.
     *
     * <p>Pressing Stop <em>is</em> the reader asking, and the control they pressed is about to stop
     * being the thing they pressed - it turns back into Send, and with an empty field it is
     * disabled outright. Leaving the caret on it would strand them on a dead button. So this path
     * keeps the unconditional focus call that the turn-completion path had to give up, and the
     * difference between the two is exactly who asked.
     */
    @Test
    @DisplayName("pressing Stop brings the caret back to the composer")
    void pressingStopBringsTheCaretBack() {
        UI ui = uiThatRunsWhatItIsHanded();
        ControllableAgent agent = new ControllableAgent();
        ChatView view = aTurnUnderWay(ui, agent);
        focusTargets(ui);

        click(action(view, "Stop responding"));

        assertThat(focusTargets(ui))
                .as("the caret after the reader pressed Stop")
                .containsExactly(composer(view).getElement());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One fact's value expression, lifted out of the script the shell installs.
     *
     * <p>Facts are written one per line where they fit and wrapped where they do not, so this
     * follows the wrap rather than requiring the script to be laid out for the convenience of a
     * test: a fact runs from its own name to the line ending in the comma that closes it, or to the
     * line closing the object for the last one. An earlier version read the first line only, which
     * silently truncated every wrapped fact and would have let a dropped clause through on any of
     * the three that no longer fit.
     *
     * <p>It is a text lookup and not a parser, and it is deliberately literal: it knows the shape
     * this one object is written in and nothing else. A fact reformatted past that shape fails the
     * assertion reading it, which is the safe direction - the alternative is a lookup that quietly
     * matches too much and reports success for a script it never found.
     *
     * @param script the installed script, as the shell queued it
     * @param name   the fact to find, which is also its record component's name
     * @return everything the script assigns to that fact, with wrapped lines joined by single
     *         spaces and any trailing comma removed; never null, never empty
     * @throws AssertionError when no line assigns that fact, which means the script and the record
     *                        have drifted apart and nothing else in this file would say so
     */
    private static String factExpression(String script, String name) {
        List<String> lines = script.lines().map(line -> line.strip()).toList();
        int assigned = -1;
        for (int at = 0; at < lines.size() && assigned < 0; at++) {
            if (lines.get(at).startsWith(name + ":")) {
                assigned = at;
            }
        }
        if (assigned < 0) {
            throw new AssertionError("No line of the script assigns " + name);
        }
        StringBuilder expression =
                new StringBuilder(lines.get(assigned).substring(name.length() + 1).strip());
        for (int at = assigned; !lines.get(at).endsWith(","); ) {
            at++;
            if (at >= lines.size() || lines.get(at).startsWith("})")) {
                break;
            }
            expression.append(' ').append(lines.get(at));
        }
        String whole = expression.toString().strip();
        return whole.endsWith(",") ? whole.substring(0, whole.length() - 1).strip() : whole;
    }

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
        return focusTargets(ui).stream()
                .map(element -> String.join(" ", element.getClassList()))
                .toList();
    }

    /**
     * The same queue read as elements rather than as names.
     *
     * <p>The composer's field carries no class of its own - it is a Vaadin text area styled through
     * its parent - so naming it is not available to the tests about the caret at the end of a turn,
     * and identity is what they have instead. Reading the element also states the assertion those
     * tests want: not that something with the right name was focused, but that this control was.
     *
     * @param ui the UI whose queue to drain; draining it empties it, so each call reports one visit
     * @return the elements the server told the browser to focus, oldest first, possibly empty
     */
    private static List<Element> focusTargets(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .filter(pending -> pending.getInvocation().getExpression().contains(".focus("))
                .flatMap(pending -> pending.getInvocation().getParameters().stream()
                        .filter(Element.class::isInstance)
                        .map(Element.class::cast)
                        .limit(1))
                .toList();
    }

    /**
     * Answers the question the view puts to the browser about where the caret is.
     *
     * <p>The view does not decide on its own whether it may have the caret back - it cannot, because
     * the server has no idea where focus is - so it asks for what the browser can see, and the rule
     * runs on this side when the facts come back. This is the browser reporting them.
     *
     * @param ui       the UI waiting on the answer; its queue is drained
     * @param reported what the browser found, in the vocabulary the script reports and
     *                 {@link CaretPlace} decodes
     * @throws AssertionError when the server asked nothing
     */
    private static void answerWhereTheCaretIs(UI ui, Reported reported) {
        caretQuestion(ui).complete(reported.json());
    }

    /**
     * The one queued script that is the caret question, singled out from everything else in flight.
     *
     * <p>Completing every subscribed invocation would be simpler and wrong: a turn ending queues
     * other questions with other answer types, and handing one of those a set of caret facts fails
     * inside Jackson rather than where the mistake is.
     *
     * @param ui the UI whose queue to drain; draining it empties it
     * @return the pending question about the caret
     * @throws AssertionError when the view asked nothing, or asked without subscribing to the answer
     */
    private static PendingJavaScriptInvocation caretQuestion(UI ui) {
        PendingJavaScriptInvocation asked = invocations(ui).stream()
                .filter(pending -> pending.getInvocation().getExpression().contains("__jcFocusFacts"))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("The server never asked the browser where the caret is"));
        if (!asked.isSubscribed()) {
            throw new AssertionError("The server asked where the caret is and ignored the answer");
        }
        return asked;
    }

    /**
     * The browser's report of the one arrangement in which the composer may take the caret.
     *
     * <p>Every test about the rule starts here and spoils exactly one fact, which is what makes each
     * of them a statement about that fact rather than about a vector of eight. The arrangement is the
     * one the composer creates for itself: a reader was in the composer, the turn ended, the send
     * control was disabled under them, and the browser put them on the document body. Nobody else on
     * the page has lost anything.
     *
     * <p>Written out by hand rather than derived from the record, because a report generated from
     * the thing it is meant to check would agree with it by construction. What keeps the two in step
     * is {@code theReportTheseTestsAnswerWithCarriesExactlyTheFactsTheRuleReads}, which fails on any
     * fact this does not carry and on any fact it carries that the rule does not read.
     *
     * @return a fresh report every call, because {@link Reported#but} writes into it
     */
    private static Reported theComposerDroppingTheReader() {
        ObjectNode facts = JacksonUtils.createObjectNode();
        facts.put("regionAttached", true);
        facts.put("caretConnected", true);
        facts.put("caretOnRoot", true);
        facts.put("caretDisabled", false);
        facts.put("caretAriaDisabled", false);
        facts.put("selectionCollapsed", true);
        facts.put("lastChoiceInRegion", true);
        facts.put("readerSteppedAway", false);
        return new Reported(facts);
    }

    /**
     * A view with a question sent and an answer still arriving.
     *
     * <p>Four tests need the same three lines of setup before they can end a turn, and the setup is
     * not what any of them is about.
     *
     * @param ui    the UI to attach the view to
     * @param agent the agent that will hold the turn open until the test closes it
     * @return the attached view, mid-turn
     */
    private static ChatView aTurnUnderWay(UI ui, ControllableAgent agent) {
        ChatView view = new ChatView(agent, new SameThreadExecutor(), new EmptyTranscripts(),
                new ConversationActivity());
        ui.add(view);
        composer(view).setValue("How do they work?");
        click(action(view, "Send message"));
        return view;
    }

    /**
     * A UI that runs what a worker hands it, over a session it can drain its queue through.
     *
     * <p>Both halves are needed to end a turn from a test. The agent's completion arrives on the
     * response executor and is pushed back through {@code UI.access}, which a plain UI in a test
     * queues against a session lock nobody will ever release - so the turn never ends and the
     * assertions are made about a view that is still streaming. And reading what the server queued
     * for the browser goes through the session, so a UI without one throws before anything is
     * queued at all.
     *
     * @return a fresh UI, current, with a stub session behind it
     */
    private static UI uiThatRunsWhatItIsHanded() {
        UI ui = new InlineUi();
        ui.getInternals().setSession(UiHarness.session());
        UI.setCurrent(ui);
        return ui;
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

    /**
     * The scripts the server has queued, as invocations rather than as text.
     *
     * <p>The text alone cannot answer which element a script was handed, and an element handed to a
     * script is the only kind of reference that cannot go stale against a renamed id.
     *
     * @param ui the UI whose queue to drain; draining it empties it
     * @return one entry per queued script, in the order they will run
     */
    private static List<PendingJavaScriptInvocation> invocations(UI ui) {
        ((StateTree) ui.getElement().getNode().getOwner()).runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations();
    }

    /**
     * The shortcuts dialog the shell has just been asked to open.
     *
     * @param ui the UI the dialog added itself to
     * @return the open dialog
     * @throws AssertionError when no dialog was opened, or when the one found is already closed
     */
    private static Dialog openShortcuts(UI ui) {
        Dialog help = ui.getChildren()
                .filter(Dialog.class::isInstance)
                .map(Dialog.class::cast)
                .filter(Dialog::isOpened)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ctrl + / opened no dialog"));
        assertThat(help.isOpened()).isTrue();
        return help;
    }

    /**
     * Closes a dialog the way Escape and a click on the scrim close it.
     *
     * <p>Neither of those reaches the server as a call to {@code close()}. The web component closes
     * itself and reports it afterwards, through the one callable Vaadin publishes for the purpose -
     * so that callable is what is invoked here. Driving the server method instead would assert that
     * a route the reader never takes works, and leave the two they do take untested.
     *
     * @param dialog the open dialog to dismiss
     * @throws AssertionError when Vaadin no longer publishes that callable, which would mean this
     *                        test is no longer simulating anything real
     */
    private static void dismissFromTheBrowser(Dialog dialog) {
        try {
            Method reportedByTheClient = Dialog.class.getDeclaredMethod("handleClientClose");
            reportedByTheClient.setAccessible(true);
            reportedByTheClient.invoke(dialog);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw new AssertionError("Vaadin no longer closes a dialog from the client this way", failed);
        }
    }

    /**
     * The dialog's own way out, which lives in its footer rather than among its children.
     *
     * @param dialog the dialog to look in
     * @return the footer control that closes it
     * @throws AssertionError when the dialog offers no such control, which would leave a pointer
     *                        user with nothing but the scrim
     */
    private static Element closeControl(Dialog dialog) {
        return descendants(dialog.getFooter().getElement())
                .filter(element -> element.getClassList().contains("jc-dialog__close"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The dialog has no close control"));
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
        public Optional<TranscriptTurn> appendFailure(String id, FailureNotice notice) {
            if (notice == null) {
                return Optional.empty();
            }
            List<TranscriptTurn> turns = byConversation.computeIfAbsent(id, key -> new ArrayList<>());
            TranscriptTurn turn = new TranscriptTurn(id + "-" + turns.size(),
                    ConversationMessage.Role.ASSISTANT, notice.sentence(), TranscriptTurn.Outcome.FAILED);
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

    /**
     * What the browser answered when the server asked where the caret is.
     *
     * <p>Written as JSON rather than as a {@link CaretPlace} on purpose. JSON is what actually
     * crosses, so every test that uses this goes through {@code CaretPlace.reportedBy} - the trust
     * boundary, the name matching and the type checking included - instead of past it. A test that
     * built the record directly would keep passing after the two sides stopped agreeing on a name,
     * which is the failure this whole seam is most exposed to.
     *
     * <p>Naming facts as strings is deliberate for the same reason: these are the names the browser
     * uses, and they are only correct if they match. A name that stops matching turns its fact into
     * an absence, {@link CaretPlace} reads an absence as a reason to leave the caret alone, and the
     * test that expects the caret to come back fails - loudly, in the direction that matters.
     */
    private static final class Reported {

        /** The facts, one boolean per name, exactly as the script would have returned them. */
        private final ObjectNode facts;

        /**
         * @param facts the object to report; taken by reference rather than copied, because only
         *              this class and its factory ever write to one
         */
        private Reported(ObjectNode facts) {
            this.facts = facts;
        }

        /**
         * The same report with one fact answered the other way.
         *
         * <p>Refuses a name the report does not already carry, which is the point of it. The
         * alternative would silently add a fact the browser never reports and the rule never reads,
         * and the test asking for it would pass while proving nothing at all.
         *
         * @param fact  the fact to change, which is also a component of {@link CaretPlace}
         * @param value what the browser found instead
         * @return this report, changed in place, so calls can be chained
         * @throws AssertionError when the report carries no such fact
         */
        Reported but(String fact, boolean value) {
            if (!facts.has(fact)) {
                throw new AssertionError("The browser reports no fact called " + fact);
            }
            facts.put(fact, value);
            return this;
        }

        /**
         * @return the facts as the browser would hand them over, never null
         */
        ObjectNode json() {
            return facts;
        }

        /**
         * @return the facts read the way the view reads them, never null; a fact this report does
         *         not carry arrives as whatever {@link CaretPlace} denies with
         */
        CaretPlace decoded() {
            return CaretPlace.reportedBy(facts);
        }
    }

    /**
     * A UI that runs a worker's command where it stands instead of queueing it.
     *
     * <p>Vaadin hands a command from another thread to the session lock, and a test session holds no
     * lock and has nobody to release one - so a turn ended from the response executor would never
     * reach the view. Running it inline is what makes the completion and failure paths reachable at
     * all; everything else about the UI is left alone.
     */
    private static final class InlineUi extends UI {

        @Override
        public Future<Void> access(Command command) {
            command.execute();
            return CompletableFuture.completedFuture(null);
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

    /**
     * An agent that starts a turn and leaves it open until the test says how it ends.
     *
     * <p>{@link RecordingAgent} never closes a turn, which is what the tests about an answer still
     * arriving want. The tests about the caret are about the other end: what a turn does on its way
     * out, on the path the reader did not ask for. That needs the listener kept and called later.
     */
    private static final class ControllableAgent implements Agent {

        /** The listener of the turn currently open, or null before the first question. */
        private ResponseListener listener;

        @Override
        public String respondTo(String conversationId, String question) {
            throw new UnsupportedOperationException("The streaming form is the one under test");
        }

        @Override
        public String respondTo(String conversationId, String question, ResponseListener listener) {
            this.listener = listener;
            return "";
        }

        @Override
        public <T> T prompt(String conversationId, String input, Class<T> result) {
            throw new UnsupportedOperationException("Not asked in these tests");
        }

        /**
         * Ends the open turn the way a provider that finished normally would.
         *
         * @throws AssertionError when nothing has been asked, which would otherwise surface as a
         *                        null dereference several frames away from the mistake
         */
        void complete() {
            if (listener == null) {
                throw new AssertionError("No turn is open: nothing has been asked yet");
            }
            listener.onComplete();
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
