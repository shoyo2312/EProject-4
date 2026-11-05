package com.tiktok.adminservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** One message of a report's evidence, as it was when the report was filed. See V10. */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report_evidence")
public class ReportEvidence extends BaseEntity {

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "content")
    private String content;

    @Column(name = "sticker_id")
    private String stickerId;

    @Column(name = "video_id")
    private String videoId;

    @Column(name = "story_id")
    private String storyId;
}
