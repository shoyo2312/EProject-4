package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.exception.UsernameChangeTooSoonException;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.event.user.UsernameChangedEvent;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Nothing to revoke on a rename: tokens carry the account id, never the handle, and the login
 * rate limit is keyed by id too. Sign-in by the old handle simply stops working.
 */
@Service
@RequiredArgsConstructor
public class UsernameChangeServiceImpl implements UsernameChangeService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserEventProducer userEventProducer;

    @Override
    @Transactional
    public UserResponse changeUsername(Long userId, String username) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UserNotFoundException(String.valueOf(userId)));

        if (user.getUsername().equals(username)) {
            return userMapper.toResponse(user);
        }

        Instant now = Instant.now();
        Instant changeableAt = UsernameCooldown.changeableAt(user.getUsernameChangedAt(), now);
        if (changeableAt != null) {
            throw new UsernameChangeTooSoonException(changeableAt);
        }

        // Case-insensitive like the unique index. The account's own row is not a clash, which is
        // what lets a handle registered with capitals move to its lowercase spelling.
        userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull(username)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new UsernameAlreadyExistsException(username);
                });

        String previous = user.getUsername();
        user.changeUsername(username, now);
        flushUnique(user, username);

        // Same transaction as the rename, so user-service's copy of the handle cannot be told
        // about a change that rolled back, nor miss one that committed.
        userEventProducer.publishUsernameChanged(UsernameChangedEvent.of(userId, username, previous));
        return userMapper.toResponse(user);
    }

    /**
     * The lookup above is not the guarantee: two accounts claiming the same free handle at once
     * both pass it, and only the unique index stops the second. Flushing here turns that loss
     * into the same 409 instead of a 500 at commit — the reasoning of
     * {@code AuthServiceImpl.saveUnique}. A version clash surfaces here too, as the shared 409
     * CONCURRENT_MODIFICATION, which is what keeps two racing renames from both passing the
     * 30-day check.
     */
    private void flushUnique(User user, String username) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            String constraint = ex.getCause() instanceof ConstraintViolationException cause
                    ? String.valueOf(cause.getConstraintName()).toLowerCase(Locale.ROOT)
                    : "";
            if (constraint.contains("username")) {
                throw new UsernameAlreadyExistsException(username);
            }
            throw ex;
        }
    }
}
