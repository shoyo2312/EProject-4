package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The whole file is about one decision: what an <em>unverified</em> address from a provider may do.
 * Facebook never verifies, so this is every Facebook signup, not an edge case.
 */
class SocialAccountRegistrarTest {

    private static final String UID = "1850497406314145";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final UsernameGenerator usernameGenerator = mock(UsernameGenerator.class);
    private final UserEventProducer eventProducer = mock(UserEventProducer.class);

    private SocialAccountRegistrar registrar;

    @BeforeEach
    void setUp() {
        when(usernameGenerator.generate(any())).thenReturn("someone");
        when(userRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        registrar = new SocialAccountRegistrar(userRepository, identityRepository,
                usernameGenerator, eventProducer);
    }

    /**
     * Nobody holds the address, so keeping it takes nothing from anyone — and it saves the user the
     * add-email screen. Unverified, though: only an OTP of ours may set that flag.
     */
    @Test
    void unverifiedAddressIsStoredWhenItIsFree() {
        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("a@example.com"))
                .thenReturn(Optional.empty());

        registrar.register(facebook("a@example.com"));

        User saved = savedUser();
        assertThat(saved.getEmail()).isEqualTo("a@example.com");
        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.getEmailVerifiedAt()).isNull();
    }

    /**
     * The takeover this guards against: anyone can put victim@example.com on a Facebook account.
     * It must not be handed the existing account — and it must not quietly start a second one
     * either, which is what the caller's mailed challenge exists to avoid.
     */
    @Test
    void unverifiedAddressThatAlreadyHasAnOwnerNeitherLinksNorRegisters() {
        User owner = existing("a@example.com");
        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("a@example.com"))
                .thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> registrar.register(facebook("a@example.com")))
                .isInstanceOf(SocialLinkRequiredSignal.class)
                .extracting(signal -> ((SocialLinkRequiredSignal) signal).owner())
                .isSameAs(owner);

        verify(userRepository, never()).saveAndFlush(any());
        verify(identityRepository, never()).saveAndFlush(any());
    }

    /** Google does verify, so the existing account is the same person and gets the new identity. */
    @Test
    void verifiedAddressLinksToTheExistingAccount() {
        User owner = existing("a@example.com");
        when(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("a@example.com"))
                .thenReturn(Optional.of(owner));

        User result = registrar.register(
                new SocialProfile(AuthProvider.GOOGLE, UID, "a@example.com", true));

        assertThat(result).isSameAs(owner);
        verify(userRepository, never()).saveAndFlush(any());
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private SocialProfile facebook(String email) {
        return new SocialProfile(AuthProvider.FACEBOOK, UID, email, false);
    }

    private User existing(String email) {
        return User.builder()
                .username("owner")
                .email(email)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
