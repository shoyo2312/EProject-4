package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.JamendoClient;
import com.tiktok.videoservice.client.MusicTrack;
import com.tiktok.videoservice.dto.response.MusicTrackResponse;
import com.tiktok.videoservice.exception.MusicNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The catalogue behind a short in-memory cache. Every user opening the picker asks for the same
 * first page, and the catalogue's quota is per client id — this service's, shared by everyone —
 * so a page is fetched once per {@link #TTL}, not once per user. Tracks seen in a page are kept
 * too: publishing a pick from the picker then needs no call at all.
 *
 * <p>Empty pages are not cached. The catalogue sometimes answers with nothing for a moment, and
 * remembering that would show an empty picker to everyone for the whole TTL.
 */
@Service
@RequiredArgsConstructor
public class MusicServiceImpl implements MusicService {

    static final Duration TTL = Duration.ofMinutes(10);

    /** Past this many pages or tracks, the cache starts over rather than grow without bound. */
    private static final int MAX_ENTRIES = 1000;

    private final JamendoClient jamendoClient;
    private final Clock clock = Clock.systemUTC();

    private final Map<String, Cached<List<MusicTrack>>> pages = new ConcurrentHashMap<>();
    private final Map<String, Cached<MusicTrack>> tracks = new ConcurrentHashMap<>();

    private record Cached<T>(T value, Instant until) {
    }

    @Override
    public List<MusicTrackResponse> search(String query, int page, int size) {
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String key = normalized + "|" + page + "|" + size;
        List<MusicTrack> found = fresh(pages.get(key));
        if (found == null) {
            found = jamendoClient.search(normalized, page * size, size);
            if (!found.isEmpty()) {
                put(pages, key, found);
                found.forEach(track -> put(tracks, track.id(), track));
            }
        }
        return found.stream().map(MusicTrackResponse::of).toList();
    }

    @Override
    public MusicTrack requireTrack(String trackId) {
        MusicTrack track = fresh(tracks.get(trackId));
        if (track != null) {
            return track;
        }
        track = jamendoClient.find(trackId).orElseThrow(MusicNotFoundException::new);
        put(tracks, trackId, track);
        return track;
    }

    private <T> T fresh(Cached<T> cached) {
        return cached != null && cached.until().isAfter(clock.instant()) ? cached.value() : null;
    }

    private <T> void put(Map<String, Cached<T>> cache, String key, T value) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached<>(value, clock.instant().plus(TTL)));
    }
}
