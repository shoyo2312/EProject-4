package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentIndex;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentCountersRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

/**
 * Fills {@code comment_index} and {@code comment_counters} from comments that were written before
 * those tables existed. Without it every such comment is stored but unlistable: the listing reads
 * the index, and a comment with no index row is invisible.
 *
 * <p>Off unless {@code interaction.comment-index.backfill=true}, and meant to be run once by hand
 * and then switched off again — index writes are idempotent, but the reply counter is a Cassandra
 * counter and a second run adds the replies a second time.
 *
 * <p>ponytail: full table scan on one node, no resume point. The table is comments, not events, and
 * this runs once; a job that checkpoints its cursor is the upgrade if it ever has to run on a table
 * too big to finish in one go.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "interaction.comment-index.backfill", havingValue = "true")
public class CommentIndexBackfill implements ApplicationRunner {

    private static final int PAGE_SIZE = 500;

    private final CommentByVideoRepository commentByVideoRepository;
    private final CommentIndexRepository commentIndexRepository;
    private final CommentCountersRepository commentCountersRepository;

    @Override
    public void run(ApplicationArguments args) {
        CassandraPageRequest pageRequest = CassandraPageRequest.first(PAGE_SIZE);
        long indexed = 0;

        while (true) {
            Slice<CommentByVideo> slice = commentByVideoRepository.findAll(pageRequest);
            for (CommentByVideo comment : slice) {
                Long videoId = comment.getKey().getVideoId();
                Long commentId = comment.getKey().getCommentId();
                Long parentId = comment.getParentId();

                // Deleted comments are indexed too: the reader skips them by their row, and
                // leaving holes in the index would only make the page-skipping loop guess.
                commentIndexRepository.save(CommentIndex.of(videoId, parentId, commentId));
                indexed++;

                if (parentId != null && !comment.isDeleted()) {
                    commentCountersRepository.incrementReplyCount(videoId, parentId, 1);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }

        log.info("Comment index backfill done: {} comments indexed. Turn "
                + "interaction.comment-index.backfill off before the next restart.", indexed);
    }
}
