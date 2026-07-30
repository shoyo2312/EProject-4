package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.LikeByUser;
import com.tiktok.interactionservice.entity.LikeByUserKey;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface LikeByUserRepository extends CassandraRepository<LikeByUser, LikeByUserKey> {
}
