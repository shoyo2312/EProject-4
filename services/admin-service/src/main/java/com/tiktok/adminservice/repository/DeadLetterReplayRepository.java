package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.DeadLetterReplay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeadLetterReplayRepository extends JpaRepository<DeadLetterReplay, Long> {

    /** Partition and parked-at are matched by the caller: offsets are per partition. */
    List<DeadLetterReplay> findByDltTopicAndDltOffsetIn(String dltTopic, Collection<Long> dltOffsets);
}
