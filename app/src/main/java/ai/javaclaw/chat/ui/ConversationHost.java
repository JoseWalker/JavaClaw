package ai.javaclaw.chat.ui;

/**
 * Implemented by a view that shows one conversation and can be told to show a different one.
 *
 * <p>This is the seam between the chrome and the content. The shell owns the sidebar and therefore
 * owns which conversation the reader has chosen, but it must not own the transcript - loading and
 * streaming messages belongs to the view that draws them. Naming the capability rather than the
 * class keeps the dependency pointing the right way: the shell can host any view that knows how to
 * open a conversation, and a view that does not is simply never asked to.
 */
interface ConversationHost {

    /**
     * Shows the given conversation, discarding whatever was on screen.
     *
     * <p>Called during navigation as well as on selection, so it may run before the view is attached
     * to a UI. Implementations must refuse a conversation they cannot open rather than clearing the
     * transcript and leaving the reader with nothing.
     *
     * @param conversation the conversation to show; never null
     */
    void openConversation(ConversationSummary conversation);
}
