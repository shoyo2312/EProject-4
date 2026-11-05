package com.tiktok.notificationservice.event.consumer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "@handle"s a comment tags. A mention is plain text — the web composer types the handle in —
 * so this reads it back out with the rule the web tints by (`splitMentions` in tiktok-cloned): an
 * "@" that starts a word, followed by handle characters. "me@example.com" tags nobody.
 */
final class CommentMentions {

    /** ponytail: fixed cap so one comment cannot page a crowd; the rest stay plain text. */
    static final int MAX_MENTIONS = 10;

    private static final Pattern MENTION = Pattern.compile("(?<![^\\s])@([\\p{L}\\p{N}_.]+)");

    private CommentMentions() {
    }

    /** Distinct, lowercased (handles are case-insensitive), in the order they first appear. */
    static List<String> handlesIn(String content) {
        if (content == null || content.indexOf('@') < 0) {
            return List.of();
        }
        Set<String> handles = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(content);
        while (matcher.find() && handles.size() < MAX_MENTIONS) {
            handles.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(handles);
    }
}
