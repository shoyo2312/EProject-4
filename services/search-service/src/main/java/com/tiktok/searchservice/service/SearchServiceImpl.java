package com.tiktok.searchservice.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.tiktok.common.exception.BadRequestException;
import com.tiktok.searchservice.client.UserDirectoryClient;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import com.tiktok.searchservice.index.OwnerBans;
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
import java.util.Set;

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
    private static final String TAGS = "tags";
    private static final String TAGS_PREFIX = TAGS + "." + VideoDocument.PREFIX_SUBFIELD;

    /**
     * Title outranks a body mention of the same word: without the boost a caption that happens to
     * say "dance" scores level with a video called "Dance tutorial".
     */
    private static final float TITLE_BOOST = 3f;

    /**
     * What a caller may sort on. The sort used to go to Elasticsearch as given, and title or
     * description (text fields, which it will not sort) or any unmapped name failed the whole
     * search as a 500.
     */
    private static final Set<String> SORTABLE =
            Set.of("_score", "createdAt", "viewCount", "likeCount", "commentCount", "shareCount");

    /**
     * Elasticsearch's default {@code index.max_result_window}: a page reaching past it is refused
     * outright rather than returned short, which surfaced as a 500.
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * Longer than anything a person types into a search box. The endpoint needs no login, and each
     * term is run through three multi-matches, one of them fuzzy, so an unbounded one is a cheap
     * way to make every search slow.
     */
    static final int MAX_QUERY_LENGTH = 200;

    /**
     * The shortest query worth asking user-service for authors. Its {@code %q%} match is served by
     * trigram indexes, which cannot help a one- or two-letter term: each such search scanned and
     * sorted every profile. And since one arm is enough to make a result, "a" also listed every
     * video by the 50 most-followed accounts with an "a" in their name.
     */
    static final int MIN_AUTHOR_QUERY_LENGTH = 3;

    /** user-service's username and display-name columns; nothing longer can be contained in one. */
    static final int MAX_AUTHOR_QUERY_LENGTH = 100;

    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchMapper searchMapper;
    private final UserDirectoryClient userDirectoryClient;
    private final OwnerBans ownerBans;

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
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new BadRequestException("INVALID_SORT",
                        "Cannot sort by '" + order.getProperty() + "'. Sortable: " + SORTABLE);
            }
        }
        if (pageable.getOffset() + pageable.getPageSize() > MAX_RESULT_WINDOW) {
            throw new BadRequestException("PAGE_TOO_DEEP",
                    "Only the first " + MAX_RESULT_WINDOW + " results can be paged through; narrow the search.");
        }
        if ((query != null && query.length() > MAX_QUERY_LENGTH)
                || (hashtag != null && hashtag.length() > MAX_QUERY_LENGTH)) {
            throw new BadRequestException("QUERY_TOO_LONG",
                    "Search terms are limited to " + MAX_QUERY_LENGTH + " characters.");
        }

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
        // A banned or closed account's videos, read now rather than stamped on them — see OwnerBans.
        List<FieldValue> hidden = ownerBans.hidden().stream().map(FieldValue::of).toList();
        if (!hidden.isEmpty()) {
            bool.mustNot(m -> m.terms(t -> t.field("userId").terms(v -> v.value(hidden))));
        }

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
                    .fields(TITLE_PREFIX + "^" + TITLE_BOOST, DESCRIPTION_PREFIX, TAGS_PREFIX)));
            // Fuzziness goes on the prefix fields, not the main ones: the standard analyser keeps
            // "video_1" as a single token, so a typo like "vidoe" is three edits from it and finds
            // nothing, whereas the prefix field holds "video" itself. Half weight, so a near-miss
            // never outranks something the term actually starts. prefixLength keeps the first
            // letter exact, or one- and two-letter terms match nearly every short word indexed.
            bool.should(s -> s.multiMatch(m -> m.query(query)
                    .fields(TITLE_PREFIX + "^" + TITLE_BOOST, DESCRIPTION_PREFIX, TAGS_PREFIX)
                    .fuzziness("AUTO")
                    .prefixLength(1)
                    .boost(0.5f)));
            // Tags are in the free-text arms too, so a caller who types "dance" without the hash
            // still finds videos whose only mention of it is a caption hashtag.
            bool.should(s -> s.term(t -> t.field(TAGS).value(normalizeHashtag(query)).boost(2f)));
            // The author's name: "vmh" lists vmh26122004's videos even when none of them says
            // "vmh". The index holds only userId, so user-service resolves the name to ids.
            String author = query.strip();
            List<FieldValue> authorIds = author.length() < MIN_AUTHOR_QUERY_LENGTH
                    || author.length() > MAX_AUTHOR_QUERY_LENGTH
                    ? List.of()
                    : userDirectoryClient.findUserIds(author).stream()
                            .map(id -> FieldValue.of(id.longValue()))
                            .toList();
            if (!authorIds.isEmpty()) {
                bool.should(s -> s.terms(t -> t.field("userId").terms(v -> v.value(authorIds)).boost(2f)));
            }
            bool.minimumShouldMatch("1");
        }
        if (StringUtils.hasText(hashtag)) {
            String normalized = normalizeHashtag(hashtag);
            if (!normalized.isEmpty()) {
                // The free-text arms again, over the tags and the caption they are written in:
                // "#cat" lists #cat first, then tags it starts (#catdance), then near-misses
                // (#capcut, #car). A must rather than a filter, so it ranks as well as admits.
                bool.must(m -> m.bool(tag -> tag
                        .should(s -> s.term(t -> t.field(TAGS).value(normalized).boost(3f)))
                        .should(s -> s.multiMatch(mm -> mm.query(normalized)
                                .fields(TAGS_PREFIX + "^3", DESCRIPTION_PREFIX)))
                        .should(s -> s.multiMatch(mm -> mm.query(normalized)
                                .fields(TAGS_PREFIX + "^3", DESCRIPTION_PREFIX)
                                .fuzziness("AUTO")
                                .prefixLength(1)
                                .boost(0.5f)))
                        .minimumShouldMatch("1")));
            }
        }

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(bool.build())))
                .withPageable(pageable)
                // Counted up to the result window and no further. No page past MAX_RESULT_WINDOW
                // can be asked for (PAGE_TOO_DEEP above), so a count beyond it only describes pages
                // nobody can reach. Counting every match instead (track_total_hits: true) made
                // Elasticsearch visit all of them and give up its top-k early termination on every
                // search, for a number past reach. A page size that does not divide the window (the
                // web client's 24) still has its last, partial page refused — as with the exact count.
                .withTrackTotalHitsUpTo(MAX_RESULT_WINDOW);
        if (pageable.getSort().isUnsorted()) {
            // Best match first; among equal matches (and with neither q nor hashtag, where every
            // hit scores the same) the newest video.
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
