package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportGroupResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminService {

    ReportResponse submitReport(Long reporterId, SubmitReportRequest request);

    Page<ReportResponse> listReports(ReportStatus status, ReportTargetType targetType, Pageable pageable);

    /**
     * The queue an admin works: one row per reported target, heaviest first. Separate from
     * {@link #listReports} — that one is the report ledger, this one is the worklist, and the
     * difference is that fifty reports against one video belong on one line here.
     */
    Page<ReportGroupResponse> listReportQueue(Pageable pageable);

    ReportResponse getReport(Long reportId);

    ReportResponse resolveReport(Long adminId, Long reportId, ResolveReportRequest request);

    /**
     * A moderation decision taken straight from the console, with no report behind it.
     * {@code targetId} is a string because target ids are not one type: a user is a Snowflake
     * long, a video is a Mongo document id.
     */
    ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                      ModerationActionType actionType, String reason);

    Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Pageable pageable);

    /** Number of reports filed against one target — the console shows it next to that user/video. */
    long countReports(ReportTargetType targetType, String targetId);

    StatsSummaryResponse getStatsSummary();
}
