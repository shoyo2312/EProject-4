package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every filter on the audit log is optional, and a JPQL {@code :x IS NULL} against an enum or an
 * instant is the kind of thing that compiles and then fails when Postgres prepares the statement —
 * so this runs against the real schema rather than a mocked repository.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ModerationActionRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The order AdminServiceImpl asks for when the console names none; the query sets no order. */
    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

    @Autowired
    private ModerationActionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void noFilterListsTheWholeLogNewestFirst() {
        ModerationAction older = action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(5));
        ModerationAction newer = action(2L, ModerationActionType.WARN_USER, "200", hoursAgo(1));

        assertThat(ids(repository.findFiltered(null, null, null, null, null, null, FIRST_PAGE).getContent()))
                .containsExactly(newer.getId(), older.getId());
    }

    /**
     * The order comes from the pageable. It used to be written into the query, ahead of anything
     * the pageable appended, so asking for another order changed nothing.
     */
    @Test
    void ordersByTheRequestedColumnWithTimeBreakingTies() {
        ModerationAction oldBan = action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(5));
        ModerationAction warning = action(1L, ModerationActionType.WARN_USER, "200", hoursAgo(3));
        ModerationAction newBan = action(1L, ModerationActionType.BAN_USER, "300", hoursAgo(1));

        PageRequest byType = PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "actionType")
                .and(Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        assertThat(ids(repository.findFiltered(null, null, null, null, null, null, byType).getContent()))
                .containsExactly(newBan.getId(), oldBan.getId(), warning.getId());

        PageRequest oldestFirst = PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        assertThat(ids(repository.findFiltered(null, null, null, null, null, null, oldestFirst).getContent()))
                .containsExactly(oldBan.getId(), warning.getId(), newBan.getId());
    }

    @Test
    void narrowsToOneAdmin() {
        ModerationAction mine = action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(2));
        action(2L, ModerationActionType.BAN_USER, "200", hoursAgo(1));

        assertThat(ids(repository.findFiltered(null, null, 1L, null, null, null, FIRST_PAGE).getContent()))
                .containsExactly(mine.getId());
    }

    @Test
    void narrowsToOneKindOfDecision() {
        action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(2));
        ModerationAction warning = action(1L, ModerationActionType.WARN_USER, "100", hoursAgo(1));

        assertThat(ids(repository.findFiltered(
                null, null, null, ModerationActionType.WARN_USER, null, null, FIRST_PAGE).getContent()))
                .containsExactly(warning.getId());
    }

    /** From inclusive, to exclusive, so consecutive days tile the log without a row counted twice. */
    @Test
    void narrowsToATimeRange() {
        Instant dayStart = Instant.parse("2026-10-06T00:00:00Z");
        Instant dayEnd = Instant.parse("2026-10-07T00:00:00Z");
        action(1L, ModerationActionType.BAN_USER, "100", dayStart.minusMillis(1));
        ModerationAction first = action(1L, ModerationActionType.BAN_USER, "200", dayStart);
        ModerationAction last = action(1L, ModerationActionType.BAN_USER, "300", dayEnd.minusMillis(1));
        action(1L, ModerationActionType.BAN_USER, "400", dayEnd);

        assertThat(ids(repository.findFiltered(null, null, null, null, dayStart, dayEnd, FIRST_PAGE).getContent()))
                .containsExactly(last.getId(), first.getId());
    }

    @Test
    void anOpenEndedRangeIsBoundedOnOneSideOnly() {
        Instant cut = hoursAgo(3);
        ModerationAction before = action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(5));
        ModerationAction after = action(1L, ModerationActionType.BAN_USER, "200", hoursAgo(1));

        assertThat(ids(repository.findFiltered(null, null, null, null, cut, null, FIRST_PAGE).getContent()))
                .containsExactly(after.getId());
        assertThat(ids(repository.findFiltered(null, null, null, null, null, cut, FIRST_PAGE).getContent()))
                .containsExactly(before.getId());
    }

    /** "What did this admin do yesterday" asks all of them at once. */
    @Test
    void filtersCombine() {
        ModerationAction match = action(1L, ModerationActionType.WARN_USER, "100", hoursAgo(2));
        action(2L, ModerationActionType.WARN_USER, "100", hoursAgo(2));
        action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(2));
        action(1L, ModerationActionType.WARN_USER, "100", hoursAgo(30));

        assertThat(ids(repository.findFiltered(
                null, null, 1L, ModerationActionType.WARN_USER, hoursAgo(24), null, FIRST_PAGE).getContent()))
                .containsExactly(match.getId());
    }

    @Test
    void stillNarrowsToOneTarget() {
        ModerationAction onTarget = action(1L, ModerationActionType.BAN_USER, "100", hoursAgo(2));
        action(1L, ModerationActionType.BAN_USER, "200", hoursAgo(1));

        assertThat(ids(repository.findFiltered(
                ReportTargetType.USER, "100", null, null, null, null, FIRST_PAGE).getContent()))
                .containsExactly(onTarget.getId());
    }

    private ModerationAction action(long adminId, ModerationActionType type, String userId, Instant createdAt) {
        ModerationAction saved = repository.saveAndFlush(ModerationAction.builder()
                .adminId(adminId)
                .actionType(type)
                .targetType(ReportTargetType.USER)
                .targetId(userId)
                .reason("test")
                .build());
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE moderation_actions SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        // Backdated behind the persistence context's back: without this the query results would
        // come out of it carrying their insert-time timestamps.
        entityManager.clear();
        return saved;
    }

    private static List<Long> ids(List<ModerationAction> actions) {
        return actions.stream().map(ModerationAction::getId).toList();
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }
}
