package com.tiktok.authservice.config;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Creates or promotes the ADMIN account named by {@code auth.admin.*} at startup, so the admin
 * console has something to sign in as. Does nothing unless both email and password are set.
 *
 * <p>The account is marked email-verified without an OTP: login refuses an unverified address, and
 * the address here was chosen by whoever configured the deployment, not claimed by a visitor.
 *
 * <p>An existing account with that email is promoted rather than duplicated — the password is left
 * alone, so this cannot be used to take over an account by re-pointing the env var at it.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminAccountBootstrap {

    @Bean
    public ApplicationRunner adminAccountBootstrapRunner(AdminBootstrapProperties properties,
                                                         UserRepository userRepository,
                                                         TransactionTemplate transactionTemplate) {
        return args -> {
            if (!properties.isConfigured()) {
                return;
            }
            String email = properties.email().toLowerCase(Locale.ROOT);

            // Two replicas starting together both read no account and both insert; uq_users_email
            // stops the second, and an exception out of an ApplicationRunner stops that instance
            // from booting at all. The loser has nothing left to do — the winner just created the
            // account it wanted — so this is a race that is over, not a failure. Caught outside the
            // template because the violation surfaces at commit, by which point the transaction is
            // already marked rollback-only and nothing can be done about it from inside.
            try {
                bootstrap(properties, userRepository, transactionTemplate, email);
            } catch (DataIntegrityViolationException e) {
                log.info("Admin bootstrap lost a race to another instance; account {} already exists", email);
            }
        };
    }

    private void bootstrap(AdminBootstrapProperties properties,
                           UserRepository userRepository,
                           TransactionTemplate transactionTemplate,
                           String email) {
        transactionTemplate.executeWithoutResult(status -> {
            User existing = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElse(null);

            if (existing != null) {
                if (existing.getRole() == UserRole.ADMIN) {
                    return;
                }
                existing.promoteToAdmin();
                userRepository.save(existing);
                log.info("Promoted existing account {} to ADMIN", email);
                return;
            }

            String username = properties.username() == null || properties.username().isBlank()
                    ? "admin"
                    : properties.username();

            // The email is free but the username is taken by some other account. Inserting
            // would hit uq_users_username and, from an ApplicationRunner, abort startup for
            // the whole service. Fail soft instead: the deployer points ADMIN_EMAIL at that
            // account (promote path above) or sets ADMIN_USERNAME to a free name.
            if (userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull(username)) {
                log.error("Admin bootstrap skipped: username '{}' already belongs to a "
                        + "different account. Set ADMIN_EMAIL to that account's email, or "
                        + "ADMIN_USERNAME to an unused name.", username);
                return;
            }

            User admin = User.builder()
                    .username(username)
                    .email(email)
                    .passwordHash(HashUtils.bcryptHash(properties.password()))
                    .role(UserRole.ADMIN)
                    .status(UserStatus.ACTIVE)
                    .build();
            admin.markEmailVerified();
            userRepository.save(admin);
            log.info("Created bootstrap ADMIN account {}", email);
        });
    }
}
