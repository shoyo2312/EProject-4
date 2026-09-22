package com.tiktok.searchservice.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.mapper.SearchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private SearchMapper searchMapper;

    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchServiceImpl(elasticsearchOperations, searchMapper);
        VideoDocument document = VideoDocument.builder().id("v1").status("PUBLISHED").build();
        SearchHits<VideoDocument> hits = mockSearchHits(document);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(VideoDocument.class))).thenReturn(hits);
    }

    @Test
    void searchVideos_normalizesHashtagBeforeFilteringOnTags() {
        searchService.searchVideos(null, "#Dance ", PageRequest.of(0, 10));

        // The tag is stored lowercased and without the hash, on a keyword field: an un-normalized
        // term would match nothing at all rather than match loosely.
        List<String> tagFilters = capturedBool().filter().stream()
                .filter(q -> q.isTerm() && q.term().field().equals("tags"))
                .map(q -> q.term().value().stringValue())
                .toList();
        assertThat(tagFilters).containsExactly("dance");
    }

    @Test
    void searchVideos_freeTextMatchesWholeWordsPrefixesAndNearMisses() {
        searchService.searchVideos("video_", null, PageRequest.of(0, 10));

        BoolQuery bool = capturedBool();
        // Any one arm is enough to be a result — "video_" must list video_1 … video_10 even though
        // none of them contains that exact token.
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");

        List<List<String>> multiMatchFields = bool.should().stream()
                .filter(Query::isMultiMatch)
                .map(q -> q.multiMatch().fields())
                .toList();
        assertThat(multiMatchFields).containsExactlyInAnyOrder(
                List.of("title^3.0", "description"),
                List.of("title.prefix^3.0", "description.prefix"),
                List.of("title.prefix^3.0", "description.prefix"));

        List<String> fuzziness = bool.should().stream()
                .filter(Query::isMultiMatch)
                .map(q -> q.multiMatch().fuzziness())
                .filter(Objects::nonNull)
                .toList();
        assertThat(fuzziness).containsExactly("AUTO");
    }

    @Test
    void searchVideos_onlyEverReturnsPublicPublishedVideos() {
        searchService.searchVideos("anything", null, PageRequest.of(0, 10));

        List<String> filters = capturedBool().filter().stream()
                .map(q -> q.term().field() + "=" + q.term().value().stringValue())
                .toList();
        assertThat(filters).containsExactlyInAnyOrder("status=PUBLISHED", "visibility=PUBLIC");
    }

    private BoolQuery capturedBool() {
        ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(elasticsearchOperations).search(captor.capture(), eq(VideoDocument.class));
        return Objects.requireNonNull(captor.getValue().getQuery()).bool();
    }

    @SuppressWarnings("unchecked")
    private <T> SearchHits<T> mockSearchHits(T content) {
        SearchHits<T> hits = mock(SearchHits.class);
        SearchHit<T> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(content);
        when(hits.getSearchHits()).thenReturn(List.of(searchHit));
        when(hits.getTotalHits()).thenReturn(1L);
        return hits;
    }
}
