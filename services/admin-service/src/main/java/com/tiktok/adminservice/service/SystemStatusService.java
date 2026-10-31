package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.response.OutboxBacklogResponse;
import com.tiktok.adminservice.dto.response.TextModerationStatusResponse;

import java.util.List;

/** What the services report about themselves, gathered for the console's System screens. */
public interface SystemStatusService {

    /** Every outbox's backlog: this service's own, and each configured service's as its actuator reports it. */
    List<OutboxBacklogResponse> outboxes();

    /** Whether each service that screens text acts on what it finds, as the service itself reports it. */
    List<TextModerationStatusResponse> textModeration();
}
