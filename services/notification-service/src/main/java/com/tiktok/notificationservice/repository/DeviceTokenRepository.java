package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.DeviceToken;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

    List<DeviceToken> findByUserId(Long userId);
}
