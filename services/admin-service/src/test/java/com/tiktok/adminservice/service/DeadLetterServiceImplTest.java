package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.response.DeadLetterBatchReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterRecordsResponse;
import com.tiktok.adminservice.dto.response.DeadLetterReplayResponse;
import com.tiktok.adminservice.entity.DeadLetterReplay;
import com.tiktok.adminservice.exception.DeadLetterNotFoundException;
import com.tiktok.adminservice.exception.DeadLetterTopicNotFoundException;
import com.tiktok.adminservice.exception.KafkaUnavailableException;
import com.tiktok.adminservice.exception.NotADeadLetterTopicException;
import com.tiktok.adminservice.mapper.DeadLetterMapper;
import com.tiktok.adminservice.repository.DeadLetterReplayRepository;
import com.tiktok.kafka.dlt.DeadLetter;
import com.tiktok.kafka.dlt.DeadLetterTopic;
import com.tiktok.kafka.dlt.DeadLetters;
import com.tiktok.kafka.dlt.ReplayResult;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static com.tiktok.kafka.dlt.ReplayResult.Outcome.HELD_BACK;
import static com.tiktok.kafka.dlt.ReplayResult.Outcome.NOT_FOUND;
import static com.tiktok.kafka.dlt.ReplayResult.Outcome.REPLAYED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceImplTest {

    private static final String TOPIC = "video.video-events.DLT";
    private static final Instant PARKED = Instant.parse("2026-10-24T08:00:00Z");

    @Mock
    private DeadLetters deadLetters;

    @Mock
    private DeadLetterReplayRepository replayRepository;

    private DeadLetterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterServiceImpl(deadLetters, replayRepository, Mappers.getMapper(DeadLetterMapper.class));
    }

    /**
     * Offsets start over when a DLT is deleted and created again, so a replay row only belongs to a
     * record parked at the same moment — and only in the same partition.
     */
    @Test
    void aRecordShowsItsOwnLatestReplay() {
        when(deadLetters.topic(TOPIC)).thenReturn(new DeadLetterTopic(TOPIC, "video.video-events", 2, 1, PARKED));
        when(deadLetters.latest(TOPIC, 50)).thenReturn(List.of(letter(0, 7), letter(1, 7)));
        when(replayRepository.findByDltTopicAndDltOffsetIn(TOPIC, List.of(7L))).thenReturn(List.of(
                row(1L, 0, 7, PARKED, HELD_BACK),
                row(2L, 0, 7, PARKED, REPLAYED),
                row(3L, 0, 7, PARKED.minus(Duration.ofDays(40)), HELD_BACK)));

        DeadLetterRecordsResponse response = service.listRecords(TOPIC, 50);

        assertThat(response.topic().pending()).isEqualTo(1);
        assertThat(response.records().get(0).lastReplay().outcome()).isEqualTo(REPLAYED);
        assertThat(response.records().get(1).lastReplay()).isNull();
    }

    @Test
    void aReplayIsRecordedWithWhoForcedIt() {
        when(deadLetters.replay(TOPIC, 0, 7, true))
                .thenReturn(new ReplayResult(TOPIC, 0, 7, PARKED, REPLAYED, "video.video-events-0@90"));

        DeadLetterReplayResponse response = service.replay(42L, TOPIC, 0, 7, true);

        assertThat(response.outcome()).isEqualTo(REPLAYED);
        assertThat(response.detail()).isEqualTo("video.video-events-0@90");
        ArgumentCaptor<DeadLetterReplay> saved = ArgumentCaptor.forClass(DeadLetterReplay.class);
        verify(replayRepository).save(saved.capture());
        assertThat(saved.getValue()).satisfies(row -> {
            assertThat(row.getAdminId()).isEqualTo(42L);
            assertThat(row.isForced()).isTrue();
            assertThat(row.getDltTopic()).isEqualTo(TOPIC);
            assertThat(row.getDltOffset()).isEqualTo(7);
            assertThat(row.getParkedAt()).isEqualTo(PARKED);
            assertThat(row.getOutcome()).isEqualTo(REPLAYED);
        });
    }

    @Test
    void aRecordThatIsNotThereIsNotFoundAndNotRecorded() {
        when(deadLetters.replay(TOPIC, 0, 99, false)).thenReturn(new ReplayResult(TOPIC, 0, 99, null, NOT_FOUND, null));

        assertThatThrownBy(() -> service.replay(42L, TOPIC, 0, 99, false))
                .isInstanceOf(DeadLetterNotFoundException.class);
        verifyNoInteractions(replayRepository);
    }

    @Test
    void replayingEveryPendingRecordNeverForcesAndRecordsEachOne() {
        when(deadLetters.replayPending(eq(TOPIC), eq(false), eq(DeadLetterServiceImpl.REPLAY_BATCH), any()))
                .thenAnswer(invocation -> {
                    Consumer<ReplayResult> each = invocation.getArgument(3);
                    each.accept(new ReplayResult(TOPIC, 0, 7, PARKED, HELD_BACK,
                            "video.video-events-0@12 holds a later VideoRestoredEvent for key v1"));
                    each.accept(new ReplayResult(TOPIC, 0, 8, PARKED, REPLAYED, "video.video-events-0@91"));
                    return 2;
                });
        when(deadLetters.topic(TOPIC)).thenReturn(new DeadLetterTopic(TOPIC, "video.video-events", 9, 3, PARKED));

        DeadLetterBatchReplayResponse response = service.replayPending(42L, TOPIC);

        assertThat(response.results()).extracting(DeadLetterReplayResponse::outcome).containsExactly(HELD_BACK, REPLAYED);
        assertThat(response.stillPending()).isEqualTo(3);
        verify(replayRepository, times(2)).save(argThat(row -> !row.isForced() && row.getAdminId() == 42L));
    }

    /** What was done before the broker went away is on record; the console is told to try again. */
    @Test
    void aBrokerThatGoesAwayMidwayLeavesTheRowsForWhatWasDone() {
        when(deadLetters.replayPending(eq(TOPIC), eq(false), eq(DeadLetterServiceImpl.REPLAY_BATCH), any()))
                .thenAnswer(invocation -> {
                    Consumer<ReplayResult> each = invocation.getArgument(3);
                    each.accept(new ReplayResult(TOPIC, 0, 7, PARKED, REPLAYED, "video.video-events-0@90"));
                    throw new TimeoutException("Gave up reading");
                });

        assertThatThrownBy(() -> service.replayPending(42L, TOPIC)).isInstanceOf(KafkaUnavailableException.class);
        verify(replayRepository).save(any());
    }

    @Test
    void onlyADeadLetterTopicIsTouched() {
        assertThatThrownBy(() -> service.replay(42L, "video.video-events", 0, 7, true))
                .isInstanceOf(NotADeadLetterTopicException.class);
        assertThatThrownBy(() -> service.listRecords("video.video-events", 50))
                .isInstanceOf(NotADeadLetterTopicException.class);
        verifyNoInteractions(deadLetters, replayRepository);
    }

    @Test
    void aTopicTheBrokerDoesNotHaveIsNotFound() {
        when(deadLetters.topic(TOPIC)).thenThrow(new UnknownTopicOrPartitionException("No topic " + TOPIC));

        assertThatThrownBy(() -> service.listRecords(TOPIC, 50)).isInstanceOf(DeadLetterTopicNotFoundException.class);
    }

    private static DeadLetter letter(int partition, long offset) {
        return new DeadLetter(TOPIC, partition, offset, PARKED, "v1", "VideoPublishedEvent",
                "video.video-events-" + partition + "@3", "search-service", "java.lang.IllegalStateException: bug", true);
    }

    private static DeadLetterReplay row(long id, int partition, long offset, Instant parkedAt, ReplayResult.Outcome outcome) {
        return DeadLetterReplay.builder().id(id).adminId(7L).dltTopic(TOPIC).dltPartition(partition).dltOffset(offset)
                .parkedAt(parkedAt).outcome(outcome).createdAt(PARKED.plusSeconds(id)).build();
    }
}
