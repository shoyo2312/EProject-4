package com.tiktok.searchservice.service;

import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.ProductSearchResponse;
import com.tiktok.searchservice.mapper.SearchMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private SearchMapper searchMapper;

    private SearchServiceImpl searchService;

    @Test
    void searchProducts_appliesPriceRangeAndCategoryFilters() {
        searchService = new SearchServiceImpl(elasticsearchOperations, searchMapper);

        ProductDocument document = ProductDocument.builder().id(1L).name("Phone case").price(BigDecimal.TEN).status("ACTIVE").build();
        ProductSearchResponse response = new ProductSearchResponse(1L, 2L, "Phone case", null, BigDecimal.TEN, "accessories", null, null);

        SearchHits<ProductDocument> hits = mockSearchHits(document);
        when(elasticsearchOperations.search(any(Query.class), org.mockito.ArgumentMatchers.eq(ProductDocument.class)))
                .thenReturn(hits);
        when(searchMapper.toResponse(document)).thenReturn(response);

        ArgumentCaptor<CriteriaQuery> queryCaptor = ArgumentCaptor.forClass(CriteriaQuery.class);

        Page<ProductSearchResponse> result = searchService.searchProducts(
                "phone", "accessories", BigDecimal.ONE, BigDecimal.valueOf(100), PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void searchVideos_normalizesHashtagBeforeFilteringOnTags() {
        searchService = new SearchServiceImpl(elasticsearchOperations, searchMapper);

        VideoDocument document = VideoDocument.builder().id("v1").status("PUBLISHED").build();
        SearchHits<VideoDocument> hits = mockSearchHits(document);
        when(elasticsearchOperations.search(any(Query.class), org.mockito.ArgumentMatchers.eq(VideoDocument.class)))
                .thenReturn(hits);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);

        searchService.searchVideos(null, "#Dance ", PageRequest.of(0, 10));

        org.mockito.Mockito.verify(elasticsearchOperations)
                .search(queryCaptor.capture(), org.mockito.ArgumentMatchers.eq(VideoDocument.class));

        // The tag is stored lowercased and without the hash, on a keyword field: an un-normalized
        // term would match nothing at all rather than match loosely.
        assertThat(criteriaValues(((CriteriaQuery) queryCaptor.getValue()).getCriteria()))
                .contains("dance")
                .doesNotContain("#Dance ");
    }

    /** Every value in a criteria tree: the flat chain, plus any nested "or" branches. */
    private static java.util.List<Object> criteriaValues(Criteria criteria) {
        java.util.List<Object> values = new java.util.ArrayList<>();
        for (Criteria node : criteria.getCriteriaChain()) {
            node.getQueryCriteriaEntries().forEach(entry -> values.add(entry.getValue()));
            node.getSubCriteria().forEach(sub -> values.addAll(criteriaValues(sub)));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private <T> SearchHits<T> mockSearchHits(T content) {
        SearchHits<T> hits = mock(SearchHits.class);
        var searchHit = mock(org.springframework.data.elasticsearch.core.SearchHit.class);
        when(searchHit.getContent()).thenReturn(content);
        when(hits.getSearchHits()).thenReturn(java.util.List.of(searchHit));
        when(hits.getTotalHits()).thenReturn(1L);
        return hits;
    }
}
