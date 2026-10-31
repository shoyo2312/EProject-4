package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.dto.response.TypingView;
import com.tiktok.chatservice.entity.Conversation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who is typing where, right now. Kept in memory and nowhere else: a typing indicator is a hint that
 * is wrong a few seconds later, so it is never stored, never a message, never counted as unread, and
 * never touches {@code lastMessageAt}. What reaches the other side is {@code (conversation, user,
 * typing)} — nothing about the text.
 *
 * <p>Tracked per socket session, and the session is the one the server assigned, not anything the
 * client says: a user is typing while ANY of their sessions is, so one tab stopping does not cancel
 * another that is still typing. Each session's claim expires after {@link #TTL_MS} unless renewed, so
 * a crash, a dropped connection or a closed laptop cannot leave the dots up for ever; a closed socket
 * is dropped at once.
 *
 * <p>A typing frame is only accepted from a participant whose conversation is open for them to send
 * into — {@link SendPermission#ALLOWED}, which rules out a request nobody accepted, a deleted request
 * that has to be reopened first and a request whose message is spent — and with no block between
 * the two. Anything else, a failed check included, is dropped without a word: the other side learns
 * nothing from the refusal.
 */
@Slf4j
@Service
public class TypingService {

    /** How long one session's "typing" lasts without being renewed. The client renews every 2.5s. */
    public static final long TTL_MS = 7_000;
    /** At most one accepted frame per session this often; a faster client is simply ignored. */
    static final long MIN_INTERVAL_MS = 1_000;
    /** How long a yes (participant, open to send, not blocked) is trusted before it is asked again. */
    static final long ALLOW_CACHE_MS = 5_000;
    static final int MAX_SESSIONS_PER_USER = 20;

    private record Key(String conversationId, Long userId) {
    }

    private record Allowed(List<Long> peers, long until) {
    }

    private static final class Slot {
        long expiresAt;
        long acceptedAt;

        Slot(long expiresAt, long acceptedAt) {
            this.expiresAt = expiresAt;
            this.acceptedAt = acceptedAt;
        }
    }

    private static final class Entry {
        final List<Long> peers;
        final Map<String, Slot> sessions = new HashMap<>();

        Entry(List<Long> peers) {
            this.peers = peers;
        }
    }

    private final ConversationService conversationService;
    private final BlockClient blockClient;
    private final ChatEventPublisher eventPublisher;
    private final LongSupplier clock;

    private final Map<Key, Entry> active = new ConcurrentHashMap<>();
    private final Map<Key, Allowed> allowed = new ConcurrentHashMap<>();

    @Autowired
    public TypingService(ConversationService conversationService, BlockClient blockClient,
                         ChatEventPublisher eventPublisher) {
        this(conversationService, blockClient, eventPublisher, System::currentTimeMillis);
    }

    TypingService(ConversationService conversationService, BlockClient blockClient,
                  ChatEventPublisher eventPublisher, LongSupplier clock) {
        this.conversationService = conversationService;
        this.blockClient = blockClient;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** One typing frame from {@code userId}'s socket session {@code sessionId}. */
    public void update(String conversationId, Long userId, String sessionId, boolean typing) {
        if (sessionId == null || conversationId == null) {
            return;
        }
        Key key = new Key(conversationId, userId);
        if (!typing) {
            stopSession(key, sessionId);
            return;
        }
        long now = clock.getAsLong();
        Entry current = active.get(key);
        if (current != null) {
            synchronized (current) {
                Slot slot = current.sessions.get(sessionId);
                if (slot != null && now - slot.acceptedAt < MIN_INTERVAL_MS) {
                    return;
                }
            }
        }
        List<Long> peers = peersIfAllowed(key, now);
        if (peers.isEmpty()) {
            // The door closed (blocked, mid-request): whatever was up comes down.
            stopSession(key, sessionId);
            return;
        }
        active.compute(key, (k, entry) -> {
            Entry next = entry == null ? new Entry(peers) : entry;
            synchronized (next) {
                if (!next.sessions.containsKey(sessionId) && next.sessions.size() >= MAX_SESSIONS_PER_USER) {
                    return next;
                }
                next.sessions.put(sessionId, new Slot(now + TTL_MS, now));
            }
            return next;
        });
        // Every accepted frame goes on, started or renewed: the other side keeps its own expiry and
        // needs the renewals to hold the dots up.
        publish(peers, conversationId, userId, true);
    }

    /** A socket closed: everything it was typing stops, unless another of the user's sessions still is. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        sessionClosed(event.getSessionId());
    }

    void sessionClosed(String sessionId) {
        for (Key key : new ArrayList<>(active.keySet())) {
            stopSession(key, sessionId);
        }
    }

    /** Claims that were not renewed run out. */
    @Scheduled(fixedDelay = 1_000)
    public void expire() {
        long now = clock.getAsLong();
        List<Runnable> stops = new ArrayList<>();
        for (Map.Entry<Key, Entry> each : active.entrySet()) {
            Key key = each.getKey();
            Entry entry = each.getValue();
            boolean gone;
            synchronized (entry) {
                for (Iterator<Slot> it = entry.sessions.values().iterator(); it.hasNext(); ) {
                    if (it.next().expiresAt <= now) {
                        it.remove();
                    }
                }
                gone = entry.sessions.isEmpty();
            }
            if (gone && active.remove(key, entry)) {
                List<Long> peers = entry.peers;
                stops.add(() -> publish(peers, key.conversationId(), key.userId(), false));
            }
        }
        stops.forEach(Runnable::run);
        allowed.values().removeIf(a -> a.until() <= now);
    }

    private void stopSession(Key key, String sessionId) {
        Entry entry = active.get(key);
        if (entry == null) {
            return;
        }
        boolean empty;
        synchronized (entry) {
            if (entry.sessions.remove(sessionId) == null) {
                return;
            }
            empty = entry.sessions.isEmpty();
        }
        if (empty && active.remove(key, entry)) {
            publish(entry.peers, key.conversationId(), key.userId(), false);
        }
    }

    /** The other participants, if {@code key.userId()} may type here right now; empty otherwise. */
    private List<Long> peersIfAllowed(Key key, long now) {
        Allowed cached = allowed.get(key);
        if (cached != null && cached.until() > now) {
            return cached.peers();
        }
        try {
            Conversation conversation = conversationService.requireParticipant(key.userId(), key.conversationId());
            if (SendPolicy.of(conversation, key.userId()) != SendPermission.ALLOWED) {
                allowed.remove(key);
                return List.of();
            }
            List<Long> peers = conversation.getParticipantIds().stream()
                    .filter(id -> !id.equals(key.userId())).toList();
            for (Long peer : peers) {
                blockClient.requireNotBlocked(key.userId(), peer);
            }
            allowed.put(key, new Allowed(peers, now + ALLOW_CACHE_MS));
            return peers;
        } catch (RuntimeException e) {
            // Not a participant, blocked, or the check itself could not be made: all the same here.
            log.debug("Typing refused in {}: {}", key.conversationId(), e.getClass().getSimpleName());
            allowed.remove(key);
            return List.of();
        }
    }

    private void publish(List<Long> peers, String conversationId, Long userId, boolean typing) {
        ChatEvent event = ChatEvent.typing(new TypingView(conversationId, userId, typing));
        for (Long peer : peers) {
            eventPublisher.toUser(peer, event);
        }
    }
}
