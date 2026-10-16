package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.AddModerationTermRequest;
import com.tiktok.adminservice.dto.request.TextPreviewRequest;
import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSummaryResponse;
import com.tiktok.adminservice.dto.response.TextPreviewResponse;
import com.tiktok.adminservice.entity.ModerationTerm;
import com.tiktok.adminservice.exception.InvalidModerationTermException;
import com.tiktok.adminservice.exception.ModerationTermExistsException;
import com.tiktok.adminservice.exception.ModerationTermNotFoundException;
import com.tiktok.adminservice.mapper.ModerationTermMapper;
import com.tiktok.adminservice.repository.ModerationTermRepository;
import com.tiktok.adminservice.repository.ModerationTermRevisions;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.moderation.rules.Blocklist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
public class ModerationTermServiceImpl implements ModerationTermService {

    /** Newest first, whatever the client asked for: the table has nothing else worth sorting by. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final ModerationTermRepository moderationTermRepository;
    private final ModerationTermRevisions moderationTermRevisions;
    private final ModerationTermMapper moderationTermMapper;
    private final TextModerator textModerator;
    private final TextClassifier textClassifier;
    /** For {@link #snapshot}, which {@link #preview} calls on itself — past the proxy's annotations. */
    private final TransactionTemplate snapshotTransaction;

    public ModerationTermServiceImpl(ModerationTermRepository moderationTermRepository,
                                     ModerationTermRevisions moderationTermRevisions,
                                     ModerationTermMapper moderationTermMapper,
                                     TextModerator textModerator,
                                     TextClassifier textClassifier,
                                     PlatformTransactionManager transactionManager) {
        this.moderationTermRepository = moderationTermRepository;
        this.moderationTermRevisions = moderationTermRevisions;
        this.moderationTermMapper = moderationTermMapper;
        this.textModerator = textModerator;
        this.textClassifier = textClassifier;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ModerationTermResponse> list(String q, AdminTerm.Action action, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.strip();
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return moderationTermRepository.search(query, action, page).map(moderationTermMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationTermSummaryResponse summary() {
        Blocklist shipped = textModerator.shippedList();
        long revision = moderationTermRevisions.current();
        return new ModerationTermSummaryResponse(shipped.version(), shipped.size(), revision,
                moderationTermRepository.countByDeletedAtIsNull(),
                "rules-" + shipped.version() + "+admin-" + revision);
    }

    /**
     * Refuses what no service could use as asked, then takes the next revision before anything else
     * — that row lock is what queues concurrent changes (see V7), and it makes the duplicate check
     * below race-free; the unique index stays as the backstop.
     */
    @Override
    @Transactional
    public ModerationTermResponse add(Long adminId, AddModerationTermRequest request) {
        String term = request.term();
        if (!Blocklist.isMatchable(term)) {
            throw new InvalidModerationTermException("term: must contain a letter or a digit");
        }
        Category category = request.action() == AdminTerm.Action.EXEMPT ? null : request.category();
        requireUsable(term, request.action(), category);

        moderationTermRevisions.next();
        String key = Blocklist.keyOf(term);
        if (moderationTermRepository.existsByTermKeyAndDeletedAtIsNull(key)) {
            throw new ModerationTermExistsException(term);
        }

        ModerationTerm saved;
        try {
            saved = moderationTermRepository.saveAndFlush(ModerationTerm.builder()
                    .term(term)
                    .termKey(key)
                    .category(category)
                    .action(request.action())
                    .note(request.note())
                    .createdBy(adminId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ModerationTermExistsException(term);
        }
        log.info("Admin {} added moderation term '{}' ({} {})", adminId, term, request.action(), category);
        return moderationTermMapper.toResponse(saved);
    }

    /**
     * A HOLD or REJECT needs a category, and not CREATOR_FILTER, which means "the video owner's own
     * setting". One that the shipped list already flags at least as hard would change nothing — the
     * more severe entry wins — so it is refused rather than left looking like it works. An EXEMPT
     * must name a shipped term: there is nothing else for it to take out.
     */
    private void requireUsable(String term, AdminTerm.Action action, Category category) {
        Blocklist shipped = textModerator.shippedList();
        if (action == AdminTerm.Action.EXEMPT) {
            if (!shipped.contains(term)) {
                throw new InvalidModerationTermException("'" + term + "' is not on the shipped list "
                        + shipped.version() + ", so there is nothing to exempt. A term written with "
                        + "diacritics and its plain spelling are different terms");
            }
            return;
        }
        if (category == null) {
            throw new InvalidModerationTermException("category: required for " + action);
        }
        if (category == Category.CREATOR_FILTER) {
            throw new InvalidModerationTermException("category: CREATOR_FILTER is for a video owner's own filters");
        }
        int severity = action == AdminTerm.Action.REJECT ? Flag.REJECT : Flag.HOLD;
        shipped.find(term)
                .filter(existing -> existing.severity() >= severity)
                .ifPresent(existing -> {
                    throw new InvalidModerationTermException("'" + term + "' is already on the shipped list as "
                            + (existing.severity() >= Flag.REJECT ? "REJECT" : "HOLD")
                            + "; an admin term can only make a shipped term stricter, or EXEMPT it");
                });
    }

    @Override
    @Transactional
    public void delete(Long adminId, Long termId) {
        moderationTermRevisions.next();
        ModerationTerm term = moderationTermRepository.findByIdAndDeletedAtIsNull(termId)
                .orElseThrow(() -> new ModerationTermNotFoundException(termId));
        term.deleteBy(adminId);
        moderationTermRepository.save(term);
        log.info("Admin {} removed moderation term '{}' ({})", adminId, term.getTerm(), term.getAction());
    }

    /**
     * Brings this service's own moderator up to the list's revision first, from the database rather
     * than by polling itself, so a term added a second ago is in the preview.
     */
    @Override
    public TextPreviewResponse preview(TextPreviewRequest request) {
        ModerationTermSnapshotResponse snapshot = snapshot();
        if (textModerator.adminRevision().orElse(-1) != snapshot.revision()) {
            textModerator.applyAdminTerms(snapshot.revision(), snapshot.terms().stream()
                    .map(term -> new AdminTerm(term.term(), term.category(), term.action()))
                    .toList());
        }
        TextVerdict rules = textModerator.screen(request.text(), request.surface());
        TextVerdict verdict = textClassifier.refine(request.text(), request.surface(), rules);
        return new TextPreviewResponse(verdict.decision(), verdict.flags(), verdict.version(),
                !verdict.version().equals(rules.version()));
    }

    /**
     * One consistent read: the revision and the terms from the same snapshot of the database.
     * Read under READ COMMITTED, a change committing between the two reads could be handed out
     * under the revision before it — and a service holding that revision would never ask again.
     */
    @Override
    public ModerationTermSnapshotResponse snapshot() {
        return snapshotTransaction.execute(status -> {
            long revision = moderationTermRevisions.current();
            List<ModerationTermSnapshotResponse.Term> terms = moderationTermRepository
                    .findByDeletedAtIsNullOrderByIdAsc()
                    .stream()
                    .map(moderationTermMapper::toSnapshotTerm)
                    .toList();
            return new ModerationTermSnapshotResponse(revision, terms);
        });
    }
}
