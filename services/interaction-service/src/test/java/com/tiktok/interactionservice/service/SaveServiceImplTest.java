package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.exception.InvalidCursorException;
import com.tiktok.interactionservice.repository.SaveByUserRepository;
import com.tiktok.interactionservice.repository.SaveByUserTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaveServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private SaveService saveService;

    @Autowired
    private SaveByUserRepository saveByUserRepository;

    @Autowired
    private SaveByUserTimeRepository saveByUserTimeRepository;

    @BeforeEach
    void cleanUp() {
        saveByUserRepository.deleteAll();
        saveByUserTimeRepository.deleteAll();
    }

    @Test
    void save_thenGetStatus_reportsSaved() {
        saveService.save(10L, 1L);

        assertThat(saveService.getStatus(10L, 1L).saved()).isTrue();
        assertThat(saveService.getStatus(10L, 2L).saved()).isFalse();
    }

    @Test
    void save_calledTwice_isIdempotent() {
        saveService.save(11L, 1L);
        SaveStatusResponse response = saveService.save(11L, 1L);

        assertThat(response.saved()).isTrue();
        assertThat(saveService.listSavedVideos(1L, null, 20).videoIds()).containsExactly(11L);
    }

    @Test
    void unsave_removesItFromTheListing() {
        saveService.save(12L, 1L);
        saveService.unsave(12L, 1L);

        assertThat(saveService.getStatus(12L, 1L).saved()).isFalse();
        assertThat(saveService.listSavedVideos(1L, null, 20).videoIds()).isEmpty();
    }

    @Test
    void listSavedVideos_pagesThroughTheCursor() {
        saveService.save(20L, 1L);
        saveService.save(21L, 1L);
        saveService.save(22L, 1L);

        VideoIdPageResponse first = saveService.listSavedVideos(1L, null, 2);
        assertThat(first.videoIds()).hasSize(2);
        assertThat(first.hasMore()).isTrue();

        VideoIdPageResponse second = saveService.listSavedVideos(1L, first.nextCursor(), 2);
        assertThat(first.videoIds()).doesNotContainAnyElementsOf(second.videoIds());
        assertThat(second.videoIds()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void listSavedVideos_onlyReturnsTheCallersOwnSaves() {
        saveService.save(30L, 1L);
        saveService.save(31L, 2L);

        assertThat(saveService.listSavedVideos(1L, null, 20).videoIds()).containsExactly(30L);
    }

    @Test
    void listSavedVideos_ordersByWhenItWasSavedNotByVideoId() {
        // 50 is the newer video, saved first; 40 is the older one, saved second. Clustering on
        // video_id would put 50 on top — the listing has to put 40 there.
        saveService.save(50L, 1L);
        saveService.save(40L, 1L);

        assertThat(saveService.listSavedVideos(1L, null, 20).videoIds()).containsExactly(40L, 50L);

        // The same two videos saved the other way round, so neither ordering of video_id can
        // produce both expectations and the assertion is actually about the save time.
        saveService.save(40L, 2L);
        saveService.save(50L, 2L);

        assertThat(saveService.listSavedVideos(2L, null, 20).videoIds()).containsExactly(50L, 40L);
    }

    @Test
    void unsave_thenSaveAgain_movesItBackToTheTop() {
        saveService.save(60L, 1L);
        saveService.save(61L, 1L);
        saveService.unsave(60L, 1L);
        saveService.save(60L, 1L);

        assertThat(saveService.listSavedVideos(1L, null, 20).videoIds()).containsExactly(60L, 61L);
    }

    @Test
    void listSavedVideos_withACursorThisServiceNeverIssued_isABadRequest() {
        assertThatThrownBy(() -> saveService.listSavedVideos(1L, "not-a-cursor!!", 20))
                .isInstanceOf(InvalidCursorException.class);
    }
}
