package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        assertThat(line).startsWith("Answering with ").contains("gemma4:31b-cloud");

        // And the scope is spelled out for anyone who stops on it, rather than left to the verb.
        assertThat(subtitleTitle(shell))
                .contains("configured now")
                .contains("Earlier answers");
    }

    // ------------------------------------------------------------------ helpers

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

        @Override
        public void openConversation(ConversationSummary conversation) {
            opened = conversation;
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
