package com.tiktok.userservice.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserPairLockTest {
    @Test
    void bothDirectionsTakeTheSameTransactionLock() {
        EntityManager manager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(manager.createNativeQuery("select 1 from pg_advisory_xact_lock(:key)")).thenReturn(query);
        when(query.setParameter("key", UserPairLock.hash(2L, 9L))).thenReturn(query);
        var lock = new UserPairLock(manager);
        lock.acquire(2L, 9L);
        lock.acquire(9L, 2L);
        assertThat(UserPairLock.key(9L, 2L)).isEqualTo("2:9");
        assertThat(UserPairLock.hash(9L, 2L)).isEqualTo(UserPairLock.hash(2L, 9L));
        assertThat(UserPairLock.hash(2L, 9L)).isNotEqualTo(UserPairLock.hash(2L, 8L));
        verify(query, times(2)).setParameter("key", UserPairLock.hash(2L, 9L));
        verify(query, times(2)).getSingleResult();
    }
}
