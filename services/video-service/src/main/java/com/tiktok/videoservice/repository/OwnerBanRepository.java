package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.OwnerBan;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OwnerBanRepository extends MongoRepository<OwnerBan, Long> {

    /** The owners among {@code userIds} hidden as of {@code now}, banned or closed, in one query on _id. */
    @Query("{ '_id': { $in: ?0 }, $or: [ { 'bannedUntil': { $gt: ?1 } }, { 'deactivatedAt': { $ne: null } } ] }")
    List<OwnerBan> findHiddenAmong(Collection<Long> userIds, Instant now);

    /** Every hidden owner, for the public feed, which has no owner list to ask about. */
    @Query("{ $or: [ { 'bannedUntil': { $gt: ?0 } }, { 'deactivatedAt': { $ne: null } } ] }")
    List<OwnerBan> findAllHidden(Instant now);
}
