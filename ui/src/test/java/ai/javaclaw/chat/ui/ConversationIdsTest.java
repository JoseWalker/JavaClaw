package ai.javaclaw.chat.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What may be treated as a conversation id.
 *
 * <p>A conversation id is not an opaque label: the chat store turns it into a file name, and the
 * sidebar hands one straight back to the agent whenever a row is clicked. So the shape is a
 * boundary, and the boundary is an allow-list rather than a list of characters to strip - a
 * rejection is easy to reason about, a repair is not.
 */
class ConversationIdsTest {

    @Test
    @DisplayName("the ids the store already uses are accepted")
    void acceptsExistingIds() {
        assertThat(ConversationIds.isWellFormed("web")).isTrue();
        assertThat(ConversationIds.isWellFormed("telegram-123456789")).isTrue();
        assertThat(ConversationIds.isWellFormed("web_2")).isTrue();
    }

    @Test
    @DisplayName("anything that could leave the conversations folder is refused")
    void refusesPathTraversal() {
        assertThat(ConversationIds.isWellFormed("..")).isFalse();
        assertThat(ConversationIds.isWellFormed("../../etc/passwd")).isFalse();
        assertThat(ConversationIds.isWellFormed("web/../secrets")).isFalse();
        assertThat(ConversationIds.isWellFormed("web.yaml")).isFalse();
    }

    @Test
    @DisplayName("nothing, whitespace or an over-long id is refused rather than trimmed")
    void refusesEmptyAndOversized() {
        assertThat(ConversationIds.isWellFormed(null)).isFalse();
        assertThat(ConversationIds.isWellFormed("")).isFalse();
        assertThat(ConversationIds.isWellFormed("   ")).isFalse();
        assertThat(ConversationIds.isWellFormed("a".repeat(200))).isFalse();
    }

    @Test
    @DisplayName("a freshly minted id is well formed, distinct, and stays in the web channel")
    void mintsUsableIds() {
        String first = ConversationIds.newConversationId();
        String second = ConversationIds.newConversationId();

        assertThat(first).isNotEqualTo(second);
        assertThat(ConversationIds.isWellFormed(first)).isTrue();
        assertThat(ConversationIds.isWellFormed(second)).isTrue();
        // The channel prefix is what files a new chat under the same heading as the live one; a
        // conversation started here is a web conversation, not a channel of its own.
        assertThat(first).startsWith(ConversationIds.LIVE + "-");
    }
}
