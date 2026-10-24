package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.SaveKeywordFilterRequest;
import com.tiktok.userservice.dto.response.KeywordFilterResponse;
import com.tiktok.userservice.exception.DuplicateKeywordFilterException;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.KeywordFilterNotFoundException;
import com.tiktok.userservice.exception.TooManyKeywordFiltersException;
import com.tiktok.userservice.repository.FeedKeywordFilterRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FeedKeywordFilterServiceImplTest {

    private static final Long USER = 1L;
    private static final Long OTHER = 2L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FeedKeywordFilterService service;

    @Autowired
    private FeedKeywordFilterRepository repository;

    @Autowired
    private AccountStateService accountStateService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static SaveKeywordFilterRequest forYou(String keyword) {
        return new SaveKeywordFilterRequest(keyword, true, false, false);
    }

    @Test
    void create_sanitizesAndKeepsTheSpellingTyped() {
        KeywordFilterResponse created = service.create(USER, forYou("  Cat "));

        assertThat(created.keyword()).isEqualTo("Cat");
        assertThat(service.list(USER)).extracting(KeywordFilterResponse::keyword).containsExactly("Cat");
    }

    @Test
    void create_rejectsTwoWords() {
        assertThatThrownBy(() -> service.create(USER, forYou("cat video")))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("keyword");
    }

    @Test
    void create_acceptsAHashtag_butNotAHashInsideTheWord() {
        assertThat(service.create(USER, forYou("#cat")).keyword()).isEqualTo("#cat");

        assertThatThrownBy(() -> service.create(USER, forYou("cat#dog")))
                .isInstanceOf(InvalidUserSettingsException.class);
    }

    /** Only emoji or punctuation: it would never match a thing, and the viewer would think it did. */
    @Test
    void create_rejectsAKeywordWithNothingToMatch() {
        assertThatThrownBy(() -> service.create(USER, forYou("#🔥🔥")))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("keyword");
    }

    @Test
    void create_rejects201CodePoints() {
        assertThat(service.create(USER, forYou("a".repeat(200))).keyword()).hasSize(200);

        assertThatThrownBy(() -> service.create(USER, forYou("b".repeat(201))))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("200");
    }

    @Test
    void create_rejectsNoFeed() {
        assertThatThrownBy(() -> service.create(USER, new SaveKeywordFilterRequest("cat", false, false, false)))
                .isInstanceOf(InvalidUserSettingsException.class);
    }

    @Test
    void create_rejectsACaseInsensitiveDuplicate() {
        service.create(USER, forYou("Cat"));

        assertThatThrownBy(() -> service.create(USER, forYou("cAT")))
                .isInstanceOf(DuplicateKeywordFilterException.class);
        // Someone else's list is theirs.
        assertThat(service.create(OTHER, forYou("cat")).keyword()).isEqualTo("cat");
    }

    @Test
    void create_rejectsTheHundredAndFirst() {
        for (int i = 0; i < FeedKeywordFilterService.MAX_FILTERS; i++) {
            service.create(USER, forYou("k" + i));
        }

        assertThatThrownBy(() -> service.create(USER, forYou("one-too-many")))
                .isInstanceOf(TooManyKeywordFiltersException.class);
    }

    @Test
    void list_isNewestFirst() {
        service.create(USER, forYou("first"));
        service.create(USER, forYou("second"));

        assertThat(service.list(USER)).extracting(KeywordFilterResponse::keyword).containsExactly("second", "first");
    }

    @Test
    void update_replacesTheKeywordAndTheFeeds() {
        Long id = service.create(USER, forYou("cat")).id();

        KeywordFilterResponse updated = service.update(USER, id, new SaveKeywordFilterRequest("Dog", false, true, true));

        assertThat(updated).isEqualTo(new KeywordFilterResponse(id, "Dog", false, true, true));
        assertThat(service.list(USER)).containsExactly(updated);
    }

    @Test
    void update_onlyCollidesWithAnotherFilter() {
        service.create(USER, forYou("cat"));
        Long dog = service.create(USER, forYou("dog")).id();

        assertThatThrownBy(() -> service.update(USER, dog, forYou("CAT")))
                .isInstanceOf(DuplicateKeywordFilterException.class);
        // Its own keyword, respelled, is not a duplicate of itself.
        assertThat(service.update(USER, dog, forYou("DOG")).keyword()).isEqualTo("DOG");
    }

    @Test
    void updateAndDelete_onlyTheOwnersRow() {
        Long theirs = service.create(OTHER, forYou("cat")).id();

        assertThatThrownBy(() -> service.update(USER, theirs, forYou("dog")))
                .isInstanceOf(KeywordFilterNotFoundException.class);
        assertThatThrownBy(() -> service.delete(USER, theirs))
                .isInstanceOf(KeywordFilterNotFoundException.class);
        assertThat(service.list(OTHER)).extracting(KeywordFilterResponse::keyword).containsExactly("cat");
    }

    @Test
    void delete_freesTheKeywordForReuse() {
        Long id = service.create(USER, forYou("cat")).id();

        service.delete(USER, id);

        assertThat(service.list(USER)).isEmpty();
        assertThat(service.create(USER, forYou("cat")).keyword()).isEqualTo("cat");
        assertThatThrownBy(() -> service.delete(USER, id)).isInstanceOf(KeywordFilterNotFoundException.class);
    }

    @Test
    void erasingTheAccount_takesItsKeywordsWithIt() {
        service.create(USER, forYou("cat"));

        accountStateService.erase(USER, Instant.now());

        assertThat(service.list(USER)).isEmpty();
    }
}
