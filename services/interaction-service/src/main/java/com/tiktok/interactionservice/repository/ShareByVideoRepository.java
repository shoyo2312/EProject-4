package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.entity.ShareByVideo;
import com.tiktok.interactionservice.entity.ShareByVideoKey;
import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ShareByVideoRepository extends CassandraRepository<ShareByVideo, ShareByVideoKey> {
}
