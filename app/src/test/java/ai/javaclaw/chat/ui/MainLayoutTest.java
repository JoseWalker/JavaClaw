package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.RouteRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wiring between the column where a conversation is chosen and the view that shows one.
 *
 * <p>This is the join that was missing, and its absence was not a small thing: the sidebar listed
 * conversations, a click renamed the heading, and the transcript underneath went on showing a
 * different conversation entirely. Every test here is about the same claim - that what the shell
 * says the reader is looking at, and what the view is actually showing, are one conversation.
 */
class MainLayoutTest {

    @Test
    @DisplayName("the shell opens on a new chat, not on somebody's last one")
    void opensOnANewChat() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Yesterday's thread"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();

        shell.showRouterLayoutContent(view);

        // Arriving inside a conversation nobody asked to resume means the first thing typed lands in
        // the middle of it. The stored thread has not gone anywhere - it is the row below.
        assertThat(view.opened).isNotNull();
        assertThat(view.opened.source()).isEqualTo(ConversationSummary.Source.DRAFT);
        assertThat(view.opened.id()).isNotEqualTo("web");
        assertThat(ConversationIds.isWellFormed(view.opened.id())).isTrue();
        assertThat(titles(shell)).contains("Yesterday's thread");
    }

    @Test
    @DisplayName("clicking a conversation hands that conversation to the view")
    void selectionReachesTheView() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Yesterday's thread"),
                stored("web-abc", "Comparing build tools"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);

        click(rowTitled(shell, "Comparing build tools"));

        assertThat(view.opened.id()).isEqualTo("web-abc");
        assertThat(topbarTitle(shell)).isEqualTo("Comparing build tools");
    }

    @Test
    @DisplayName("the heading and the view never name two different conversations")
    void headingFollowsTheView() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Yesterday's thread"),
                stored("web-abc", "Comparing build tools"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);

        click(rowTitled(shell, "Comparing build tools"));
        click(rowTitled(shell, "Yesterday's thread"));

        assertThat(view.opened.id()).isEqualTo("web");
        assertThat(topbarTitle(shell)).isEqualTo("Yesterday's thread");
    }

    @Test
    @DisplayName("a view that cannot show a conversation is hosted without being told about one")
    void toleratesAViewThatIsNotAConversationHost() {
        FakeDirectory directory = new FakeDirectory(stored("web", "Yesterday's thread"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());

        shell.showRouterLayoutContent(new Div());

        // The click must not throw looking for a host that was never there.
        click(rowTitled(shell, "Yesterday's thread"));
        assertThat(topbarTitle(shell)).isEqualTo("Yesterday's thread");
    }

    @Test
    @DisplayName("the name the column is given reaches the bar, not only the row")
    void theBarFollowsTheNameTheColumnShows() {
        FakeDirectory directory = new FakeDirectory();
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String id = view.opened.id();

        // The bar is named from the first question, which is all there is to name it with yet.
        shell.activeConversationTitled("How do I make a Gradle build faster?");
        assertThat(topbarTitle(shell)).isNotEqualTo("New chat");

        // The exchange is written and the namer answers with something the derivation could not
        // produce. One conversation cannot be called two things at once: whatever the column shows
        // is what the reader will look for in it, so the bar has to be showing the same string.
        directory.put(stored(id, "Speeding up a Gradle build"));
        shell.activeConversationUpdated();

        assertThat(topbarTitle(shell)).isEqualTo("Speeding up a Gradle build");
        assertThat(titles(shell)).contains("Speeding up a Gradle build");
    }

    @Test
    @DisplayName("a renamed conversation keeps its new name when the reader comes back to it")
    void theAdoptedNameSurvivesAReselection() {
        FakeDirectory directory = new FakeDirectory(stored("web-other", "Another thread"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String id = view.opened.id();

        shell.activeConversationTitled("How do I make a Gradle build faster?");
        directory.put(stored(id, "Speeding up a Gradle build"));
        shell.activeConversationUpdated();

        // Leaving and returning must not restore the name the shell was carrying before the rename:
        // a cached summary that was never updated is how the bar ends up disagreeing with the column
        // again one click later.
        click(rowTitled(shell, "Another thread"));
        click(rowTitled(shell, "Speeding up a Gradle build"));

        assertThat(topbarTitle(shell)).isEqualTo("Speeding up a Gradle build");
        assertThat(view.opened.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("the persistent chrome claims nothing about where the model runs")
    void makesNoLocalityClaimItCannotCheck() {
        // The exact configuration the claim was written against: a model whose own name ends in
        // -cloud, on a screen that also carried the words "Running on this machine".
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.model.chat", "ollama");
        environment.setProperty("spring.ai.ollama.chat.options.model", "gemma4:31b-cloud");
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web", "A thread")), environment);

        String chrome = shell.getElement().getTextRecursively().toLowerCase(Locale.ROOT);

        // Nothing on this screen can tell where inference happens, and this is the one piece of
        // chrome that is on screen the whole time - so a wrong answer here is worse than no answer.
        assertThat(chrome).doesNotContain("this machine", "runs locally", "your machine",
                "on-device", "offline", "stays local");
        // What it may say, it reads from configuration rather than assuming.
        assertThat(chrome).contains("gemma4:31b-cloud");
    }

    @Test
    @DisplayName("the model line says what will answer, not what answered")
    void scopesTheModelLineToThePresent() {
        // The line is read from configuration when the shell is laid out, and it sits directly
        // under the name of the thread on screen - so a bare model name there is a claim about
        // current configuration wearing the costume of a claim about the turns above it. Nothing
        // in this application records which model wrote a given answer, so the honest reading of
        // this line is the only one it is allowed to invite.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.model.chat", "ollama");
        environment.setProperty("spring.ai.ollama.chat.options.model", "gemma4:31b-cloud");
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web", "A thread")), environment);

        String line = subtitle(shell);
        // The present progressive was the defect: byte-identical on an empty chat, on a finished
        // transcript and mid-answer, so a label that reads as a report of what is happening carried
        // no state at all - and over a conversation a different model may have written, it read as a
        // claim about those answers. The tooltip said the opposite of the verb above it, and a
        // tooltip is reachable by neither keyboard nor touch.
        assertThat(line).startsWith("Next answer: ").contains("gemma4:31b-cloud");
        assertThat(line).doesNotContain("Answering");

        // And the scope is spelled out for anyone who stops on it, rather than left to the verb.
        assertThat(subtitleTitle(shell))
                .contains("configured now")
                .contains("Earlier answers");
    }

    // ------------------------------------------------------------ the reader's place

    /**
     * A conversation the reader is in has to be somewhere the browser can come back to.
     *
     * <p>Before this, clicking a row never moved the URL off {@code /ui/}. A behaviour audit put a
     * full answer on screen, pressed reload, and came back to an empty transcript titled "New chat" -
     * the thread was still in the column, but the reader's place was gone, and there was nothing in
     * the address for the second page load to read. The reference product restores the same
     * conversation from {@code /c/<id>} at the same URL.
     *
     * <p>Asserted on what the browser is actually sent rather than on a method having been called:
     * the address only exists if the instruction carrying it reaches the client. Fails on the
     * original, which sends nothing at all.
     */
    @Test
    @DisplayName("choosing a conversation puts it in the address bar")
    void addressesTheChosenConversation() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Yesterday's thread"),
                stored("web-abc", "Comparing build tools"));
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        ui.add(shell);
        shell.showRouterLayoutContent(new RecordingView());

        click(rowTitled(shell, "Comparing build tools"));

        assertThat(addressesSentTo(ui)).contains("c/web-abc");
    }

    /**
     * The other half: a chat nobody has written to yet is not a place, so it is not addressed.
     *
     * <p>An id is minted the moment a reader asks for a new chat, long before anything is stored
     * under it. Addressing it would put a link to an empty thread in the address bar and in the
     * reader's history, for a conversation that will never exist if they navigate away. The address
     * appears when the conversation does - see {@link #theAddressFollowsTheFirstExchange()}.
     */
    @Test
    @DisplayName("a chat with nothing in it is not addressed as though it were a conversation")
    void doesNotAddressAnEmptyChat() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web", "A thread")), new MockEnvironment());
        ui.add(shell);
        shell.showRouterLayoutContent(new RecordingView());

        click(element(shell, "jc-new-chat"));

        assertThat(addressesSentTo(ui)).noneSatisfy(address ->
                assertThat(address).startsWith("c/"));
    }

    @Test
    @DisplayName("a chat acquires its address as soon as its first exchange is written")
    void theAddressFollowsTheFirstExchange() {
        FakeDirectory directory = new FakeDirectory();
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        ui.add(shell);
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String id = view.opened.id();
        addressesSentTo(ui);

        // The exchange lands and the store starts answering for the conversation, which is the
        // moment it stops being a draft and starts being somewhere a reader can return to.
        directory.put(stored(id, "Speeding up a Gradle build"));
        shell.activeConversationUpdated();

        assertThat(addressesSentTo(ui)).contains("c/" + id);
    }

    /**
     * The half a reload depends on: an address that names a conversation opens that conversation,
     * everywhere at once, before the transcript is ever shown.
     *
     * <p>Fails on the original, which does not read the route at all - the shell is not a
     * {@code BeforeEnterObserver} there, so this does not compile against it.
     */
    @Test
    @DisplayName("arriving at a conversation's address opens that conversation")
    void opensTheConversationNamedByTheAddress() {
        FakeDirectory directory = new FakeDirectory(
                stored("web", "Yesterday's thread"),
                stored("web-abc", "Comparing build tools"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();

        shell.beforeEnter(navigationTo("web-abc"));
        shell.showRouterLayoutContent(view);

        assertThat(view.opened.id()).isEqualTo("web-abc");
        assertThat(topbarTitle(shell)).isEqualTo("Comparing build tools");
        assertThat(rowTitled(shell, "Comparing build tools").getAttribute("aria-current"))
                .isEqualTo("true");
    }

    /**
     * A chat whose first exchange has not been written is still the reader's place. The store cannot
     * answer for it, and coming back to a <em>different</em> conversation would be worse than coming
     * back to the empty one they were in.
     */
    @Test
    @DisplayName("an address the store has never heard of still opens the chat it names")
    void opensAnUnwrittenConversationFromItsAddress() {
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web", "A thread")), new MockEnvironment());
        RecordingView view = new RecordingView();

        shell.beforeEnter(navigationTo("web-0123456789ab"));
        shell.showRouterLayoutContent(view);

        assertThat(view.opened.id()).isEqualTo("web-0123456789ab");
    }

    /**
     * A reload that lands on a chat with nothing written in it must not then move the reader off it.
     *
     * <p>The rule that keeps an empty chat out of the address bar is about chats the reader has just
     * started, not about the one the browser is already showing. Applied without that distinction, a
     * reload would arrive at the right conversation and immediately rewrite the URL back to the root
     * - which is the defect this mechanism exists to fix, reintroduced by its own rule.
     */
    @Test
    @DisplayName("a chat opened from its own address keeps that address")
    void keepsTheAddressAnUnwrittenChatArrivedOn() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);
        shell.beforeEnter(navigationTo("web-0123456789ab"));
        shell.showRouterLayoutContent(new RecordingView());

        // Everything the column reports through, which is where the rewrite would have happened.
        shell.activeConversationUpdated();

        assertThat(addressesSentTo(ui)).doesNotContain(".", "");
    }

    /**
     * The conversation the reader is actually in has to be addressed by the fact that it was
     * written, not by the conversation list noticing that it was.
     *
     * <p>An audit asked a question in a new chat, watched the answer arrive, and read
     * {@code location.href} forty-five seconds later still on {@code /ui/} with two turns on screen;
     * a reload then came back to an empty transcript titled "New chat". The thread was in the store
     * - the column had grown by a row - so nothing was lost except the reader's place, which is the
     * one thing an address exists to keep.
     *
     * <p>The store the column reads is not the store the turn was written to, and it catches up on
     * its own schedule. So this stages the state that produced the defect: the conversation is on
     * record and the directory has still never heard of it. The address must not wait for that.
     *
     * <p>Fails on the original twice over: there is no way for the view to say a conversation has
     * been written, and the only path to an address ran through the column reporting a stored row -
     * which is precisely what has not happened here.
     */
    @Test
    @DisplayName("the live conversation is addressed the moment its first turn is written")
    void addressesTheLiveConversationWhenItIsWritten() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String id = view.opened.id();
        addressesSentTo(ui);

        shell.activeConversationTitled("Say the single word: pineapple");
        // The turn is on disk. The column's directory is deliberately never told, because in the
        // running application it is not told either until its own store catches up.
        shell.activeConversationStored(id);

        assertThat(addressesSentTo(ui)).contains("c/" + id);
    }

    /**
     * A report about some other conversation must not move the reader's address.
     *
     * <p>A turn can finish being written after the reader has already clicked away from the thread
     * it belongs to - the view files it under the conversation it was asked of, by design - and an
     * address written from that would put the reader somewhere they had just left.
     */
    @Test
    @DisplayName("a turn written for a conversation the reader has left does not move the address")
    void ignoresAWriteForAConversationTheReaderHasLeft() {
        FakeDirectory directory = new FakeDirectory(stored("web-abc", "Comparing build tools"));
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        ui.add(shell);
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String left = view.opened.id();
        click(rowTitled(shell, "Comparing build tools"));
        addressesSentTo(ui);

        shell.activeConversationStored(left);

        assertThat(addressesSentTo(ui)).isEmpty();
    }

    // ------------------------------------------------------------------ going back

    /**
     * Back has to come back, not walk out.
     *
     * <p>Measured on the running application: {@code history.length} read 2 at load and stayed 2
     * after opening three conversations in turn, because every selection replaced the one entry
     * rather than adding to it. One Back therefore landed on {@code about:blank} with no shell and
     * an empty body - the whole application gone in one keystroke, from a control every browser puts
     * within a thumb's reach. The reference product returns to its own root with the shell alive.
     *
     * <p>Asserted on the instruction the browser is actually sent, and on which of the two history
     * calls it is, because that is the entire difference between an entry the reader can return to
     * and an entry that has been overwritten.
     *
     * <p>Fails on the original, which sends {@code replaceState} for every selection.
     */
    @Test
    @DisplayName("opening another conversation leaves the one before it in the browser's history")
    void pushesAHistoryEntryForEachConversationOpened() {
        FakeDirectory directory = new FakeDirectory(
                stored("web-abc", "Comparing build tools"),
                stored("web-def", "Yesterday's thread"));
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        ui.add(shell);
        shell.showRouterLayoutContent(new RecordingView());
        addressesSentTo(ui);

        click(rowTitled(shell, "Comparing build tools"));
        click(rowTitled(shell, "Yesterday's thread"));

        assertThat(historyCalls(ui))
                .as("each conversation the reader opened is somewhere Back can return to")
                .containsExactly(new HistoryCall(false, "c/web-abc"),
                        new HistoryCall(false, "c/web-def"));
    }

    /**
     * The other half, and the half a naive fix loses: a conversation acquiring its address is not a
     * second place.
     *
     * <p>The chat the reader is in is minted before it is written, so its address appears under them
     * while they sit still. Pushing that would put an entry in the history for the screen they are
     * already looking at, and Back would land them on the same screen with an older URL - which is
     * the reader pressing Back and appearing to go nowhere.
     */
    @Test
    @DisplayName("a chat acquiring its own address replaces the entry it is already on")
    void promotingTheOpenChatDoesNotAddAnEntry() {
        UI ui = UiHarness.ui();
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        ui.add(shell);
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        String id = view.opened.id();
        addressesSentTo(ui);

        shell.activeConversationStored(id);

        assertThat(historyCalls(ui)).containsExactly(new HistoryCall(true, "c/" + id));
    }

    /**
     * Back is a navigation, and a navigation has to reach the transcript.
     *
     * <p>The shell reads the address before the view is shown, which is right on a cold load - the
     * view does not exist yet and is handed the conversation the moment it does. A Back keystroke is
     * not a cold load: the view is already attached and Flow does not rebuild it for a route that
     * resolves to the same component, so nothing hands it anything. Without this the bar and the
     * marked row would follow the address and the messages under them would not, which is the exact
     * disagreement the whole selection path exists to prevent.
     *
     * <p>Fails on the original, where {@code openFromAddress} deliberately never tells the host.
     */
    @Test
    @DisplayName("navigating back to a conversation's address puts that conversation in the view")
    void anAddressReachesAViewThatIsAlreadyOnScreen() {
        FakeDirectory directory = new FakeDirectory(
                stored("web-abc", "Comparing build tools"),
                stored("web-def", "Yesterday's thread"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        click(rowTitled(shell, "Yesterday's thread"));

        // What a popstate delivers: the same view, a different address.
        shell.beforeEnter(navigationTo("web-abc"));

        assertThat(view.opened.id()).isEqualTo("web-abc");
        assertThat(topbarTitle(shell)).isEqualTo("Comparing build tools");
    }

    /**
     * The root is where a new chat lives, so arriving back at it has to mean one.
     *
     * <p>Starting a chat leaves the reader at {@code /ui/}, so that address is a real place in the
     * history and Back can land on it. A navigation that names no conversation used to be ignored
     * outright, which was correct for the one navigation that is not a navigation - the first page
     * load, where the shell has only just built the chat the reader is in - and wrong for every
     * other, where it left a stored conversation on screen underneath a URL that says otherwise.
     */
    @Test
    @DisplayName("navigating back to the root puts the reader in a new chat")
    void theRootIsANewChat() {
        FakeDirectory directory = new FakeDirectory(stored("web-abc", "Comparing build tools"));
        MainLayout shell = new MainLayout(directory, new MockEnvironment());
        RecordingView view = new RecordingView();
        shell.showRouterLayoutContent(view);
        click(rowTitled(shell, "Comparing build tools"));

        shell.beforeEnter(navigationToTheRoot());

        assertThat(view.opened.source()).isEqualTo(ConversationSummary.Source.DRAFT);
        assertThat(view.opened.id()).isNotEqualTo("web-abc");
        assertThat(ConversationIds.isWellFormed(view.opened.id())).isTrue();
        assertThat(topbarTitle(shell)).isEqualTo("New chat");
        // Arriving in a new chat by pressing Back is the reader asking for one as deliberately as
        // pressing the control is, and a new chat that leaves the caret nowhere in silence is the
        // defect that control had to fix once already.
        assertThat(view.entered).isOne();
    }

    /**
     * The exception the rule above is written around: the first page load is also a navigation with
     * no conversation in it, and the shell has already put the reader in a chat by then.
     *
     * <p>Treating it as a request for a new chat would mint a second one before the first had been
     * seen, and - because settling a reader into a chat means putting the caret in the composer and
     * saying so - it would take focus on the first paint of a page nobody had asked anything of yet.
     * That is the same class of defect as the drawer that moved the caret on a remembered reload.
     */
    @Test
    @DisplayName("the root does not restart a chat the reader is already sitting in")
    void theRootKeepsAChatTheReaderIsAlreadyIn() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        RecordingView view = new RecordingView();
        // The cold load: the shell settles the route before the view exists, and the view is handed
        // whatever it settled on.
        shell.beforeEnter(navigationToTheRoot());
        shell.showRouterLayoutContent(view);
        String opened = view.opened.id();
        // Whatever the shell hands the view has to be the chat the column is marking, or the reader
        // is looking at one conversation and the list is pointing at another.
        assertThat(rowTitled(shell, "New chat").getAttribute("aria-current")).isEqualTo("true");
        assertThat(ConversationIds.isWellFormed(opened)).isTrue();

        // The same address arriving again with the reader already in an empty chat - a Forward, a
        // repeated navigation, a client router re-entering the route it is on.
        shell.beforeEnter(navigationToTheRoot());

        assertThat(view.opened.id()).as("the chat the reader is typing in is not thrown away")
                .isEqualTo(opened);
        assertThat(view.entered).as("nobody asked for a new chat, so nothing takes the caret")
                .isZero();
    }

    /**
     * Deny by default at the boundary. A route parameter is whatever was typed into the address bar,
     * and it is about to be handed to a store that turns it into a file name. An id outside the
     * allow-list is refused rather than repaired: a repaired id addresses some other conversation,
     * and the reader would never know they had been moved.
     */
    @Test
    @DisplayName("an address that could not be a conversation opens nothing")
    void refusesAnAddressOfTheWrongShape() {
        MainLayout shell = new MainLayout(new FakeDirectory(stored("web", "A thread")), new MockEnvironment());
        RecordingView view = new RecordingView();

        shell.beforeEnter(navigationTo("../../etc/passwd"));
        shell.showRouterLayoutContent(view);

        assertThat(view.opened.source()).isEqualTo(ConversationSummary.Source.DRAFT);
        assertThat(view.opened.id()).isNotEqualTo("../../etc/passwd");
        assertThat(ConversationIds.isWellFormed(view.opened.id())).isTrue();
        assertThat(topbarTitle(shell)).isEqualTo("New chat");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A navigation to a conversation's address, as the router would deliver it.
     *
     * <p>The parameter is what the shell reads and is passed exactly as given, including the shapes
     * that must be refused. The {@link Location} beside it is only the event's record of where the
     * browser went and is built from the prefix alone, because {@code Location} rejects a path with
     * {@code ..} in it before the shell ever gets a chance to - which would make the refusal test
     * pass for the wrong reason.
     *
     * @param conversationId what the address names, well formed or not
     * @return the event the shell is asked to enter
     */
    private static BeforeEnterEvent navigationTo(String conversationId) {
        return new BeforeEnterEvent(new Router(Mockito.mock(RouteRegistry.class)),
                NavigationTrigger.PAGE_LOAD, new Location(ConversationAddress.PREFIX),
                ChatView.class, new RouteParameters(ConversationAddress.PARAMETER, conversationId),
                UiHarness.ui(), List.of(MainLayout.class));
    }

    /**
     * A navigation to the application's own root, which names no conversation.
     *
     * <p>The same event the first page load delivers and the same one a Back keystroke delivers once
     * a chat has been started; the shell has to tell them apart from what it is holding rather than
     * from anything in the event, because there is nothing in the event to tell them apart by.
     *
     * @return the event the shell is asked to enter
     */
    private static BeforeEnterEvent navigationToTheRoot() {
        return new BeforeEnterEvent(new Router(Mockito.mock(RouteRegistry.class)),
                NavigationTrigger.PAGE_LOAD, new Location(""), ChatView.class,
                RouteParameters.empty(), UiHarness.ui(), List.of(MainLayout.class));
    }

    /**
     * Offline was not a designed state, and the shell is where the design for it has to start.
     *
     * <p>An audit went offline at 1440x900 and watched for fourteen seconds: nothing was sent, and
     * the application said nothing about why. The only thing on screen was Vaadin's own connection
     * indicator, a five-pixel striped band whose sentence is in the DOM and is not painted readably.
     *
     * <p>Two claims, and the state is undesigned without either. There has to be somewhere to say
     * it - present from the first paint and announced politely, because a live region created at the
     * moment it is written to is the one shape of live region that does not work. And the thing that
     * decides when to say it has to be in the browser, because the server is precisely what is
     * unreachable: the shell listens to the browser's own connection events and writes the sentence
     * from there.
     *
     * <p>Fails on the original, which had no such element and installed no such listener.
     */
    @Test
    @DisplayName("the shell has somewhere to say the connection is gone, and a browser-side watch on it")
    void marksTheConnectionState() {
        MainLayout shell = new MainLayout(new FakeDirectory(), new MockEnvironment());
        UI ui = UiHarness.ui();

        ui.add(shell);

        Element strip = element(shell, "jc-connection");
        assertThat(strip.getAttribute("role")).isEqualTo("status");
        assertThat(strip.getAttribute("aria-live")).isEqualTo("polite");

        List<PendingJavaScriptInvocation> installed = scriptsSentTo(ui);
        assertThat(installed)
                .as("the shell listens to the browser for both directions of the transition")
                .anySatisfy(invocation -> assertThat(invocation.getInvocation().getExpression())
                        .contains("'offline'").contains("'online'").contains("navigator.onLine"));
        assertThat(installed.stream()
                .flatMap(invocation -> invocation.getInvocation().getParameters().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast))
                .as("the sentence the strip shows is the shell's own, not a string invented in script")
                .anyMatch(parameter -> parameter.contains("offline"));
    }

    /**
     * Everything the server has asked the browser to run since this was last called.
     *
     * <p>Draining clears the queue, so each call reports one visit.
     *
     * @param ui the UI whose queue to drain
     * @return the pending invocations, in order, possibly empty
     */
    private static List<PendingJavaScriptInvocation> scriptsSentTo(UI ui) {
        StateTree tree = (StateTree) ui.getElement().getNode().getOwner();
        tree.runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations();
    }

    /**
     * The addresses the server has told the browser to show since this was last called.
     *
     * <p>Read off the pending script queue rather than off any field, because an address that never
     * leaves the server is not an address: what makes a conversation reloadable is the instruction
     * arriving at the browser. Draining also clears the queue, so each call reports one visit.
     *
     * @param ui the UI whose queue to drain
     * @return the locations passed to the history call, in order, possibly empty
     */
    private static List<String> addressesSentTo(UI ui) {
        return historyCalls(ui).stream().map(HistoryCall::address).toList();
    }

    /**
     * One instruction to the browser's history, as the two things a test about the reader's place
     * cares about.
     *
     * <p>Which of the two calls it is <em>is</em> the behaviour under test: replacing overwrites the
     * entry the reader is standing on, so Back leaves the application, and pushing adds one, so Back
     * returns to where they were. A test that only checked the address could not tell the defect
     * from the fix.
     *
     * @param replacing true for {@code replaceState}, false for {@code pushState}
     * @param address   the location the browser was asked to show, relative to the base URL
     */
    private record HistoryCall(boolean replacing, String address) {
    }

    /**
     * Every history instruction the server has sent since this was last called.
     *
     * <p>Read off the pending script queue rather than off any field, because an address that never
     * leaves the server is not an address: what makes a conversation reloadable is the instruction
     * arriving at the browser. Draining also clears the queue, so each call reports one visit.
     *
     * <p>Vaadin sends one of two scripts for each call depending on whether the client router is in
     * play - a direct {@code window.history.pushState} or a {@code vaadin-navigate} event carrying
     * {@code replace: false} - so both shapes are recognised rather than one, and a build that
     * switched routers would fail here rather than silently reporting nothing.
     *
     * @param ui the UI whose queue to drain
     * @return the calls, in order, possibly empty
     */
    private static List<HistoryCall> historyCalls(UI ui) {
        StateTree tree = (StateTree) ui.getElement().getNode().getOwner();
        tree.runExecutionsBeforeClientResponse();
        List<HistoryCall> calls = new ArrayList<>();
        for (PendingJavaScriptInvocation pending : ui.getInternals().dumpPendingJavaScriptInvocations()) {
            String expression = pending.getInvocation().getExpression();
            boolean replacing = expression.contains("replaceState") || expression.contains("replace: true");
            boolean pushing = expression.contains("pushState") || expression.contains("replace: false");
            if (!replacing && !pushing) {
                continue;
            }
            pending.getInvocation().getParameters().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(address -> calls.add(new HistoryCall(replacing, address)));
        }
        return List.copyOf(calls);
    }

    private static ConversationSummary stored(String id, String title) {
        return new ConversationSummary(id, title, title, "Web", List.of(), Optional.empty(),
                ConversationSummary.Source.STORED);
    }

    private static String subtitle(MainLayout shell) {
        return descendants(shell.getElement())
                .filter(element -> element.getClassList().contains("jc-topbar__subtitle"))
                .map(Element::getTextRecursively)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The bar has no model line"));
    }

    private static String subtitleTitle(MainLayout shell) {
        return descendants(shell.getElement())
                .filter(element -> element.getClassList().contains("jc-topbar__subtitle"))
                .map(element -> element.getAttribute("title"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The bar has no model line"));
    }

    private static String topbarTitle(MainLayout shell) {
        return descendants(shell.getElement())
                .filter(element -> element.getClassList().contains("jc-topbar__title"))
                .map(Element::getTextRecursively)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The bar has no title"));
    }

    private static List<String> titles(MainLayout shell) {
        return descendants(shell.getElement())
                .filter(element -> element.getClassList().contains("jc-conv__title"))
                .map(Element::getTextRecursively)
                .toList();
    }

    /** The one element in the shell carrying a class name, for the controls that have no title. */
    private static Element element(MainLayout shell, String className) {
        return descendants(shell.getElement())
                .filter(candidate -> candidate.getClassList().contains(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with class " + className));
    }

    private static Element rowTitled(MainLayout shell, String title) {
        return descendants(shell.getElement())
                .filter(element -> element.getClassList().contains("jc-conv"))
                .filter(element -> element.getTextRecursively().contains(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No conversation row titled " + title));
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

    /** A routed view that records what it was asked to show, and shows nothing. */
    private static final class RecordingView extends Div implements ConversationHost {

        private transient ConversationSummary opened;

        /**
         * How many times the view was asked to settle the reader into a new chat.
         *
         * <p>Counted rather than flagged because this is the call that moves the caret and speaks:
         * doing it once when the reader asked is the behaviour, and doing it on a page load nobody
         * asked anything of is a focus steal on the first paint.
         */
        private int entered;

        @Override
        public void openConversation(ConversationSummary conversation) {
            opened = conversation;
        }

        @Override
        public void enterNewConversation() {
            entered++;
        }
    }

    private static final class FakeDirectory implements ConversationDirectory {

        private final List<ConversationSummary> rows = new ArrayList<>();

        private FakeDirectory(ConversationSummary... initial) {
            rows.addAll(List.of(initial));
        }

        /** Stands in for the store catching up: a conversation appears, or acquires a real name. */
        private void put(ConversationSummary row) {
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
