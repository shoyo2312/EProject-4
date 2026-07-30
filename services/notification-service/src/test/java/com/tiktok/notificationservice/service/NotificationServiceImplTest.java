package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.exception.NotNotificationOwnerException;
import com.tiktok.notificationservice.exception.NotificationNotFoundException;
import com.tiktok.notificationservice.mapper.NotificationMapper;
import com.tiktok.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationMapper notificationMapper;

    private NotificationServiceImpl notificationService;

    private Notification unreadNotification(String id, Long recipientId) {
        return Notification.builder()
                .id(id)
                .recipientId(recipientId)
                .type(NotificationType.SYSTEM)
                .title("title")
                .body("body")
                .read(false)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void create_buildsNotificationWithGivenFieldsAndReturnsMappedResponse() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationResponse expected = new NotificationResponse("1", NotificationType.LIKE, "t", "b", "ref1", false, Instant.now());
        when(notificationMapper.toResponse(any(Notification.class))).thenReturn(expected);

        NotificationResponse response = notificationService.create(100L, NotificationType.LIKE, "t", "b", "ref1");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();

        assertThat(saved.getRecipientId()).isEqualTo(100L);
        assertThat(saved.getType()).isEqualTo(NotificationType.LIKE);
        assertThat(saved.getTitle()).isEqualTo("t");
        assertThat(saved.getBody()).isEqualTo("b");
        assertThat(saved.getReferenceId()).isEqualTo("ref1");
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.getId()).isNotBlank();
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void listByUser_mapsAllNotificationsForRecipient() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        Notification n1 = unreadNotification("n1", 1L);
        Notification n2 = unreadNotification("n2", 1L);
        NotificationResponse r1 = new NotificationResponse("n1", NotificationType.SYSTEM, "t", "b", null, false, n1.getCreatedAt());
        NotificationResponse r2 = new NotificationResponse("n2", NotificationType.SYSTEM, "t", "b", null, false, n2.getCreatedAt());
        when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(n1, n2));
        when(notificationMapper.toResponse(n1)).thenReturn(r1);
        when(notificationMapper.toResponse(n2)).thenReturn(r2);

        List<NotificationResponse> responses = notificationService.listByUser(1L);

        assertThat(responses).containsExactly(r1, r2);
    }

    @Test
    void unreadCount_delegatesToRepositoryCount() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        when(notificationRepository.countByRecipientIdAndReadFalse(1L)).thenReturn(3L);

        assertThat(notificationService.unreadCount(1L)).isEqualTo(3L);
    }

    @Test
    void markAsRead_calledByOwner_marksNotificationRead() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        Notification notification = unreadNotification("n1", 1L);
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));

        notificationService.markAsRead(1L, "n1");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().isRead()).isTrue();
    }

    @Test
    void markAsRead_calledByNonOwner_throwsNotNotificationOwnerAndDoesNotSave() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        Notification notification = unreadNotification("n1", 1L);
        when(notificationRepository.findById("n1")).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(2L, "n1"))
                .isInstanceOf(NotNotificationOwnerException.class);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_missingNotification_throwsNotificationNotFound() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        when(notificationRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(1L, "missing"))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void markAllAsRead_marksEveryUnreadNotificationForRecipient() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        Notification n1 = unreadNotification("n1", 1L);
        Notification n2 = unreadNotification("n2", 1L);
        when(notificationRepository.findByRecipientIdAndReadFalse(1L)).thenReturn(List.of(n1, n2));

        notificationService.markAllAsRead(1L);

        assertThat(n1.isRead()).isTrue();
        assertThat(n2.isRead()).isTrue();
        verify(notificationRepository).saveAll(List.of(n1, n2));
    }

    @Test
    void markAllAsRead_noUnreadNotifications_savesEmptyListWithoutError() {
        notificationService = new NotificationServiceImpl(notificationRepository, notificationMapper);
        when(notificationRepository.findByRecipientIdAndReadFalse(1L)).thenReturn(List.of());

        notificationService.markAllAsRead(1L);

        verify(notificationRepository).saveAll(List.of());
    }
}
