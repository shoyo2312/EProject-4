package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.DeviceToken;
import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.exception.NotNotificationOwnerException;
import com.tiktok.notificationservice.event.producer.NotificationEventPublisher;
import com.tiktok.notificationservice.exception.NotificationNotFoundException;
import com.tiktok.notificationservice.mapper.NotificationMapper;
import com.tiktok.notificationservice.repository.DeviceTokenRepository;
import com.tiktok.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushNotificationService pushNotificationService;
    private final NotificationEventPublisher notificationEventPublisher;

    @Override
    public NotificationResponse create(Long recipientId, NotificationType type, String title, String body, String referenceId) {
        Notification notification = Notification.builder()
                .id(Notification.newId())
                .recipientId(recipientId)
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
     * Best effort, and deliberately after the save: the inbox entry is the notification, a push
     * is only the nudge that it arrived. A device that cannot be reached — or Firebase being
     * down — must not fail the event consumer that got here, because a retried delivery would
     * write the entry a second time.
     */
    private void push(Notification notification) {
        try {
            deviceTokenRepository.findByUserId(notification.getRecipientId()).forEach(device ->
                    pushNotificationService.send(
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

    @Override
    public List<NotificationResponse> listByUser(Long recipientId) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId).stream()
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

    @Override
    public void markAllAsRead(Long requesterId) {
        List<Notification> unread = notificationRepository.findByRecipientIdAndReadFalse(requesterId);
        unread.forEach(Notification::markRead);
        notificationRepository.saveAll(unread);
    }
}
