package com.tiktok.paymentservice.repository;

import com.tiktok.paymentservice.entity.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

    boolean existsByEventId(String eventId);
}
