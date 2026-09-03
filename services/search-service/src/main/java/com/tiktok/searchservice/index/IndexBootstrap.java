package com.tiktok.searchservice.index;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.document.VideoDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the three indexes with their declared mappings before anything writes to them.
 *
 * <p>Nothing else does it now that the writes go through scripted updates rather than
 * repositories — and an index Elasticsearch auto-creates on first write gets a dynamic mapping
 * instead of the declared one, which for {@code tags} means text-analysed rather than keyword.
 * That fails in the worst way available: no error, and a tag filter that starts matching
 * loosely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBootstrap implements ApplicationRunner {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void run(ApplicationArguments args) {
        create(VideoDocument.class);
        create(ProductDocument.class);
        create(ProcessedEventDocument.class);
    }

    private void create(Class<?> type) {
        IndexOperations indexOps = elasticsearchOperations.indexOps(type);
        if (indexOps.exists()) {
            return;
        }
        indexOps.createWithMapping();
        log.info("Created index for {}", type.getSimpleName());
    }
}
