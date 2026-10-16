package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.OwnerBan;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OwnerBanRepository extends MongoRepository<OwnerBan, Long> {

    /** The owners among {@code userIds} banned as of {@code now}, in one query on _id. */
    List<OwnerBan> findByUserIdInAndBannedUntilAfter(Collection<Long> userIds, Instant now);

    /** Every ban in force, for the public feed — which has no owner list to ask about. */
    List<OwnerBan> findByBannedUntilAfter(Instant now);
}
