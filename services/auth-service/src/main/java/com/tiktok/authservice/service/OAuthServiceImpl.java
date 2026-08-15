package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class OAuthServiceImpl implements OAuthService {

    private final Map<AuthProvider, SocialTokenVerifier> verifiers = new EnumMap<>(AuthProvider.class);
    private final UserIdentityRepository userIdentityRepository;
    private final UserRepository userRepository;
    private final SocialAccountRegistrar registrar;
    private final TokenIssuer tokenIssuer;

    public OAuthServiceImpl(List<SocialTokenVerifier> verifiers,
                            UserIdentityRepository userIdentityRepository,
                            UserRepository userRepository,
                            SocialAccountRegistrar registrar,
                            TokenIssuer tokenIssuer) {
        verifiers.forEach(verifier -> this.verifiers.put(verifier.provider(), verifier));
        this.userIdentityRepository = userIdentityRepository;
        this.userRepository = userRepository;
        this.registrar = registrar;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * Deliberately not {@code @Transactional}: creating the account has to be able to fail on its
     * own, inside {@link SocialAccountRegistrar}, so that losing the uq_identity race leaves this
     * method able to read the winner's row. A shared transaction would already be doomed by then.
     */
    @Override
    public SocialLoginResponse login(AuthProvider provider, SocialLoginRequest request) {
        SocialProfile profile = verifiers.get(provider).verify(request.token());

        User user = findLinked(profile).orElseGet(() -> registerOrJoinWinner(profile));

        // A locked account must not be handed a session by the side door. Same answer the password
        // login gives, for the same reason: why the sign-in failed is not the client's business.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        // Not checked: emailVerified. The provider has already proven the user controls the
        // account, and an account with no address at all could never satisfy it.
        return new SocialLoginResponse(tokenIssuer.issue(user), user.getEmail() == null);
    }

    private User registerOrJoinWinner(SocialProfile profile) {
        try {
            return registrar.register(profile);
        } catch (DataIntegrityViolationException e) {
            // The identity now exists because a concurrent request for the same provider account
            // created it. Nothing is wrong — read it back and continue. If it is still not there
            // the violation was something else entirely and belongs in the 500 bucket.
            log.info("Concurrent first login for {} uid {} — joining the winning registration",
                    profile.provider(), profile.uid());
            return findLinked(profile).orElseThrow(() -> e);
        }
    }

    private Optional<User> findLinked(SocialProfile profile) {
        return userIdentityRepository
                .findByProviderAndProviderUid(profile.provider(), profile.uid())
                .map(identity -> userRepository.findById(identity.getUserId())
                        .filter(user -> !user.isDeleted())
                        // The link outlives the account it points at, so a deleted account would
                        // otherwise send us down the create path and straight into uq_identity.
                        .orElseThrow(InvalidCredentialsException::new));
    }
}
