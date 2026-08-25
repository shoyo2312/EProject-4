package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.SaveByUserTime;
import com.tiktok.interactionservice.entity.SaveByUserTimeKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface SaveByUserTimeRepository extends CassandraRepository<SaveByUserTime, SaveByUserTimeKey> {

    /** Newest save first, straight off the clustering order — no ORDER BY needed. */
    @Query("SELECT * FROM saves_by_user_time WHERE user_id = :userId")
    Slice<SaveByUserTime> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
