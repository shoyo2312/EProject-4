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
    default ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                              ModerationActionType actionType, String reason) {
        return moderate(adminId, targetType, targetId, actionType, reason, null);
    }

    /**
     * @param banDays how long a BAN_USER lasts, or null for a ban that does not lapse. Ignored
     *                for every other action type.
     */
    ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                      ModerationActionType actionType, String reason, Integer banDays);

    /**
     * How many times this target has been banned, taken down or had a comment removed — what the
     * console calls strikes.
     *
     * <p>Counts enforcement only. A reversal does not subtract: an unban is a decision about
     * whether the account is usable today, not a finding that the ban never happened, and a
     * count that quietly forgets last month's takedown is the one an admin would be misled by.
     */
    long countStrikes(ReportTargetType targetType, String targetId);

    Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Pageable pageable);

    /** Number of reports filed against one target — the console shows it next to that user/video. */
    long countReports(ReportTargetType targetType, String targetId);

    StatsSummaryResponse getStatsSummary();
}
