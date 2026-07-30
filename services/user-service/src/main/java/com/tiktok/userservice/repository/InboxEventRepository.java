package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

    boolean existsByEventId(String eventId);
}
