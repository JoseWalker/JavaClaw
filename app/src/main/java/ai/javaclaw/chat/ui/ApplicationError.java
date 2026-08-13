package ai.javaclaw.chat.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.HasErrorParameter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/**
 * What the reader is shown when the server could not do what they asked.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Because failure was not a designed state anywhere in this application, so whatever the
 * framework did by default was the whole of the design. A live audit read
 * {@code document.body.innerText} after a server-side throwable and got "There was an exception
 * while trying to navigate to '' with the root cause 'java.lang.OutOfMemoryErro..." - Vaadin's own
 * fallback text, which is a Java class name shown to somebody who came here to ask a question. Every
 * later load painted a blank page: no shell, no brand mark, not one anchor, and an HTTP 200 in under
 * two seconds, so nothing on screen said the application was down at all.
 *
 * <p>What made that structural rather than incidental is that a search of this source tree for
 * {@code HasErrorParameter}, {@code ErrorHandler} and {@code setErrorHandler} returned nothing. The
 * throwable that triggered it is unrepeatable and beside the point; the absence of anywhere for a
 * throwable to land is neither.
 *
 * <h2>What it says, and what it refuses to say</h2>
 *
 * <p>Three sentences and a link, in the voice the rest of the application speaks in. That something
 * went wrong, because the reader can see that much already and pretending otherwise is worse. That
 * their conversations are still there, because that is the fear an error page creates and it is a
 * fear this application can honestly answer - the transcript store is written turn by turn and a
 * failed request does not touch it. And a way back in, as a real anchor rather than a control that
 * needs the server to answer, because the server is what has just failed to.
 *
 * <p>Nothing about the failure itself reaches the reader. Not the exception's name, not its message,
 * not a host, a port or a path: none of it is anything they can act on, and the measured defect is
 * the strongest case there is for saying so - a reader was shown a Java class name. The whole of it
 * goes to the log, under a reference the page shows, which is the one thing worth carrying from one
 * to the other.
 *
 * <h2>Why it borrows the shell's chrome rather than the shell</h2>
 *
 * <p>Because {@link MainLayout} needs a conversation directory and a live session, and either of
 * those can be what has just failed. An error page that can fail has nothing left to fall back to,
 * so this one is built from nothing but its own markup and the stylesheet's class names - the same
 * ground, the same mark, the same measure, and no dependency that can throw.
 */
public class ApplicationError extends Div implements HasErrorParameter<Exception> {

    private static final Logger log = LoggerFactory.getLogger(ApplicationError.class);

    /** The page's own heading. Deliberately about the request rather than about an exception. */
    private static final String HEADING = "Something went wrong";

    /**
     * The reassurance, and it is a claim this application is in a position to make.
     *
     * <p>Turns are written to the transcript store as they happen rather than at the end of a
     * session, so a request that failed did not take a conversation with it. Saying so is the
     * difference between an error page and an error page a reader can walk away from.
     */
    private static final String REASSURANCE =
            "JavaClaw could not finish that request. Your conversations are safe - nothing that was "
                    + "already said has been lost.";

    /** What the link means, spelled out so the link text does not have to carry the instruction. */
    private static final String WAY_BACK = "Reload the page, or ";

    /** The link's own text, which says where it goes rather than "click here". */
    private static final String WAY_BACK_LINK = "go back to your conversations";

    /**
     * The application's own root, relative to the base URL Vaadin writes into every page it serves.
     *
     * <p>Relative for the reason {@link ConversationAddress} is: where this application is mounted is
     * the server's decision, and an absolute path built here would be a second opinion about it.
     */
    private static final String APPLICATION_ROOT = ".";

    /** What the reference line says before the reference, so the number is not a bare token. */
    private static final String REFERENCE_PREFIX = "Reference ";

    /**
     * Where the reference is written, filled in when a failure arrives.
     *
     * <p>Held as a field rather than built in {@link #setErrorParameter} so the page's shape is
     * settled by the constructor and cannot depend on a failure being well formed enough to describe.
     */
    private final Span reference = new Span();

    /**
     * Builds the page before it is told what went wrong.
     *
     * <p>Everything except the reference, which is the only part that varies. A page assembled inside
     * the error callback would be a page whose existence depends on that callback completing, and the
     * one thing an error surface may not do is fail.
     */
    public ApplicationError() {
        addClassName("jc-shell");

        Span mark = new Span("JC");
        mark.addClassNames("jc-mark", "jc-mark--l");
        // The letterform is decoration next to a heading that says the same thing in words.
        mark.getElement().setAttribute("aria-hidden", "true");

        H1 heading = new H1(HEADING);
        heading.addClassName("jc-empty__title");

        Span said = new Span(REASSURANCE);
        said.addClassName("jc-empty__hint");

        // A real anchor, and router-ignored: this is the one page where a client-side navigation is
        // the wrong retry, because whatever failed may well be the thing that would have to answer
        // it. A plain link makes the browser fetch the page again from nothing.
        Anchor back = new Anchor(APPLICATION_ROOT, WAY_BACK_LINK);
        back.setRouterIgnore(true);

        Paragraph offer = new Paragraph(new Span(WAY_BACK), back);
        Div prose = new Div(offer);
        prose.addClassName("jc-prose");

        reference.addClassNames("jc-empty__hint", "jc-error__reference");

        Div body = new Div(mark, heading, said, prose, reference);
        body.addClassNames("jc-empty", "jc-measure");

        Div content = new Div(body);
        content.addClassName("jc-main__content");

        Main main = new Main(content);
        main.addClassName("jc-main");
        add(main);
    }

    /**
     * Records the failure and shows the reader a page rather than a stack trace.
     *
     * <p>Deny by default about what is shown: nothing is read out of the throwable at all, so there
     * is no path by which a message, a host or a file name can reach the page. What is read out of it
     * goes to the log, in full, once.
     *
     * <p>A failure carrying nothing is the ordinary case rather than an edge one - the framework
     * reaches here whenever it knows only that something went wrong - so a null throwable is
     * expected, logged as such, and still gets a page. Whatever this returns, the reader must end up
     * looking at something.
     *
     * <p>Must run on the Vaadin UI thread; Vaadin only calls it there.
     *
     * @param event     the navigation that failed, not read from - the address a reader typed is not
     *                  a fact this page needs and putting it in a log line would be user input in a
     *                  log line
     * @param parameter the failure, whose throwable may be null
     * @return HTTP 500, because the request did not succeed and a page that says so while answering
     *         200 lies to everything that is not a person
     */
    @Override
    public int setErrorParameter(BeforeEnterEvent event, ErrorParameter<Exception> parameter) {
        String correlation = newReference();
        Throwable cause = parameter == null ? null : parameter.getCaughtException();
        if (cause == null) {
            log.error("A navigation failed with no throwable to report. Reference {}", correlation);
        } else {
            log.error("A navigation failed and the reader was shown the error page. Reference {}",
                    correlation, cause);
        }
        reference.setText(REFERENCE_PREFIX + correlation);
        return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    /**
     * A short token tying the page the reader is looking at to the line in the log.
     *
     * <p>Random rather than sequential, and not a secret either way: it exists so a reader can quote
     * something and an operator can find it, and a counter would leak how many failures the process
     * has had. Eight hex characters is short enough to read aloud over a phone and long enough that
     * two failures in one log file will not collide.
     *
     * @return the reference, upper case for legibility, never blank
     */
    private static String newReference() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
