package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.StickerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The stickers offered while someone types, one pool per topic ({@link SuggestionTopics}).
 *
 * <p>GIPHY is asked once per topic every few hours, never per keystroke or per user: a refresh runs
 * at startup and on a timer, so a request for "happy" is answered from memory. That keeps the
 * calls to GIPHY at a few dozen a day whatever the traffic — its free key allows 100 an hour, and
 * the picker's own searches share it. A pool holds more stickers than are shown ({@link #POOL});
 * each answer is a random few of them, so the same word does not always offer the same pictures.
 *
 * <p>A refresh that fails keeps the pool it has and tries again in {@link #RETRY}; a topic that has
 * never loaded answers with nothing for that long rather than asking GIPHY on every request.
 * Only one request refreshes a stale pool, the rest are served the old one.
 */
@Component
public class StickerSuggestions {

    static final int POOL = 20;
    static final int SHOWN = 8;
    static final Duration TTL = Duration.ofHours(6);
    static final Duration RETRY = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(StickerSuggestions.class);

    private record Pool(List<StickerView> stickers, long expiresAt) {
    }

    private final SuggestionTopics topics;
    private final GiphyClient giphy;
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    @Autowired
    public StickerSuggestions(SuggestionTopics topics, GiphyClient giphy) {
        this(topics, giphy, System::nanoTime);
    }

    /** With a clock a test can move, to see a pool expire. */
    StickerSuggestions(SuggestionTopics topics, GiphyClient giphy, LongSupplier nanoTime) {
        this.topics = topics;
        this.giphy = giphy;
        this.nanoTime = nanoTime;
    }

    /** A few stickers for {@code topicId}; none for a topic that does not exist or has nothing yet. */
    public List<StickerView> suggestions(String topicId) {
        SuggestionTopics.Topic topic = topics.find(topicId).orElse(null);
        if (topic == null) {
            return List.of();
        }
        Pool pool = pools.get(topic.id());
        if (pool == null || expired(pool)) {
            pool = refresh(topic, pool);
        }
        List<StickerView> shuffled = new ArrayList<>(pool.stickers());
        Collections.shuffle(shuffled);
        return List.copyOf(shuffled.subList(0, Math.min(SHOWN, shuffled.size())));
    }

    /** Warms every topic at startup, then keeps them fresh. Never throws: a failure only fills the log. */
    @Scheduled(initialDelayString = "${chat.giphy.suggestion-initial-delay-millis:5000}",
            fixedDelayString = "${chat.giphy.suggestion-refresh-millis:3600000}")
    public void refreshAll() {
        for (SuggestionTopics.Topic topic : topics.all()) {
            try {
                Pool pool = pools.get(topic.id());
                if (pool == null || expired(pool)) {
                    refresh(topic, pool);
                }
            } catch (RuntimeException e) {
                log.warn("Refreshing sticker suggestions for {} failed: {}", topic.id(), e.getClass().getSimpleName());
            }
        }
    }

    private Pool refresh(SuggestionTopics.Topic topic, Pool current) {
        ReentrantLock lock = locks.computeIfAbsent(topic.id(), id -> new ReentrantLock());
        if (!lock.tryLock()) {
            if (current != null) {
                return current;
            }
            // Nothing to serve yet and another request is loading it: wait for that answer.
            lock.lock();
        }
        try {
            Pool latest = pools.get(topic.id());
            if (latest != null && !expired(latest)) {
                return latest;
            }
            List<StickerView> fresh = giphy.stickers(topic.query(), 0, POOL);
            Pool next;
            if (fresh.isEmpty()) {
                // GiphyClient answers empty for a failure and for a service with no key alike.
                next = new Pool(latest == null ? List.of() : latest.stickers(), deadline(RETRY));
            } else {
                next = new Pool(List.copyOf(fresh), deadline(TTL));
            }
            pools.put(topic.id(), next);
            return next;
        } finally {
            lock.unlock();
        }
    }

    private boolean expired(Pool pool) {
        return nanoTime.getAsLong() - pool.expiresAt() >= 0;
    }

    private long deadline(Duration after) {
        return nanoTime.getAsLong() + after.toNanos();
    }
}
