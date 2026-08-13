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

    /**
     * Settles the reader into a chat that has just been started for them.
     *
     * <p>Separate from {@link #openConversation} because starting a chat and choosing one are
     * different requests with different right answers. Choosing a conversation means "show me
     * this", and moving the caret out of the list the reader is browsing would be taking the list
     * away mid-browse. Starting one means "I want to ask something else", and leaving the caret
     * where it was - which for a keystroke is nowhere at all - is the shell hearing the request and
     * doing half of it.
     *
     * <p>Called after the conversation has been opened, never instead of it.
     *
     * <p>Default: nothing. A view that has no composer to move into is entitled to stay put, and
     * making this abstract would force every future host to write an empty method to say so.
     */
    default void enterNewConversation() {
        // A host with nowhere to put the caret has nothing to do here.
    }
}
