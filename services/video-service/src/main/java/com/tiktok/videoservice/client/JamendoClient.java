package com.tiktok.videoservice.client;

import com.tiktok.common.http.PooledRequestFactory;
import com.tiktok.videoservice.config.JamendoProperties;
import com.tiktok.videoservice.exception.MusicUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads tracks from Jamendo's catalogue (api.jamendo.com/v3.0/tracks).
 *
 * <p>Only tracks a video may use come back: ones whose artist allows downloads, under a licence
 * without NoDerivatives — putting music to a picture is an adaptation, which an ND licence forbids.
 * The rest are dropped here, so neither the picker nor publish ever offers one.
 *
 * <p>The API sometimes answers a query with zero results that the same query returns a moment
 * later; an empty answer is asked again, twice, before it is believed.
 */
@Slf4j
@Component
@EnableConfigurationProperties(JamendoProperties.class)
public class JamendoClient {

    private static final int ATTEMPTS = 3;

    private final JamendoProperties properties;
    private final RestClient client;

    public JamendoClient(JamendoProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(PooledRequestFactory.create(properties.timeout(), properties.timeout()))
                .build();
    }

    /**
     * @param query blank for the catalogue's most popular tracks
     */
    public List<MusicTrack> search(String query, int offset, int limit) {
        return fetch(uri -> {
            if (query != null && !query.isBlank()) {
                uri.queryParam("search", query.strip());
            } else {
                uri.queryParam("order", "popularity_total");
            }
            return uri.queryParam("offset", offset).queryParam("limit", limit);
        });
    }

    /** Empty for an id the catalogue does not have, or has but this service would not offer. */
    public Optional<MusicTrack> find(String id) {
        return fetch(uri -> uri.queryParam("id", id)).stream().findFirst();
    }

    private List<MusicTrack> fetch(Function<UriBuilder, UriBuilder> query) {
        if (!properties.configured()) {
            throw new MusicUnavailableException(null);
        }
        for (int attempt = 1; ; attempt++) {
            JsonNode body;
            try {
                body = client.get()
                        .uri(uri -> build(query.apply(uri.path("/tracks/"))))
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                log.warn("Music catalogue unreachable", e);
                throw new MusicUnavailableException(e);
            }
            if (body == null || !"success".equals(body.path("headers").path("status").asString(""))) {
                // The answer names our client id in its error message; logged, never returned.
                log.warn("Music catalogue refused the query: {}",
                        body == null ? "no body" : body.path("headers").path("error_message").asString(""));
                throw new MusicUnavailableException(null);
            }
            JsonNode results = body.path("results");
            if (!results.isEmpty() || attempt == ATTEMPTS) {
                return tracks(results);
            }
        }
    }

    private URI build(UriBuilder uri) {
        return uri.queryParam("client_id", properties.clientId())
                .queryParam("format", "json")
                .queryParam("audioformat", "mp32")
                .queryParam("include", "licenses")
                .queryParam("ccnd", "false")
                .build();
    }

    private static List<MusicTrack> tracks(JsonNode results) {
        List<MusicTrack> tracks = new ArrayList<>();
        for (JsonNode r : results) {
            String licence = r.path("license_ccurl").asString("");
            if (!r.path("audiodownload_allowed").asBoolean(false)
                    || licence.toLowerCase(Locale.ROOT).contains("-nd")
                    || r.path("audiodownload").asString("").isBlank()) {
                continue;
            }
            tracks.add(new MusicTrack(
                    r.path("id").asString(""),
                    r.path("name").asString(""),
                    r.path("artist_name").asString(""),
                    r.path("duration").asInt(0),
                    r.path("audio").asString(""),
                    r.path("audiodownload").asString(""),
                    r.path("image").asString(""),
                    licence,
                    r.path("shareurl").asString("")));
        }
        return tracks;
    }
}
