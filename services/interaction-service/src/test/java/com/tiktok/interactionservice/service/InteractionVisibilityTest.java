package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.request.ViewRequest;
import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.exception.VideoNotFoundException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
import com.tiktok.interactionservice.repository.RepostByVideoRepository;
import com.tiktok.interactionservice.repository.SaveByUserRepository;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Interactions used to be accepted for any id at all. A PRIVATE or FRIENDS video could be liked,
 * commented on and — since the comment list is public — have its comments read by anyone who knew
 * its id; a made-up id could be shared into trending. Every write, and the comment read, now asks
 * video-service whether the caller may see the video first.
 */
class InteractionVisibilityTest extends AbstractInteractionServiceIT {

    private static final long HIDDEN = 990L;

    @MockBean
    private VideoOwnershipClient videoOwnershipClient;

    @Autowired private LikeService likeService;
    @Autowired private ShareService shareService;
    @Autowired private SaveService saveService;
    @Autowired private RepostService repostService;
    @Autowired private CommentService commentService;
    @Autowired private ViewService viewService;

    @Autowired private LikeByVideoRepository likeByVideoRepository;
    @Autowired private ShareByVideoRepository shareByVideoRepository;
    @Autowired private SaveByUserRepository saveByUserRepository;
    @Autowired private RepostByVideoRepository repostByVideoRepository;
    @Autowired private CommentByVideoRepository commentByVideoRepository;
    @Autowired private VideoCountersRepository videoCountersRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void hiddenVideo() {
        likeByVideoRepository.deleteAll();
        shareByVideoRepository.deleteAll();
        saveByUserRepository.deleteAll();
        repostByVideoRepository.deleteAll();
        commentByVideoRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        doThrow(new VideoNotFoundException(HIDDEN)).when(videoOwnershipClient).requireVisible(HIDDEN);
    }

    @Test
    void writesToAVideoTheCallerCannotSee_areRefusedAndLeaveNothingBehind() {
        assertThatThrownBy(() -> likeService.like(HIDDEN, 1L)).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> shareService.share(HIDDEN, 1L)).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> saveService.save(HIDDEN, 1L)).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> repostService.repost(HIDDEN, 1L)).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> commentService.addComment(HIDDEN, 1L, "hi")).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> viewService.recordView(HIDDEN, 1L, new ViewRequest("p1")))
                .isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> viewService.recordWatch(HIDDEN, 1L, new WatchRequest(1_000L, 2_000L)))
                .isInstanceOf(VideoNotFoundException.class);

        assertThat(likeByVideoRepository.count()).isZero();
        assertThat(shareByVideoRepository.count()).isZero();
        assertThat(saveByUserRepository.count()).isZero();
        assertThat(repostByVideoRepository.count()).isZero();
        assertThat(commentByVideoRepository.count()).isZero();
        assertThat(videoCountersRepository.count()).isZero();
    }

    @Test
    void theCommentThreadOfAVideoTheCallerCannotSee_isNotReadable() {
        assertThatThrownBy(() -> commentService.listComments(HIDDEN, null, 20, null))
                .isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> commentService.listReplies(HIDDEN, 5L, null, 20, null))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void aVisibleVideo_isUnaffected() {
        assertThat(likeService.like(991L, 1L).liked()).isTrue();
    }
}
