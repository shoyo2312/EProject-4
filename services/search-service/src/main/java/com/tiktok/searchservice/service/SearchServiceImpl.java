package com.tiktok.searchservice.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import com.tiktok.searchservice.mapper.SearchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Only surfaces documents in a terminal "visible" state (video status PUBLISHED and visibility
 * PUBLIC) — everything else is still being indexed, was marked inactive upstream, or is not the
 * searcher's to see.
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String TITLE_PREFIX = TITLE + "." + VideoDocument.PREFIX_SUBFIELD;
    private static final String DESCRIPTION_PREFIX = DESCRIPTION + "." + VideoDocument.PREFIX_SUBFIELD;

    /**
     * Title outranks a body mention of the same word: without the boost a caption that happens to
     * say "dance" scores level with a video called "Dance tutorial".
     */
    private static final float TITLE_BOOST = 3f;

    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchMapper searchMapper;

    /**
     * A hashtag as a caller types it — "#Dance", "dance", " Dance " — reduced to what
     * video-service actually stored. It normalises tags at publish time (lowercase, {@code #}
     * stripped) and the field is a keyword, so an un-normalised term matches nothing at all
     * rather than matching loosely.
     */
    private static String normalizeHashtag(String hashtag) {
        String stripped = hashtag.strip().toLowerCase(Locale.ROOT);
        return stripped.startsWith("#") ? stripped.substring(1).strip() : stripped;
    }

    @Override
    public Page<VideoSearchResponse> searchVideos(String query, String hashtag, Pageable pageable) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        // Both conditions, because they answer different questions: status says the video finished
        // moderation, visibility says who may see it. Filtering on status alone handed a PRIVATE
        // video — title, description, thumbnail — to any anonymous caller, since video-service's
        // own visibility check never runs on this read path.
        //
        // Search has no viewer identity to work with (the gateway lets these calls through
        // unauthenticated), so FRIENDS is excluded along with PRIVATE rather than resolved. A
        // friend looking for a friend's video finds it on the profile listing, which does know
        // who is asking.
        //
        // Documents indexed before the field existed have no visibility and so match nothing
        // here. That is the safe direction, and they gain the field the next time their
        // publication event is replayed.
        //
        // Filters, not must clauses: they decide membership only and contribute nothing to the
        // score, so ranking is left entirely to how well the text matched.
        bool.filter(f -> f.term(t -> t.field("status").value("PUBLISHED")));
        bool.filter(f -> f.term(t -> t.field("visibility").value("PUBLIC")));

        if (StringUtils.hasText(query)) {
            // A document need only satisfy one arm to be a result, but every arm it satisfies adds
            // to its score, so the order comes out as: whole-word hits in the title, then
            // whole-word hits in the caption, then videos the term is merely a prefix of, then
            // near-misses a typo away. A search for "video_" lists video_1 … video_10, all of them,
            // while "video_1" still puts video_1 first ahead of the rest.
            bool.should(s -> s.multiMatch(m -> m.query(query)
                    .fields(TITLE + "^" + TITLE_BOOST, DESCRIPTION)
                    .boost(3f)));
            bool.should(s -> s.multiMatch(m -> m.query(query)
                    .fields(TITLE_PREFIX + "^" + TITLE_BOOST, DESCRIPTION_PREFIX)));
            // Fuzziness goes on the prefix fields, not the main ones: the standard analyser keeps
            // "video_1" as a single token, so a typo like "vidoe" is three edits from it and finds
            // nothing, whereas the prefix field holds "video" itself. Half weight, so a near-miss
            // never outranks something the term actually starts. prefixLength keeps the first
            // letter exact, or one- and two-letter terms match nearly every short word indexed.
            bool.should(s -> s.multiMatch(m -> m.query(query)
                    .fields(TITLE_PREFIX + "^" + TITLE_BOOST, DESCRIPTION_PREFIX)
                    .fuzziness("AUTO")
                    .prefixLength(1)
                    .boost(0.5f)));
            // Tags are in the free-text arms too, so a caller who types "dance" without the hash
            // still finds videos whose only mention of it is a caption hashtag.
            bool.should(s -> s.term(t -> t.field("tags").value(normalizeHashtag(query)).boost(2f)));
            bool.minimumShouldMatch("1");
        }
        if (StringUtils.hasText(hashtag)) {
            String normalized = normalizeHashtag(hashtag);
            if (!normalized.isEmpty()) {
                bool.filter(f -> f.term(t -> t.field("tags").value(normalized)));
            }
        }

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(bool.build())))
                .withPageable(pageable)
                // Elasticsearch stops counting at 10 000 by default and reports that as the total,
                // so a page count built from it silently caps — Page is a paging contract, it has
                // to be exact.
                .withTrackTotalHits(true);
        if (pageable.getSort().isUnsorted()) {
            // Best match first; among equal matches (and for a tag-only search, where every hit
            // scores the same) the newest video.
            builder.withSort(Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createdAt")));
        }
        NativeQuery nativeQuery = builder.build();
        SearchHits<VideoDocument> hits = elasticsearchOperations.search(nativeQuery, VideoDocument.class);

        List<VideoSearchResponse> content = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(searchMapper::toResponse)
                .toList();

        return new PageImpl<>(content, pageable, hits.getTotalHits());
    }
}
