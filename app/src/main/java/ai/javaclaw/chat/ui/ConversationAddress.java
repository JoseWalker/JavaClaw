package ai.javaclaw.chat.ui;

import com.vaadin.flow.router.RouteParameters;

import java.util.Optional;

/**
 * Where a conversation lives in the address bar.
 *
 * <h2>Why a conversation needs an address at all</h2>
 *
 * <p>It did not have one. Every conversation was reached by clicking a row, the URL never moved off
 * {@code /ui/}, and a reload therefore had nothing to go on: an audit measured a full answer on
 * screen, pressed reload, and came back to an empty transcript titled "New chat". Nothing was lost
 * from the store - the thread was still in the column - but the reader's place was, and a place that
 * only exists inside one browser tab cannot be bookmarked, cannot be sent to a colleague, and does
 * not survive the browser deciding to reload the tab on its own.
 *
 * <h2>Why the vocabulary is here rather than on the view or the shell</h2>
 *
 * <p>Two parties have to agree about it and neither of them should own it. {@link ChatView} declares
 * the route, because the route belongs to the view that answers it; {@link MainLayout} reads the
 * parameter out of a navigation and writes the address back when the reader moves, because the shell
 * owns which conversation the reader is in. If either of them held the template, the other would be
 * holding a copy, and the failure mode of two copies is an address the router does not answer - a
 * reload landing on a 404 instead of on the conversation, which is a worse outcome than the defect
 * this fixes.
 *
 * <p>The template is a compile-time constant so the annotation on the view and the path built here
 * are the same string by construction rather than by review.
 */
final class ConversationAddress {

    /**
     * The name of the route parameter carrying a conversation id.
     *
     * <p>Read back with {@link RouteParameters#get(String)}, which is why it is a constant: a typo
     * in either half is not a compile error, it is a route that silently never matches.
     */
    static final String PARAMETER = "conversationId";

    /**
     * The path segment a conversation hangs under.
     *
     * <p>One segment rather than none, so the conversation space cannot collide with a future route:
     * {@code /ui/settings} has to be able to mean the settings page rather than a conversation
     * someone happened to name "settings".
     */
    static final String PREFIX = "c";

    /**
     * The route template {@link ChatView} answers for a conversation.
     *
     * <p>Deliberately not optional. {@code /ui/c} with nothing after it names no conversation, and a
     * route that matches it would be a route that has to invent one.
     */
    static final String ROUTE = PREFIX + "/:" + PARAMETER;

    private ConversationAddress() {
    }

    /**
     * The address of a conversation, relative to the application's base URL.
     *
     * <p>Relative on purpose: the application is mapped under a prefix the server decides, and the
     * browser resolves this against the {@code <base href>} Vaadin already writes into the page. An
     * absolute path built here would be a second opinion about where the application is mounted.
     *
     * @param conversationId the conversation to address; must already be known well formed
     * @return the path, without a leading slash
     */
    static String of(String conversationId) {
        return PREFIX + "/" + conversationId;
    }

    /**
     * The conversation a navigation is asking for, if it is asking for one at all.
     *
     * <p>The shape is checked here rather than trusted, because a route parameter is whatever was
     * typed into the address bar. {@link ConversationIds#isWellFormed} is the same allow-list the
     * store is protected by, and an id that fails it is dropped rather than repaired: a repaired id
     * addresses a different conversation, and showing the reader a thread they did not ask for is a
     * quieter lie than showing them nothing.
     *
     * @param parameters the route parameters of the navigation, possibly null outside a navigation
     * @return the conversation id, or empty when the navigation names none or names one that could
     *         not be a conversation
     */
    static Optional<String> from(RouteParameters parameters) {
        if (parameters == null) {
            return Optional.empty();
        }
        return parameters.get(PARAMETER).filter(ConversationIds::isWellFormed);
    }
}
