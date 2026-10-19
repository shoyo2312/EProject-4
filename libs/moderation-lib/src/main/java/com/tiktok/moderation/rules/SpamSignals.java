package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.text.NormalizedText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What spam looks like regardless of wording: a way to leave the platform (a link, a phone number,
 * an address), a betting brand, a pile of mentions. Every signal holds — none refuses — because each
 * has an innocent use, and that call is a person's.
 *
 * <p>Links differ by surface. In a comment every link is held: a comment section is where link spam
 * lives. A caption is the owner's own text, and creators routinely point at their other profiles,
 * so links to the allow-listed domains pass there.
 */
public final class SpamSignals {

    private static final Pattern URL =
            Pattern.compile("(?i)(?:https?://|www\\.)([^\\s/?#:@]+)");

    /**
     * A bare {@code name.tld}. The TLDs are the common ones and the ones spam favours; a longer list
     * starts matching ordinary text ({@code hello.world}, {@code this.one}). Not preceded by a letter
     * or digit, so it never starts inside a word, and not by {@code @}, so an e-mail address is
     * reported once, as an address.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}@.\\-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "(?:com|net|org|vn|info|biz|xyz|top|club|site|online|store|shop|link|click|live"
                    + "|io|me|cc|co|ly|gg|tv|app|win|bet|vip|pro|tk|ml|ga|cf|ru|cn|asia))(?![\\p{L}\\p{N}\\-])");

    /** {@code (.)com}, {@code [dot]net}: a link spelled so a link pattern misses it. */
    private static final Pattern OBFUSCATED_DOT = Pattern.compile(
            "(?i)[(\\[{]\\s*(?:\\.|dot|chấm|cham)\\s*[)\\]}]\\s*[a-z]{2,6}\\b");

    private static final Pattern EMAIL = Pattern.compile(
            "[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)+");

    /**
     * A Vietnamese mobile number: {@code 0}, {@code 84} or {@code +84}, a mobile prefix digit, eight
     * more digits, with a space, dot or dash allowed between any two — the ways people write one so
     * a copy-paste still dials. Not inside a longer run of digits, so an order number or a bank
     * account does not match.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+])(?:\\+?84|0)[ .\\-]?[35789](?:[ .\\-]?\\d){8}(?!\\d)");

    /**
     * A betting brand's shape — letters then {@code 88}, at most one digit between them
     * ({@code fb88}, {@code jun88}, {@code sv388}), or {@code bet} with digits ({@code 789bet},
     * {@code f8bet}, {@code bet365}). A digit is required on purpose: without one, {@code alphabet}
     * and {@code tibet} are betting brands. More digits before the {@code 88} make a birth year —
     * {@code minh1988}, the way people sign a name or pick a handle — not a brand. A bare {@code 88}
     * after a name still matches ({@code hung88}): it is drawn exactly like {@code jun88}, and a hold
     * only asks a person. Brands with no digit are named in the blocklist instead.
     */
    private static final Pattern BETTING = Pattern.compile("[a-z]+\\d?88|[a-z]*\\d+[a-z]*bet|bet\\d+");

    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}._])@[\\p{L}\\p{N}._]{2,}");

    private final List<String> captionLinkAllowlist;
    private final int mentionFlood;

    public SpamSignals(List<String> captionLinkAllowlist, int mentionFlood) {
        this.captionLinkAllowlist = captionLinkAllowlist.stream()
                .map(domain -> domain.strip().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .toList();
        this.mentionFlood = mentionFlood;
    }

    public List<Flag> detect(String text, NormalizedText normalized, Surface surface) {
        List<Flag> flags = new ArrayList<>();

        for (String host : linkHosts(text)) {
            if (!surface.isCaption() || !isAllowListed(host)) {
                flags.add(new Flag(Category.SPAM, Flag.HOLD, "link:" + host));
            }
        }
        if (OBFUSCATED_DOT.matcher(text).find()) {
            flags.add(new Flag(Category.SPAM, Flag.HOLD, "link:obfuscated"));
        }
        if (EMAIL.matcher(text).find()) {
            flags.add(new Flag(Category.PERSONAL_INFO, Flag.HOLD, "email"));
        }
        if (PHONE.matcher(text).find()) {
            flags.add(new Flag(Category.PERSONAL_INFO, Flag.HOLD, "phone"));
        }
        normalized.syllables().stream()
                .filter(syllable -> BETTING.matcher(syllable).matches())
                .distinct()
                .forEach(syllable -> flags.add(new Flag(Category.SCAM, Flag.HOLD, "betting:" + syllable)));

        if (surface == Surface.COMMENT || surface == Surface.VIDEO_DESCRIPTION) {
            long mentions = MENTION.matcher(text).results().count();
            if (mentions >= mentionFlood) {
                flags.add(new Flag(Category.SPAM, Flag.HOLD, "mentions:" + mentions));
            }
        }
        return flags;
    }

    /** Every host a link in the text points at, lower case, without {@code www.}, each once. */
    private static Set<String> linkHosts(String text) {
        Set<String> hosts = new LinkedHashSet<>();
        Matcher url = URL.matcher(text);
        while (url.find()) {
            hosts.add(host(url.group(1)));
        }
        Matcher domain = DOMAIN.matcher(text);
        while (domain.find()) {
            hosts.add(host(domain.group(1)));
        }
        return hosts;
    }

    private static String host(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private boolean isAllowListed(String host) {
        return captionLinkAllowlist.stream()
                .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }
}
