package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.RepostByUser;
import com.tiktok.interactionservice.entity.RepostByUserKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface RepostByUserRepository extends CassandraRepository<RepostByUser, RepostByUserKey> {

    @Query("SELECT * FROM reposts_by_user WHERE user_id = :userId")
    Slice<RepostByUser> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
