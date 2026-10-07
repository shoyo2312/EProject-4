package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.AdminUserResponse;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both filters on the directory query are optional, so the interesting cases are the null ones:
 * a JPQL {@code :status IS NULL} against an enum parameter is the kind of thing that compiles and
 * then fails when the query is actually prepared. Runs against a real Postgres for that reason.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AdminUserDirectoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private MailService mailService;

    @Autowired
    private AdminUserDirectory directory;

    @Autowired
    private UserRepository userRepository;

    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt"));

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        save("johndoe", "john@example.com", UserStatus.ACTIVE);
        save("janedoe", "jane@example.com", UserStatus.BANNED);
        // A social account that never supplied an address — the email LIKE must not blow up on it.
        save("ghost", null, UserStatus.ACTIVE);
    }

    @Test
    void listsEveryoneWhenNeitherFilterIsGiven() {
        assertThat(directory.search(null, null, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactlyInAnyOrder("johndoe", "janedoe", "ghost");
    }

    @Test
    void filtersByStatusAlone() {
        assertThat(directory.search(null, UserStatus.BANNED, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactly("janedoe");
    }

    @Test
    void searchesUsernameAndEmailCaseInsensitively() {
        assertThat(directory.search("JOHN", null, FIRST_PAGE))
                .as("matches the username and, for a different casing, the address too")
                .extracting(AdminUserResponse::username)
                .containsExactly("johndoe");

        assertThat(directory.search("jane@EXAMPLE", null, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactly("janedoe");
    }

    /** Text, not a pattern: unescaped, {@code _} matched any one character and listed everyone. */
    @Test
    void searchesForWildcardCharactersLiterally() {
        save("john_doe", "john_doe@example.com", UserStatus.ACTIVE);

        assertThat(directory.search("_", null, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactly("john_doe");
        assertThat(directory.search("%", null, FIRST_PAGE)).isEmpty();
        assertThat(directory.search("n_d", null, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactly("john_doe");
    }

    @Test
    void combinesBothFilters() {
        assertThat(directory.search("doe", UserStatus.ACTIVE, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .containsExactly("johndoe");
    }

    @Test
    void ignoresSoftDeletedAccounts() {
        User ghost = userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull("ghost").orElseThrow();
        ghost.markDeleted();
        userRepository.save(ghost);

        assertThat(directory.search(null, null, FIRST_PAGE))
                .extracting(AdminUserResponse::username)
                .doesNotContain("ghost");
    }

    /** A report carries an id and nothing else; the console opens the account from that alone. */
    @Test
    void readsOneAccountById() {
        User jane = userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull("janedoe").orElseThrow();

        AdminUserResponse found = directory.get(jane.getId());

        assertThat(found.username()).isEqualTo("janedoe");
        assertThat(found.status()).isEqualTo(UserStatus.BANNED);
    }

    @Test
    void anUnknownIdIsNotFound() {
        assertThatThrownBy(() -> directory.get(123_456_789L)).isInstanceOf(UserNotFoundException.class);
    }

    /** Same rule as the listing: an account that cannot be listed cannot be opened either. */
    @Test
    void aSoftDeletedAccountIsNotFound() {
        User ghost = userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull("ghost").orElseThrow();
        ghost.markDeleted();
        userRepository.save(ghost);

        assertThatThrownBy(() -> directory.get(ghost.getId())).isInstanceOf(UserNotFoundException.class);
    }

    private void save(String username, String email, UserStatus status) {
        userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(status)
                .build());
    }
}
