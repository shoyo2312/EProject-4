package com.tiktok.searchservice.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.tiktok.searchservice.client.UserDirectoryClient;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.mapper.SearchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.tiktok.common.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private SearchMapper searchMapper;

    @Mock
    private UserDirectoryClient userDirectoryClient;

    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchServiceImpl(elasticsearchOperations, searchMapper, userDirectoryClient);
        VideoDocument document = VideoDocument.builder().id("v1").status("PUBLISHED").build();
        SearchHits<VideoDocument> hits = mockSearchHits(document);
        // Lenient: the rejection tests never get as far as searching.
        lenient().when(elasticsearchOperations.search(any(NativeQuery.class), eq(VideoDocument.class))).thenReturn(hits);
    }

    /** title is a text field: Elasticsearch refuses to sort on it, and that refusal was a 500. */
    @Test
    void searchVideos_refusesASortOnAFieldElasticsearchCannotSort() {
        assertThatThrownBy(() -> searchService.searchVideos("x", null, PageRequest.of(0, 10, Sort.by("title"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchVideos_refusesAPagePastTheResultWindow() {
        assertThatThrownBy(() -> searchService.searchVideos("x", null, PageRequest.of(100, 100)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchVideos_hashtagMatchesTheExactTagPrefixesAndNearMisses() {
        searchService.searchVideos(null, "#Dance ", PageRequest.of(0, 10));

        BoolQuery hashtag = capturedBool().must().get(0).bool();
        assertThat(hashtag.minimumShouldMatch()).isEqualTo("1");
        // The exact arm is a term on a keyword field: an un-normalized "#Dance " would match
        // nothing at all there.
        assertThat(hashtag.should().stream().filter(Query::isTerm)
                .map(q -> q.term().value().stringValue())).containsExactly("dance");
        // The loose arms are what let "#cat" reach #capcut and #car, and captions with them.
        assertThat(hashtag.should().stream().filter(Query::isMultiMatch)
                .map(q -> q.multiMatch().fields()))
                .containsOnly(List.of("tags.prefix^3", "description.prefix"))
                .hasSize(2);
        assertThat(hashtag.should().stream().filter(Query::isMultiMatch)
                .map(q -> q.multiMatch().fuzziness()).filter(Objects::nonNull)).containsExactly("AUTO");
    }

    @Test
    void searchVideos_freeTextAlsoFindsTheVideosOfAuthorsItNames() {
        when(userDirectoryClient.findUserIds("vmh")).thenReturn(List.of(7L, 8L));

        searchService.searchVideos(" vmh ", null, PageRequest.of(0, 10));

        List<Long> authorIds = capturedBool().should().stream()
                .filter(Query::isTerms)
                .filter(q -> q.terms().field().equals("userId"))
                .flatMap(q -> q.terms().terms().value().stream())
                .map(FieldValue::longValue)
                .toList();
        assertThat(authorIds).containsExactly(7L, 8L);
    }

    /** Nobody by that name, or user-service down: the search runs on the text alone. */
    @Test
    void searchVideos_withNoMatchingAuthor_addsNoAuthorArm() {
        searchService.searchVideos("dance", null, PageRequest.of(0, 10));

        assertThat(capturedBool().should()).noneMatch(Query::isTerms);
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
                List.of("title.prefix^3.0", "description.prefix", "tags.prefix"),
                List.of("title.prefix^3.0", "description.prefix", "tags.prefix"));

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
        lenient().when(searchHit.getContent()).thenReturn(content);
        lenient().when(hits.getSearchHits()).thenReturn(List.of(searchHit));
        lenient().when(hits.getTotalHits()).thenReturn(1L);
        return hits;
    }
}
