package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.exception.NotNotificationOwnerException;
import com.tiktok.notificationservice.exception.NotificationNotFoundException;
import com.tiktok.notificationservice.mapper.NotificationMapper;
import com.tiktok.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;

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

        return notificationMapper.toResponse(notificationRepository.save(notification));
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
