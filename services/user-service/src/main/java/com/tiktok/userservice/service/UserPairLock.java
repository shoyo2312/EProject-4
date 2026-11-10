package com.tiktok.userservice.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class UserPairLock {
    private final EntityManager entityManager;

    public static String key(Long a, Long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    // Stable FNV-1a, independent of JVM/database hash seeds. A collision only serializes extra pairs.
    static long hash(Long a, Long b) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : key(a, b).getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
        }
        return hash;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Long a, Long b) {
        // READ COMMITTED check-then-insert permits a follow after block's edge removal. Every
        // writer of this pair locks before reading; PostgreSQL releases the lock only at commit.
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:key)")
                .setParameter("key", hash(a, b)).getSingleResult();
    }
}
