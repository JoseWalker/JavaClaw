package ai.javaclaw.chat.ui;

import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.RouteRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the reader is shown when the server could not do what they asked.
 *
 * <p>Failure was not a designed state anywhere in this application. A live audit read
 * {@code document.body.innerText} after a server-side throwable and got "There was an exception
 * while trying to navigate to '' with the root cause 'java.lang.OutOfMemoryErro..." - Vaadin's own
 * default text, which is a Java class name shown to somebody who came here to ask a question. Every
 * later load painted a blank page: no shell, no brand mark, no anchors, and an HTTP 200 in under two
 * seconds, so nothing on screen said the application was down at all.
 *
 * <p>The trigger is not what these tests are about, and could not be: whatever exhausts a heap on a
 * given afternoon is unrepeatable. What is structural is that the application had no error surface
 * to fall back to - no {@code HasErrorParameter}, no error view - so whatever the framework did by
 * default was the whole of the design. These tests pin the surface, not the throwable.
 *
 * <p>Every test here fails on the original by not compiling against it: there was no such class.
 */
class ApplicationErrorTest {

    /**
     * A failure carrying exactly the two things that must not reach a reader: where the application
     * was trying to go on the network, and what the exception is called.
     */
    private static final RuntimeException LEAKY =
            new IllegalStateException("connect timed out to http://10.0.0.5:11434/api/chat");

    @Test
    @DisplayName("a failure lands on JavaClaw's own page rather than on the framework's")
    void paintsTheApplicationsOwnChrome() {
        ApplicationError page = new ApplicationError();

        int status = page.setErrorParameter(navigation(), new ErrorParameter<>(Exception.class, LEAKY));

        assertThat(status).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        // The three things the audit found missing on the blank page it measured.
        assertThat(classesOf(page)).contains("jc-shell", "jc-mark");
        assertThat(text(page)).isNotBlank();
    }

    /**
     * The reader is told what happened to them, in the voice the rest of this application speaks in,
     * and is given something to do about it.
     *
     * <p>A page that only says something broke is a dead end. The two true statements worth making
     * are that their conversations are still there and that trying again is worth doing, and the
     * link is what makes the second one an action rather than an instruction.
     */
    @Test
    @DisplayName("it says what happened and offers a way back into the application")
    void saysWhatHappenedAndOffersAWayBack() {
        ApplicationError page = new ApplicationError();

        page.setErrorParameter(navigation(), new ErrorParameter<>(Exception.class, LEAKY));

        assertThat(text(page).toLowerCase(Locale.ROOT))
                .contains("javaclaw")
                .contains("conversation");
        assertThat(links(page))
                .as("a way back into the application, as a link a browser can follow on its own")
                .isNotEmpty();
    }

    /**
     * Never leak. An error page is read by whoever is in front of the browser, which on a chat
     * assistant is not necessarily the person who deployed it.
     *
     * <p>The measured defect is the strongest possible case for this: the reader was shown a Java
     * class name. What a throwable carries beyond that is worse - hostnames, ports, file paths,
     * fragments of a query - and none of it is anything a reader can act on. It belongs in the log,
     * and the reader gets a reference they can quote instead.
     */
    @Test
    @DisplayName("nothing about the failure itself reaches the reader")
    void tellsTheReaderNothingDiagnostic() {
        ApplicationError page = new ApplicationError();

        page.setErrorParameter(navigation(), new ErrorParameter<>(Exception.class, LEAKY));

        String shown = text(page);
        assertThat(shown)
                .doesNotContain("IllegalStateException")
                .doesNotContain("java.")
                .doesNotContain("10.0.0.5")
                .doesNotContain("11434")
                .doesNotContain("connect timed out")
                .doesNotContain("Exception")
                .doesNotContain("at ai.javaclaw");
    }

    /**
     * The one thing a reader can usefully carry to whoever runs the server.
     *
     * <p>Without it, "something went wrong" is unactionable from both ends: the reader has nothing
     * to quote and the log has nothing to match. The reference is minted per failure and is the only
     * thing shared between the page and the log line.
     */
    @Test
    @DisplayName("the reader is given a reference, and a different one for a different failure")
    void givesTheReaderAReferenceToQuote() {
        ApplicationError first = new ApplicationError();
        ApplicationError second = new ApplicationError();

        first.setErrorParameter(navigation(), new ErrorParameter<>(Exception.class, LEAKY));
        second.setErrorParameter(navigation(), new ErrorParameter<>(Exception.class, LEAKY));

        assertThat(reference(first)).isNotBlank();
        assertThat(reference(second)).isNotBlank().isNotEqualTo(reference(first));
    }

    /**
     * The page has to render for a failure that describes itself not at all.
     *
     * <p>An exception with no message is the ordinary case rather than an edge one - a null
     * dereference and an out-of-memory both arrive that way - and a page assembled out of what the
     * throwable had to say would come back blank for exactly the failures that matter most. The one
     * failure with nowhere left to fall back to is an error page that fails.
     */
    @Test
    @DisplayName("a failure that describes itself not at all still gets a page")
    void survivesAFailureThatSaysNothing() {
        ApplicationError page = new ApplicationError();

        int status = page.setErrorParameter(navigation(),
                new ErrorParameter<>(Exception.class, new IllegalStateException()));

        assertThat(status).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertThat(text(page)).isNotBlank();
        assertThat(reference(page)).isNotBlank();
    }

    // ------------------------------------------------------------------ helpers

    /** A navigation that failed, as the router would deliver it to an error view. */
    private static BeforeEnterEvent navigation() {
        return new BeforeEnterEvent(new Router(Mockito.mock(RouteRegistry.class)),
                NavigationTrigger.PAGE_LOAD, new Location(""), ApplicationError.class,
                RouteParameters.empty(), UiHarness.ui(), List.of());
    }

    private static String text(ApplicationError page) {
        return page.getElement().getTextRecursively();
    }

    /** The reference the page shows, taken from the element that carries it and nowhere else. */
    private static String reference(ApplicationError page) {
        return descendants(page.getElement())
                .filter(element -> element.getClassList().contains("jc-error__reference"))
                .map(Element::getTextRecursively)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The error page shows no reference"));
    }

    private static List<String> classesOf(ApplicationError page) {
        List<String> classes = new ArrayList<>(page.getElement().getClassList());
        descendants(page.getElement()).forEach(element -> classes.addAll(element.getClassList()));
        return classes;
    }

    private static List<String> links(ApplicationError page) {
        return descendants(page.getElement())
                // A text node has no tag and throws when asked for one, so the walk has to skip
                // them rather than ask and find out.
                .filter(element -> !element.isTextNode())
                .filter(element -> "a".equals(element.getTag()))
                .map(element -> element.getAttribute("href"))
                .filter(href -> href != null)
                .toList();
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
}
