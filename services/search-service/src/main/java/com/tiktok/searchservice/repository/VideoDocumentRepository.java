package com.tiktok.searchservice.repository;

import com.tiktok.searchservice.document.VideoDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface VideoDocumentRepository extends ElasticsearchRepository<VideoDocument, String> {
}
