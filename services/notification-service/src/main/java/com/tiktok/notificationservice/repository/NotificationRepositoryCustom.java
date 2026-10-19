package com.tiktok.notificationservice.repository;

import com.tiktok.notificationservice.entity.Notification;

import java.util.List;

public interface NotificationRepositoryCustom {

    /**
     * One page of an inbox, newest first, starting after {@code after} — or at the newest entry
     * when it is null. Ordered by createdAt and then id, which is what makes the cursor exact: two
     * entries stamped in the same millisecond still have one order, so a page boundary between
     * them neither repeats nor skips one. Backed by {@code recipient_page_idx}.
     */
    List<Notification> findPage(Long recipientId, Notification after, int size);

    /**
     * Marks every unread entry of an inbox read in one update, rather than loading each into memory
     * to save it back.
     *
     * @return how many entries changed
     */
    long markAllRead(Long recipientId);
}
