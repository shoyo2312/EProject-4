package com.tiktok.authservice.config;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAccountBootstrapTest {

    private static final String EMAIL = "admin@example.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate(mock(PlatformTransactionManager.class));

    /**
     * Whoever registered the address first chose its password, or linked a Facebook identity that
     * only claims it — and social sign-in skips the admin second factor. Promoting that account
     * handed them the console.
     */
    @Test
    void anAccountThatNeverVerifiedTheAddressIsNotPromoted() throws Exception {
        User squatter = existing();
        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(squatter));

        run();

        assertThat(squatter.getRole()).isEqualTo(UserRole.USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    void aVerifiedAccountIsPromotedAndKeepsItsPassword() throws Exception {
        User owner = existing();
        owner.markEmailVerified();
        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL)).thenReturn(Optional.of(owner));

        run();

        assertThat(owner.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(owner.getPasswordHash()).isEqualTo("owners-hash");
        verify(userRepository).save(owner);
    }

    private static User existing() {
        return User.builder()
                .username("someone")
                .email(EMAIL)
                .passwordHash("owners-hash")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private void run() throws Exception {
        new AdminAccountBootstrap()
                .adminAccountBootstrapRunner(new AdminBootstrapProperties(EMAIL, "configured-pass", null),
                        userRepository, transactionTemplate)
                .run(null);
    }
}
