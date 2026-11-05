package com.tiktok.notificationservice.event.consumer;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMentionsTest {

    @Test
    void handlesIn_findsEachTagOnce_lowercased_inOrder() {
        assertThat(CommentMentions.handlesIn("@Alice hi @bob.builder and @alice again"))
                .containsExactly("alice", "bob.builder");
    }

    /** Same rule the web tags and tints by: a mention starts a word, so an address is not one. */
    @Test
    void handlesIn_ignoresAnAtInsideAWord() {
        assertThat(CommentMentions.handlesIn("mail me at me@example.com")).isEmpty();
        assertThat(CommentMentions.handlesIn(null)).isEmpty();
    }

    /** One comment cannot page a crowd: past the cap the rest are plain text. */
    @Test
    void handlesIn_stopsAtTheCap() {
        String many = IntStream.range(0, 30).mapToObj(i -> "@user" + i).collect(Collectors.joining(" "));

        assertThat(CommentMentions.handlesIn(many)).hasSize(CommentMentions.MAX_MENTIONS)
                .startsWith("user0", "user1");
    }
}
