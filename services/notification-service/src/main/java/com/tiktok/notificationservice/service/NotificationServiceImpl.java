package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.client.PushPreferencesClient;
import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.DeviceToken;
import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.exception.InvalidNotificationCursorException;
import com.tiktok.notificationservice.exception.NotNotificationOwnerException;
import com.tiktok.notificationservice.event.producer.NotificationEventPublisher;
import com.tiktok.notificationservice.exception.NotificationNotFoundException;
import com.tiktok.notificationservice.mapper.NotificationMapper;
import com.tiktok.notificationservice.repository.DeviceTokenRepository;
import com.tiktok.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotificationService pushNotificationService;
    private final NotificationEventPublisher notificationEventPublisher;
    private final PushPreferencesClient pushPreferencesClient;

    /**
     * How long a like/share/follow from the same person on the same thing counts as already
     * announced. Long enough to cover a session of somebody toggling a button, short enough that
     * the same person coming back tomorrow is news again.
     */
    private static final Duration COLLAPSE_WINDOW = Duration.ofHours(24);

    @Override
    public NotificationResponse create(Long recipientId, Long actorId, NotificationType type, String title, String body, String referenceId) {
        Optional<Notification> alreadyAnnounced = findRecentDuplicate(recipientId, actorId, type, referenceId);
        if (alreadyAnnounced.isPresent()) {
            // Returned rather than null: the caller asked for this notification to exist, and it
            // does. Nothing is published or pushed — the recipient was already nudged once.
            log.debug("Collapsing repeat {} from actor {} to {} on {}", type, actorId, recipientId, referenceId);
            return notificationMapper.toResponse(alreadyAnnounced.get());
        }

        Notification notification = Notification.builder()
                .id(Notification.newId())
                .recipientId(recipientId)
                .actorId(actorId)
                .type(type)
                .title(title)
                .body(body)
                .referenceId(referenceId)
                .read(false)
                .build();

        Notification saved = notificationRepository.save(notification);
        notificationEventPublisher.publishCreated(saved);
        push(saved);
        return notificationMapper.toResponse(saved);
    }

    /**
     * A second like from the same account on the same video is the same piece of news, so it
     * does not get a second inbox entry. Unlike-then-like is not rate limiting or abuse
     * handling — it is one person's single opinion arriving repeatedly.
     *
     * <p>ponytail: read-then-write, so two deliveries landing in the same instant can both pass
     * and write two entries. Redelivery of one event is already blocked upstream by
     * IdempotentEventProcessor; what is left for this to stop is a human tapping a button, which
     * is never that fast. A unique index would close it properly, at the cost of a second
     * collection and a TTL to expire the window.
     */
    private Optional<Notification> findRecentDuplicate(Long recipientId, Long actorId,
                                                       NotificationType type, String referenceId) {
        if (!type.collapsesRepeats() || actorId == null || referenceId == null) {
            return Optional.empty();
        }
        return notificationRepository
                .findFirstByRecipientIdAndActorIdAndTypeAndReferenceIdAndCreatedAtAfter(
                        recipientId, actorId, type, referenceId, Instant.now().minus(COLLAPSE_WINDOW));
    }

    /**
     * Best effort, and deliberately after the save: the inbox entry is the notification, a push
     * is only the nudge that it arrived. A device that cannot be reached — or Firebase being
     * down — must not fail the event consumer that got here, because a retried delivery would
     * write the entry a second time.
     *
     * <p>The recipient's push switches and sleep hours decide whether to nudge at all. They are
     * asked for only when there is a device to push to, and when user-service cannot say what the
     * recipient chose the push is skipped on purpose (spec §3.3): pushing what they switched off is
     * the worse failure, and the entry is in the inbox either way.
     */
    private void push(Notification notification) {
        try {
            List<DeviceToken> devices = deviceTokenRepository.findByUserId(notification.getRecipientId());
            if (devices.isEmpty()) {
                return;
            }
            PushPreferences preferences = pushPreferencesClient.preferencesOf(notification.getRecipientId());
            if (preferences == null || !preferences.allows(notification.getType(), Instant.now())) {
                return;
            }
            devices.forEach(device -> pushNotificationService.send(
                    device.getToken(), notification.getTitle(), notification.getBody()));
        } catch (RuntimeException ex) {
            log.warn("Notification {} was stored but could not be pushed", notification.getId(), ex);
        }
    }

    @Override
    public void registerDevice(Long userId, String token) {
        // save() on a token-keyed document is an upsert, so a re-registration overwrites itself
        // and a token that moved to another account simply changes hands. See DeviceToken.
        deviceTokenRepository.save(DeviceToken.builder().token(token).userId(userId).build());
    }

    @Override
    public void unregisterDevice(Long userId, String token) {
        // Checked rather than deleted outright: the token travels in a URL, and without the
        // owner check anyone holding one could silence someone else's device. A mismatch is a
        // no-op rather than a 403, which would confirm the token exists.
        deviceTokenRepository.findById(token)
                .filter(device -> userId.equals(device.getUserId()))
                .ifPresent(deviceTokenRepository::delete);
    }

    /**
     * Paged, because an inbox only grows: every like, comment and follow adds an entry, and the
     * whole of it used to be read and sent on every open. The cursor is an entry's own id rather
     * than a page number, so entries arriving while someone scrolls do not shift the next page.
     */
    @Override
    public List<NotificationResponse> listByUser(Long recipientId, String before, int size) {
        Notification after = before == null ? null : notificationRepository.findById(before)
                .filter(notification -> recipientId.equals(notification.getRecipientId()))
                .orElseThrow(() -> new InvalidNotificationCursorException(before));
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return notificationRepository.findPage(recipientId, after, limit).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Override
    public long unreadCount(Long recipientId) {
        return notificationRepository.countByRecipientIdAndReadFalse(recipientId);
    }

    @Override
    public void markAsRead(Long requesterId, String notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (!requesterId.equals(notification.getRecipientId())) {
            throw new NotNotificationOwnerException(notificationId);
        }

        notification.markRead();
        notificationRepository.save(notification);
    }

    /** One update, however many are unread; it used to load them all to save them back one by one. */
    @Override
    public void markAllAsRead(Long requesterId) {
        notificationRepository.markAllRead(requesterId);
    }
}
