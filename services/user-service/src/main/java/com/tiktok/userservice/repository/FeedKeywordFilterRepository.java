package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.FeedKeywordFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FeedKeywordFilterRepository extends JpaRepository<FeedKeywordFilter, Long> {

    /** Newest first: Snowflake ids grow with time. */
    List<FeedKeywordFilter> findByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId);

    Optional<FeedKeywordFilter> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    long countByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * Whether another of the account's filters already has this keyword, whatever its case.
     * {@code lower()} on both sides so uq_feed_keyword_filters_user_keyword answers it — a derived
     * {@code ...IgnoreCase} emits {@code upper()} and scans. Pass 0 as {@code excludeId} when
     * creating: no Snowflake id is 0.
     */
    @Query("select case when count(f) > 0 then true else false end from FeedKeywordFilter f "
            + "where f.userId = :userId and lower(f.keyword) = lower(:keyword) "
            + "and f.id <> :excludeId and f.deletedAt is null")
    boolean existsOtherWithKeyword(@Param("userId") Long userId,
                                   @Param("keyword") String keyword,
                                   @Param("excludeId") Long excludeId);

    /** Every filter of a batch of accounts, for the internal settings contract — one query per batch. */
    List<FeedKeywordFilter> findByUserIdInAndDeletedAtIsNull(Collection<Long> userIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FeedKeywordFilter f set f.deletedAt = :at where f.userId = :userId and f.deletedAt is null")
    int softDeleteAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
