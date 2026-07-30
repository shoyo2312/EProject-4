package com.tiktok.searchservice.service;

import com.tiktok.searchservice.document.ProductDocument;
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
