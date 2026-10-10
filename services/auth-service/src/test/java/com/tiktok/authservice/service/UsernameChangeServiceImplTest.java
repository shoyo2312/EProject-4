package com.tiktok.authservice.service;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.exception.UsernameChangeTooSoonException;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.event.user.UsernameChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UsernameChangeServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private MailService mailService;

    @MockitoBean
    private TurnstileService turnstileService;

    @Autowired
    private UsernameChangeService usernameChangeService;

    @Autowired
    private AuthService authService;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void renames_stampsTheChange_andAnnouncesItOnItsOwnAggregate() throws Exception {
        User alice = account("alice");

        UserResponse response = usernameChangeService.changeUsername(alice.getId(), "alice.new");

        assertThat(response.username()).isEqualTo("alice.new");
        assertThat(response.usernameChangeableAt())
                .isCloseTo(Instant.now().plus(UsernameCooldown.PERIOD), within(1, ChronoUnit.MINUTES));
        assertThat(reload(alice).getUsername()).isEqualTo("alice.new");

        List<OutboxEvent> outbox = outboxEventRepository.findAll();
        assertThat(outbox).singleElement().satisfies(row -> {
            assertThat(row.getAggregateType()).isEqualTo("USERNAME");
            assertThat(row.getAggregateId()).isEqualTo(String.valueOf(alice.getId()));
            assertThat(row.getEventType()).isEqualTo("UsernameChangedEvent");
        });
        UsernameChangedEvent event = objectMapper.readValue(outbox.get(0).getPayload(), UsernameChangedEvent.class);
        assertThat(event.userId()).isEqualTo(alice.getId());
        assertThat(event.username()).isEqualTo("alice.new");
        assertThat(event.previousUsername()).isEqualTo("alice");
    }

    @Test
    void theCurrentHandle_isNotAChange_andSpendsNothing() {
        User alice = account("alice");

        UserResponse response = usernameChangeService.changeUsername(alice.getId(), "alice");

        assertThat(response.usernameChangeableAt()).isNull();
        assertThat(reload(alice).getUsernameChangedAt()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void aSecondChangeWithinThirtyDays_isRefused_andChangesNothing() {
        User alice = account("alice");
        usernameChangeService.changeUsername(alice.getId(), "alice.one");
        lastChangedDaysAgo(alice, 29);

        assertThatThrownBy(() -> usernameChangeService.changeUsername(alice.getId(), "alice.two"))
                .isInstanceOf(UsernameChangeTooSoonException.class)
                .hasFieldOrPropertyWithValue("code", "USERNAME_CHANGE_TOO_SOON")
                .hasFieldOrPropertyWithValue("status", HttpStatus.TOO_MANY_REQUESTS);

        assertThat(reload(alice).getUsername()).isEqualTo("alice.one");
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void thirtyDaysLater_theHandleMayChangeAgain() {
        User alice = account("alice");
        usernameChangeService.changeUsername(alice.getId(), "alice.one");
        lastChangedDaysAgo(alice, 30);

        assertThat(usernameChangeService.changeUsername(alice.getId(), "alice.two").username())
                .isEqualTo("alice.two");
    }

    @Test
    void anotherAccountsHandle_isTaken_whateverItsCase() {
        account("Bob");
        User alice = account("alice");

        assertThatThrownBy(() -> usernameChangeService.changeUsername(alice.getId(), "bob"))
                .isInstanceOf(UsernameAlreadyExistsException.class);

        assertThat(reload(alice).getUsernameChangedAt()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void theAccountsOwnHandle_inLowercase_isNotAClashWithItself() {
        User alice = account("Alice");

        assertThat(usernameChangeService.changeUsername(alice.getId(), "alice").username()).isEqualTo("alice");
    }

    @Test
    void aHandleTakenBetweenTheLookupAndTheWrite_isStillA409() {
        account("bob");
        User alice = account("alice");
        // The race: the lookup saw no owner, so only the unique index stands in the way.
        doReturn(Optional.empty()).when(userRepository).findByUsernameIgnoreCaseAndDeletedAtIsNull("bob");

        assertThatThrownBy(() -> usernameChangeService.changeUsername(alice.getId(), "bob"))
                .isInstanceOf(UsernameAlreadyExistsException.class);

        assertThat(reload(alice).getUsername()).isEqualTo("alice");
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void theHandleItGaveUp_isFreeForSomeoneElse() {
        User alice = account("alice");
        User bob = account("bob");
        usernameChangeService.changeUsername(alice.getId(), "alice.new");

        assertThat(usernameChangeService.changeUsername(bob.getId(), "alice").username()).isEqualTo("alice");
    }

    @Test
    void me_saysWhenTheHandleMayNextChange() {
        User alice = account("alice");
        assertThat(authService.getCurrentUser(alice.getId()).usernameChangeableAt()).isNull();

        usernameChangeService.changeUsername(alice.getId(), "alice.new");

        Instant changedAt = reload(alice).getUsernameChangedAt();
        assertThat(authService.getCurrentUser(alice.getId()).usernameChangeableAt())
                .isEqualTo(changedAt.plus(UsernameCooldown.PERIOD));
    }

    private User account(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username.toLowerCase() + "@example.com")
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** As if the last change had been made {@code days} ago. */
    private void lastChangedDaysAgo(User user, int days) {
        jdbcTemplate.update("update users set username_changed_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(days))), user.getId());
    }
}
