package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.OwnerBan;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OwnerBanRepository extends MongoRepository<OwnerBan, Long> {

    /** The owners among {@code userIds} hidden as of {@code now}, banned or closed, in one query on _id. */
    @Query("{ '_id': { $in: ?0 }, $or: [ { 'bannedUntil': { $gt: ?1 } }, { 'deactivatedAt': { $ne: null } } ] }")
    List<OwnerBan> findHiddenAmong(Collection<Long> userIds, Instant now);
}
