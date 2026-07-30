package com.tiktok.searchservice.repository;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProcessedEventRepository extends ElasticsearchRepository<ProcessedEventDocument, String> {
}
