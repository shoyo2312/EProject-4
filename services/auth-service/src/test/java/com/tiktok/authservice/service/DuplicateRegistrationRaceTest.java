package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * The existsBy checks in register() are check-then-act: two concurrent registrations for the same
 * identity both read nothing and both go on to insert. Only uq_users_email / uq_users_username
 * stops the second, and the DataIntegrityViolationException it raises used to escape as a 500 —
 * the same request answered 409 when it lost the race by a second and 500 when it lost by a
 * millisecond.
 *
 * <p>Worse than the status code: the id is assigned rather than generated, so Hibernate deferred
 * the INSERT to commit. The violation surfaced after register() had returned, outside anything
 * that could translate it.
 *
 * <p>Rather than race real threads, which decides nothing reliably, each test forces the exact
 * state a lost race leaves behind: the row is committed, and the pre-check is stubbed to report
 * what the racing request saw a moment earlier — nothing. The insert then hits the index for real.
 * Mirrors user-service's DuplicateRelationshipRaceTest.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DuplicateRegistrationRaceTest {

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
    private AuthService authService;

    @SpyBean
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void register_losingAnEmailRace_reports409NotAServerError() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "password123"));

        doReturn(false).when(userRepository).existsByEmailIgnoreCaseAndDeletedAtIsNull("john@example.com");

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("someoneelse", "john@example.com", "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    /**
     * The constraint name is what separates this case from the one above. Guessing, or defaulting
     * to whichever exception is listed first, would tell this caller their email is taken and send
     * them off to change the one input that was fine.
     */
    @Test
    void register_losingAUsernameRace_namesTheUsernameNotTheEmail() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "password123"));

        doReturn(false).when(userRepository).existsByUsernameIgnoreCaseAndDeletedAtIsNull("johndoe");

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("johndoe", "different@example.com", "password123")))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    /**
     * The rollback matters as much as the status code: register() also writes an outbox row and an
     * OTP, and a half-applied duplicate would announce a UserRegisteredEvent for an account that
     * was never created.
     */
    @Test
    void register_losingARace_leavesNoPartialAccountBehind() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "password123"));

        doReturn(false).when(userRepository).existsByEmailIgnoreCaseAndDeletedAtIsNull("john@example.com");

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("someoneelse", "john@example.com", "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull("someoneelse")).isEmpty();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void register_sequentialDuplicate_stillReports409ThroughThePreCheck() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "password123"));

        // Unstubbed: the cheap read still short-circuits, so the index is the backstop and not
        // the normal path.
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("someoneelse", "john@example.com", "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }
}
