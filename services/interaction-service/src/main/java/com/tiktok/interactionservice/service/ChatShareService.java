package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.entity.ChatShareDedupeKey;
import com.tiktok.interactionservice.repository.ChatShareDedupeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatShareService {
    private final ChatShareDedupeRepository dedupeRepository;
    private final ShareServiceImpl shareService;

    public boolean count(Long videoId, Long userId, String dedupeKey) {
        Long shareId = SnowflakeIdGenerator.nextId();
        ChatShareDedupeKey key = new ChatShareDedupeKey(videoId, dedupeKey);
        boolean applied = LwtRetry.run(() -> dedupeRepository.claim(videoId, dedupeKey, shareId),
                () -> dedupeRepository.findById(key).map(row -> shareId.equals(row.getShareId())).orElse(false),
                "Chat share claim");
        if (!applied) {
            return false;
        }
        try {
            shareService.persistShare(videoId, userId, shareId);
            return true;
        } catch (RuntimeException e) {
            try {
                dedupeRepository.deleteById(key);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
                log.error("Could not release failed chat share claim {} for video {}", dedupeKey, videoId, cleanup);
            }
            throw e;
        }
    }
}
