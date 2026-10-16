package com.tiktok.adminservice.service;

import com.tiktok.adminservice.event.producer.AdminEventProducer;
import com.tiktok.adminservice.repository.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaleReportSweepTest {

    private static final int BATCH_SIZE = 500;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private AdminEventProducer adminEventProducer;

    private StaleReportSweep sweep() {
        return new StaleReportSweep(reportRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), adminEventProducer,
                Duration.ofDays(30), 2, BATCH_SIZE, 20);
    }

    @Test
    void everyBatchThatClosedReports_announcesItself() {
        when(reportRepository.dismissStale(any(Instant.class), eq(2), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE, 7);

        sweep().dismissStaleReports();

        // An open console otherwise keeps listing the closed reports as pending until reloaded.
        InOrder order = inOrder(reportRepository, adminEventProducer);
        order.verify(reportRepository).dismissStale(any(Instant.class), anyInt(), anyInt());
        order.verify(adminEventProducer).publishStaleReportsDismissed(BATCH_SIZE);
        order.verify(reportRepository).dismissStale(any(Instant.class), anyInt(), anyInt());
        order.verify(adminEventProducer).publishStaleReportsDismissed(7);
        order.verifyNoMoreInteractions();
    }

    @Test
    void aSweepThatClosedNothing_saysNothing() {
        when(reportRepository.dismissStale(any(Instant.class), anyInt(), anyInt())).thenReturn(0);

        sweep().dismissStaleReports();

        verifyNoInteractions(adminEventProducer);
    }
}
