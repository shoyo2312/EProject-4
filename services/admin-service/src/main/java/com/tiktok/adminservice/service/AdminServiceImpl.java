package com.tiktok.adminservice.service;

import com.tiktok.adminservice.client.AccountRoleClient;
import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.DailyAdminStatsResponse;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportGroupResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.event.producer.AdminEventProducer;
import com.tiktok.adminservice.exception.InvalidModerationTargetException;
import com.tiktok.adminservice.exception.ReportAlreadyResolvedException;
import com.tiktok.adminservice.exception.ReportAlreadySubmittedException;
import com.tiktok.adminservice.exception.ReportNotFoundException;
import com.tiktok.adminservice.mapper.AdminMapper;
import com.tiktok.adminservice.repository.ModerationActionRepository;
import com.tiktok.adminservice.repository.ReportQueueRow;
import com.tiktok.adminservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ReportRepository reportRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final AdminMapper adminMapper;
    private final AdminEventProducer adminEventProducer;
    private final AccountRoleClient accountRoleClient;

    @Override
    @Transactional
    public ReportResponse submitReport(Long reporterId, SubmitReportRequest request) {
        // Checked here and not only at resolve time: an id nobody can act on is a report that
        // cannot be closed, discovered days later by the admin who tries. Stored in its one
        // spelling, or "007" and "7" would be two targets — see ReportTargetType.
        String targetId = request.targetType().canonicalTargetId(request.targetId());

        // The ordinary repeat — the same user reporting the same thing again — answers with the
        // report they already filed. Reading first rather than inserting and recovering: Postgres
        // aborts the transaction on a constraint violation, so a SELECT in the catch block runs
        // against a dead transaction and fails with "current transaction is aborted".
        Optional<Report> existing = reportRepository
                .findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                        reporterId, request.targetType(), targetId);
        if (existing.isPresent()) {
            return adminMapper.toResponse(existing.get());
        }

        // Reports that arrive after the target was already banned, taken down or removed are the
        // bulk of a busy queue: the video is gone, everyone who saw it before reports it, and an
        // admin opens fifty rows to decide nothing. They are recorded — the report is still the
        // record that someone objected — and closed on arrival.
        boolean alreadyEnforced = standingEnforcement(request.targetType(), targetId).isPresent();

        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(request.targetType())
                .targetId(targetId)
                .reason(request.reason())
                .status(alreadyEnforced ? ReportStatus.RESOLVED : ReportStatus.PENDING)
                // resolvedBy stays null, which is how the console tells a decision the service
                // made from one an admin made.
                .resolvedAt(alreadyEnforced ? Instant.now() : null)
                .build();

        Report saved;
        try {
            // Flushed here so the unique index answers inside this method. Left to commit time it
            // would surface as a raw DataIntegrityViolationException from the transaction proxy
            // and be served as a 500.
            saved = reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent submit of the same report. 409 rather than the
            // other request's row, because that row cannot be read from this transaction any more.
            // Only for that index: any other violation is a bug on our side, and calling it a
            // duplicate told the reporter their report was filed when nothing was.
            if (e.getCause() instanceof ConstraintViolationException cause
                    && "idx_reports_one_per_reporter_target".equalsIgnoreCase(cause.getConstraintName())) {
                throw new ReportAlreadySubmittedException();
            }
            throw e;
        }

        if (!alreadyEnforced) {
            adminEventProducer.publishReportSubmitted(saved);
        }
        return adminMapper.toResponse(saved);
    }

    @Override
    public Page<ReportResponse> listReports(ReportStatus status, ReportTargetType targetType,
                                            String targetId, Long reporterId, Pageable pageable) {
        // Canonical only when the type says how: a bare targetId filter is matched as typed.
        String id = targetType != null && targetId != null ? targetType.canonicalTargetId(targetId) : targetId;
        return reportRepository.findFiltered(status, targetType, id, reporterId, pageable)
                .map(adminMapper::toResponse);
    }

    @Override
    public Page<ReportGroupResponse> listReportQueue(Pageable pageable) {
        // Page and size only: the query carries its own ORDER BY, and a sort from the query
        // string would be appended to it as a second one that never gets a chance to apply.
        // Ordering the worklist is the queue's job, not the caller's.
        Page<ReportQueueRow> rows = reportRepository.findPendingQueue(
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        return rows.map(row -> new ReportGroupResponse(
                ReportTargetType.valueOf(row.getTargetType()),
                row.getTargetId(),
                row.getReportCount(),
                row.getFirstReportedAt(),
                row.getLastReportedAt(),
                row.getLatestReason(),
                row.getSeverity(),
                row.getPriority()));
    }

    @Override
    public ReportResponse getReport(Long reportId) {
        return adminMapper.toResponse(findVisible(reportId));
    }

    @Override
    @Transactional
    public ReportResponse resolveReport(Long adminId, Long reportId, ResolveReportRequest request) {
        Report report = findVisible(reportId);

        if (report.getStatus() != ReportStatus.PENDING) {
            throw new ReportAlreadyResolvedException(reportId);
        }

        // The request picks an action, the report supplies the target, and nothing else pairs
        // them up — see ModerationActionType. Reports predating the submit-time check above can
        // still carry an id this action cannot use, so both halves are verified.
        request.actionType().requireApplicableTo(report.getTargetType());
        String targetId = report.getTargetType().canonicalTargetId(report.getTargetId());
        if (!request.actionType().answersReports()) {
            throw new InvalidModerationTargetException(
                    request.actionType() + " is not a decision on a report");
        }

        record(adminId, report.getTargetType(), targetId,
                request.actionType(), request.reason(), report.getId(),
                bannedUntil(request.actionType(), request.banDays()));

        ReportStatus finalStatus = request.actionType().resolutionStatus();
        report.resolve(adminId, finalStatus);

        // Everyone else who reported the same thing is answered by the same decision. Without
        // this their reports stay PENDING and come back up the queue as a target with no
        // history — the admin who takes it next sees a video already down and decides it again.
        reportRepository.closePendingFor(report.getTargetType(), targetId,
                finalStatus, adminId, report.getId(), Instant.now());
        adminEventProducer.publishReportsResolved(report.getTargetType(), targetId);

        return adminMapper.toResponse(report);
    }

    /**
     * Implemented here rather than as a {@code default} on the interface: a default method is
     * self-invocation once the proxy calls through to it, so the {@code @Transactional} on the
     * overload below never applied. The two repository writes below still committed on their own
     * (Spring Data wraps {@code save}), the event published and the status changed — and then the
     * bulk {@code closePendingFor} update failed with TransactionRequiredException, so the caller
     * got a 500 for an action that had in fact happened.
     */
    @Override
    @Transactional
    public ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                            ModerationActionType actionType, String reason) {
        return moderate(adminId, targetType, targetId, actionType, reason, null);
    }

    @Override
    @Transactional
    public ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                            ModerationActionType actionType, String reason, Integer banDays) {
        actionType.requireApplicableTo(targetType);
        String id = targetType.canonicalTargetId(targetId);

        ModerationAction action = record(adminId, targetType, id, actionType, reason, null,
                bannedUntil(actionType, banDays));

        // The console resolves a whole queue row through this path — one decision, every
        // standing report against that target closed. It is also what a takedown taken straight
        // from the video listing does, which is the point: an action nobody tied to a report
        // still answers the reports, and leaving them PENDING is how the same video comes back
        // to the top of the queue an hour after it was dealt with. A purge is the exception: it
        // says nothing about whether the reports were right, so they stay where they are.
        if (actionType.answersReports()) {
            reportRepository.closePendingFor(targetType, id, actionType.resolutionStatus(),
                    adminId, null, Instant.now());
            adminEventProducer.publishReportsResolved(targetType, id);
        }

        return adminMapper.toResponse(action);
    }

    @Override
    public Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Long adminId,
                                                      ModerationActionType actionType, Instant from, Instant to,
                                                      Pageable pageable) {
        // Both or neither. Silently ignoring a half-filled filter answers a narrow question with
        // the whole platform's audit log, which reads as "no actions were taken against this one".
        if (targetType == null ^ targetId == null) {
            throw new InvalidModerationTargetException(
                    "targetType and targetId must be given together, or both omitted");
        }

        String id = targetType != null ? targetType.canonicalTargetId(targetId) : null;
        return moderationActionRepository.findFiltered(targetType, id, adminId, actionType, from, to, pageable)
                .map(adminMapper::toResponse);
    }

    @Override
    public long countStrikes(ReportTargetType targetType, String targetId) {
        return moderationActionRepository.countByTargetTypeAndTargetIdAndActionTypeIn(
                targetType, targetType.canonicalTargetId(targetId), ModerationActionType.ENFORCING);
    }

    @Override
    public long countReports(ReportTargetType targetType, String targetId) {
        return reportRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(
                targetType, targetType.canonicalTargetId(targetId));
    }

    @Override
    public StatsSummaryResponse getStatsSummary() {
        return new StatsSummaryResponse(
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.PENDING),
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.RESOLVED),
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.DISMISSED),
                moderationActionRepository.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    /**
     * Column offsets into the per-day accumulator in {@link #getDailyStats}. Three queries each
     * fill in part of the same row, so the row cannot be built in one pass.
     */
    private static final int CREATED = 0;
    private static final int TAKEN = 1;
    private static final int RESOLVED = 2;
    private static final int DISMISSED = 3;
    private static final int BANNED = 4;
    private static final int TAKEN_DOWN = 5;
    private static final int REMOVED = 6;
    private static final int COLUMNS = 7;

    @Override
    public List<DailyAdminStatsResponse> getDailyStats(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        // Sorted, and keyed by day rather than indexed by offset: a day on which nothing at all
        // happened has no row in any of the three queries, and the console reads this as a
        // series — a missing day is a gap it can see, an invented zero-filled one is not.
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (var row : reportRepository.findDailyCreated(since)) {
            byDay.computeIfAbsent(row.getDay(), d -> new long[COLUMNS])[CREATED] = row.getCnt();
        }
        for (var row : reportRepository.findDailyResolutions(since)) {
            long[] day = byDay.computeIfAbsent(row.getDay(), d -> new long[COLUMNS]);
            day[RESOLVED] = row.getResolved();
            day[DISMISSED] = row.getDismissed();
        }
        for (var row : moderationActionRepository.findDailyActions(since)) {
            long[] day = byDay.computeIfAbsent(row.getDay(), d -> new long[COLUMNS]);
            day[TAKEN] = row.getTaken();
            day[BANNED] = row.getUsersBanned();
            day[TAKEN_DOWN] = row.getVideosTakenDown();
            day[REMOVED] = row.getCommentsRemoved();
        }
        return byDay.entrySet().stream()
                .map(e -> {
                    long[] d = e.getValue();
                    return new DailyAdminStatsResponse(e.getKey(), d[CREATED], d[TAKEN],
                            d[RESOLVED], d[DISMISSED], d[BANNED], d[TAKEN_DOWN], d[REMOVED]);
                })
                .toList();
    }

    /**
     * The enforcement currently standing against a target, if any — the newest ban, takedown or
     * removal that has not since been undone or run out. Empty means nothing has been done to it,
     * the last word was a restore or an unban, or the last word was a ban whose term is over.
     *
     * <p>Read from this service's own audit log rather than asked of video-service or
     * auth-service: §6 forbids reaching into another service's database, and an HTTP call per
     * submitted report would put the report path behind another service's availability for an
     * answer this one already holds.
     *
     * <p>A temporary ban ends without anything landing in that log: auth-service's sweep lifts it
     * and tells nobody. Its end date is in the log, though, and has to be read — otherwise the
     * ban's audit row stands forever, and every report against the account it no longer holds is
     * closed on arrival and never reaches an admin.
     */
    private Optional<ModerationAction> standingEnforcement(ReportTargetType targetType, String targetId) {
        Instant now = Instant.now();
        return moderationActionRepository
                .findFirstByTargetTypeAndTargetIdAndActionTypeInOrderByCreatedAtDesc(
                        targetType, targetId, ModerationActionType.STATE_CHANGING)
                .filter(action -> action.getActionType().effect() == ModerationActionType.Effect.ENFORCE)
                .filter(action -> action.getBannedUntil() == null || action.getBannedUntil().isAfter(now));
    }

    /**
     * Only a ban has a duration, and only here is it turned into an instant: computing it at the
     * consumer would date the ban from whenever the event happened to be delivered.
     */
    private static Instant bannedUntil(ModerationActionType actionType, Integer banDays) {
        return banDays != null && actionType == ModerationActionType.BAN_USER
                ? Instant.now().plus(banDays, ChronoUnit.DAYS)
                : null;
    }

    /** Writes the audit row and the outbox event for one decision. */
    private ModerationAction record(Long adminId, ReportTargetType targetType, String targetId,
                                    ModerationActionType actionType, String reason, Long reportId,
                                    Instant bannedUntil) {
        // auth-service refuses to ban any admin account, since only it knows roles, and its refusal
        // reached nobody: this side kept an audit row for a ban that never landed, closed the
        // reports against the account as handled, and the services that hide a banned owner's
        // videos hid an admin's. So a ban asks the role first. The self-ban check stays ahead of
        // it — it needs no call, and still holds when auth-service cannot be asked.
        if (actionType == ModerationActionType.BAN_USER && targetId.equals(String.valueOf(adminId))) {
            throw new InvalidModerationTargetException("An admin cannot ban their own account");
        }
        if (actionType == ModerationActionType.BAN_USER && accountRoleClient.isAdmin(Long.valueOf(targetId))) {
            throw new InvalidModerationTargetException("An admin account cannot be banned");
        }
        // No existence check against the owning service: this one cannot read its database, and an
        // extra HTTP call would only move the failure. An action against an id that does not exist
        // is a no-op on the consumer side, and the audit row is still the honest record of the
        // decision. Consumers are required to ignore unknown ids for exactly this reason.
        ModerationAction action = ModerationAction.builder()
                .adminId(adminId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .reportId(reportId)
                .bannedUntil(bannedUntil)
                .build();
        moderationActionRepository.save(action);
        adminEventProducer.publishFor(action);
        return action;
    }

    private Report findVisible(Long reportId) {
        return reportRepository.findByIdAndDeletedAtIsNull(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
    }
}
