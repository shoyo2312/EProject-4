package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.AddModerationTermRequest;
import com.tiktok.adminservice.dto.request.TextPreviewRequest;
import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSummaryResponse;
import com.tiktok.adminservice.dto.response.TextPreviewResponse;
import com.tiktok.adminservice.exception.InvalidModerationTermException;
import com.tiktok.adminservice.exception.ModerationTermExistsException;
import com.tiktok.adminservice.exception.ModerationTermNotFoundException;
import com.tiktok.adminservice.mapper.ModerationTermMapperImpl;
import com.tiktok.adminservice.repository.ModerationTermRevisions;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The term list against the real schema: the partial unique index on the key, the revision row,
 * the search query's optional filters, and what a service's matcher gets built from.
 *
 * <p>Not wrapped in the test's own transaction: the revision row and REPEATABLE READ are the point,
 * and a surrounding transaction would hide both.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ModerationTermServiceImpl.class, ModerationTermRevisions.class, ModerationTermMapperImpl.class,
        ModerationTermServiceIntegrationTest.Moderation.class})
class ModerationTermServiceIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The shipped list as every service loads it, and no classifier — the rules' verdict alone. */
    @TestConfiguration
    static class Moderation {

        @Bean
        TextModerator textModerator() {
            return new TextModerator(Blocklist.load(new ClassPathResource("moderation/blocklist-vi.txt")),
                    new SpamSignals(List.of(), 5), true);
        }

        @Bean
        TextClassifier textClassifier() {
            return TextClassifier.disabled();
        }

        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Autowired
    private ModerationTermService service;

    @Autowired
    private ModerationTermRevisions revisions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ModerationTermResponse add(String term, Category category, AdminTerm.Action action) {
        return service.add(1L, new AddModerationTermRequest(term, category, action, null));
    }

    @org.junit.jupiter.api.BeforeEach
    void empty() {
        jdbcTemplate.update("DELETE FROM moderation_terms");
    }

    @Test
    void everyChange_movesTheRevision_deletesToo() {
        long before = revisions.current();

        ModerationTermResponse added = add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        long afterAdd = revisions.current();
        service.delete(2L, added.id());

        assertThat(afterAdd).isEqualTo(before + 1);
        assertThat(revisions.current()).isEqualTo(before + 2);
    }

    @Test
    void aSecondSpellingWithTheSameKey_isRefused_butThePlainSpellingIsItsOwnTerm() {
        add("Đồ  Mất Dạy", Category.INSULT, AdminTerm.Action.REJECT);

        assertThatThrownBy(() -> add("đồ mất dạy", Category.INSULT, AdminTerm.Action.HOLD))
                .isInstanceOf(ModerationTermExistsException.class);
        // Without diacritics it matches every spelling — a different term to the matcher.
        assertThat(add("do mat day", Category.INSULT, AdminTerm.Action.HOLD).term()).isEqualTo("do mat day");
    }

    @Test
    void aDeletedTerm_canBeAddedAgain() {
        ModerationTermResponse first = add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        service.delete(1L, first.id());

        assertThat(add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.REJECT).action())
                .isEqualTo(AdminTerm.Action.REJECT);
    }

    @Test
    void deletingTwice_isNotFound() {
        ModerationTermResponse term = add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        service.delete(1L, term.id());

        assertThatThrownBy(() -> service.delete(1L, term.id())).isInstanceOf(ModerationTermNotFoundException.class);
    }

    @Test
    void anExempt_mustNameAShippedTerm() {
        assertThatThrownBy(() -> add("không có trong danh sách", null, AdminTerm.Action.EXEMPT))
                .isInstanceOf(InvalidModerationTermException.class)
                .hasMessageContaining("not on the shipped list");
        // "ngu như bò" is shipped; its EXEMPT is stored without a category whatever was sent.
        assertThat(add("ngu như bò", Category.INSULT, AdminTerm.Action.EXEMPT).category()).isNull();
    }

    @Test
    void aHoldOrReject_needsARealCategory() {
        assertThatThrownBy(() -> add("cút đi cho khuất mắt", null, AdminTerm.Action.HOLD))
                .isInstanceOf(InvalidModerationTermException.class);
        assertThatThrownBy(() -> add("cút đi cho khuất mắt", Category.CREATOR_FILTER, AdminTerm.Action.HOLD))
                .isInstanceOf(InvalidModerationTermException.class);
        assertThatThrownBy(() -> add("!!!", Category.INSULT, AdminTerm.Action.HOLD))
                .isInstanceOf(InvalidModerationTermException.class);
    }

    /** The more severe entry wins in the matcher, so a weaker admin term would look like it works and do nothing. */
    @Test
    void aTermTheShippedListAlreadyFlagsAsHard_isRefused_butMakingOneStricterIsNot() {
        assertThatThrownBy(() -> add("dcm may", Category.PROFANITY, AdminTerm.Action.HOLD))
                .isInstanceOf(InvalidModerationTermException.class)
                .hasMessageContaining("already on the shipped list as REJECT");
        assertThat(add("ngu như bò", Category.INSULT, AdminTerm.Action.REJECT).action())
                .isEqualTo(AdminTerm.Action.REJECT);
    }

    @Test
    void theList_searchesAndFilters_newestFirst() {
        add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        add("đồ mất dạy", Category.INSULT, AdminTerm.Action.REJECT);
        add("ngu như bò", null, AdminTerm.Action.EXEMPT);

        assertThat(service.list(null, null, PageRequest.of(0, 10)).getContent())
                .extracting(ModerationTermResponse::term)
                .containsExactly("ngu như bò", "đồ mất dạy", "cút đi cho khuất mắt");
        assertThat(service.list("MẤT", null, PageRequest.of(0, 10)).getContent())
                .extracting(ModerationTermResponse::term).containsExactly("đồ mất dạy");
        assertThat(service.list(null, AdminTerm.Action.EXEMPT, PageRequest.of(0, 10)).getContent())
                .extracting(ModerationTermResponse::term).containsExactly("ngu như bò");
    }

    @Test
    void theSnapshot_isTheLiveList_atTheCurrentRevision() {
        add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        ModerationTermResponse gone = add("đồ mất dạy", Category.INSULT, AdminTerm.Action.REJECT);
        service.delete(1L, gone.id());

        ModerationTermSnapshotResponse snapshot = service.snapshot();

        assertThat(snapshot.revision()).isEqualTo(revisions.current());
        assertThat(snapshot.terms()).extracting(ModerationTermSnapshotResponse.Term::term)
                .containsExactly("cút đi cho khuất mắt");
    }

    @Test
    void theSummary_namesTheVersionAVerdictWouldRecord() {
        add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);

        ModerationTermSummaryResponse summary = service.summary();

        assertThat(summary.adminTerms()).isEqualTo(1);
        assertThat(summary.shippedTerms()).isGreaterThan(50);
        assertThat(summary.version()).isEqualTo("rules-" + summary.shippedVersion() + "+admin-" + summary.revision());
    }

    /** A term added a moment ago is in the preview: it reads the database, not a poll. */
    @Test
    void thePreview_seesTermsAddedAMomentAgo_andExemptions() {
        TextPreviewRequest request = new TextPreviewRequest("thôi cút đi cho khuất mắt", Surface.COMMENT);
        assertThat(service.preview(request).decision()).isEqualTo(Decision.ALLOW);

        add("cút đi cho khuất mắt", Category.INSULT, AdminTerm.Action.HOLD);
        TextPreviewResponse held = service.preview(request);
        assertThat(held.decision()).isEqualTo(Decision.HOLD);
        assertThat(held.version()).endsWith("+admin-" + revisions.current());
        assertThat(held.classifierAnswered()).isFalse();

        add("ngu như bò", null, AdminTerm.Action.EXEMPT);
        assertThat(service.preview(new TextPreviewRequest("ngu như bò", Surface.COMMENT)).decision())
                .isEqualTo(Decision.ALLOW);
    }
}
