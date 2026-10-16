package com.tiktok.moderation.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.TextModerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps this service's {@link TextModerator} on admin-service's term list: asks
 * {@code GET /api/v1/admin/internal/blocklist} every {@code refresh} and rebuilds the matcher when
 * the revision moved.
 *
 * <p>Polled rather than pushed over Kafka: every instance needs every change, and a consumer group
 * hands each event to one instance of the group. A poll per minute per instance of a list this size
 * costs nothing, and the delay is bounded by the interval.
 *
 * <p>An unreachable admin-service keeps the last list — and before the first answer, the shipped
 * file alone — logging once when it starts failing and once when it recovers. Never the reason a
 * service does not start: it starts on its own thread, after the context is up.
 */
public class AdminTermsSync implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminTermsSync.class);

    static final String PATH = "/api/v1/admin/internal/blocklist";

    /**
     * Names as strings rather than enums: an admin-service a release ahead may send a category this
     * library has never heard of, and one unknown name must not throw away the whole list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireTerm(String term, String category, String action) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snapshot(long revision, List<WireTerm> terms) {
    }

    private final TextModerator moderator;
    private final RestClient restClient;
    private final Duration refresh;
    private ScheduledExecutorService scheduler;
    private volatile boolean failing;

    public AdminTermsSync(TextModerator moderator, RestClient restClient, Duration refresh) {
        this.moderator = moderator;
        this.restClient = restClient;
        this.refresh = refresh;
    }

    /** One poll. Public for tests and for a caller that wants a change in now rather than in a minute. */
    public void refresh() {
        try {
            ApiResponse<Snapshot> response = restClient.get()
                    .uri(PATH)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            Snapshot snapshot = response == null ? null : response.data();
            if (snapshot == null) {
                throw new IllegalStateException("admin-service answered without a list");
            }
            OptionalLong current = moderator.adminRevision();
            if (current.isEmpty() || current.getAsLong() != snapshot.revision()) {
                moderator.applyAdminTerms(snapshot.revision(), terms(snapshot));
            }
            if (failing) {
                failing = false;
                log.info("Admin moderation terms reachable again, at revision {}", snapshot.revision());
            }
        } catch (RuntimeException e) {
            if (!failing) {
                failing = true;
                log.warn("Could not read admin moderation terms; keeping {} until admin-service answers: {}",
                        moderator.version(), e.toString());
            }
        }
    }

    private static List<AdminTerm> terms(Snapshot snapshot) {
        List<AdminTerm> terms = new ArrayList<>();
        for (WireTerm wire : snapshot.terms() == null ? List.<WireTerm>of() : snapshot.terms()) {
            AdminTerm.Action action = parse(AdminTerm.Action.class, wire.action());
            Category category = parse(Category.class, wire.category());
            if (action == null || (action != AdminTerm.Action.EXEMPT && category == null)) {
                log.warn("Admin term '{}' has an action or category this service does not know ({}, {}); skipped",
                        wire.term(), wire.action(), wire.category());
                continue;
            }
            terms.add(new AdminTerm(wire.term(), category, action));
        }
        return terms;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "admin-terms-sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refresh, 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
