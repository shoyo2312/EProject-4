package com.tiktok.searchservice.service;

import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.ProductSearchResponse;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import com.tiktok.searchservice.mapper.SearchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Only surfaces documents in a terminal "visible" state (video status PUBLISHED and visibility
 * PUBLIC, product status ACTIVE) — everything else is still being indexed, was marked inactive
 * upstream, or is not the searcher's to see.
 */
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

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
        Criteria criteria = Criteria.where("status").is("PUBLISHED")
                .and(Criteria.where("visibility").is("PUBLIC"));

        if (StringUtils.hasText(query)) {
            // Tags are in the free-text arm too, so a caller who types "dance" without the hash
            // still finds videos whose only mention of it is a caption hashtag.
            // Title outranks a body mention of the same word: both are analysed text, so without
            // the boost a caption that happens to say "dance" scores level with a video called
            // "Dance tutorial".
            criteria = criteria.and(Criteria.where("title").matches(query).boost(3f)
                    .or("description").matches(query)
                    .or("tags").is(normalizeHashtag(query)));
        }
        if (StringUtils.hasText(hashtag)) {
            String normalized = normalizeHashtag(hashtag);
            if (!normalized.isEmpty()) {
                criteria = criteria.and(Criteria.where("tags").is(normalized));
            }
        }

        CriteriaQuery criteriaQuery = new CriteriaQuery(criteria, pageable);
        // Elasticsearch stops counting at 10 000 by default and reports that as the total, so a
        // page count built from it silently caps — Page is a paging contract, it has to be exact.
        criteriaQuery.setTrackTotalHits(true);
        SearchHits<VideoDocument> hits = elasticsearchOperations.search(criteriaQuery, VideoDocument.class);

        List<VideoSearchResponse> content = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(searchMapper::toResponse)
                .toList();

        return new PageImpl<>(content, pageable, hits.getTotalHits());
    }

    @Override
    public Page<ProductSearchResponse> searchProducts(String query, String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        Criteria criteria = Criteria.where("status").is("ACTIVE");

        if (StringUtils.hasText(query)) {
            criteria = criteria.and(
                    Criteria.where("name").matches(query).or("description").matches(query));
        }
        if (StringUtils.hasText(category)) {
            criteria = criteria.and(Criteria.where("category").is(category));
        }
        if (minPrice != null) {
            criteria = criteria.and(Criteria.where("price").greaterThanEqual(minPrice));
        }
        if (maxPrice != null) {
            criteria = criteria.and(Criteria.where("price").lessThanEqual(maxPrice));
        }

        CriteriaQuery criteriaQuery = new CriteriaQuery(criteria, pageable);
        criteriaQuery.setTrackTotalHits(true);
        SearchHits<ProductDocument> hits = elasticsearchOperations.search(criteriaQuery, ProductDocument.class);

        List<ProductSearchResponse> content = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(searchMapper::toResponse)
                .toList();

        return new PageImpl<>(content, pageable, hits.getTotalHits());
    }
}
