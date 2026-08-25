package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.LikeByUser;
import com.tiktok.interactionservice.entity.LikeByUserKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

public interface LikeByUserRepository extends CassandraRepository<LikeByUser, LikeByUserKey> {

    @Query("SELECT * FROM likes_by_user WHERE user_id = :userId")
    Slice<LikeByUser> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
