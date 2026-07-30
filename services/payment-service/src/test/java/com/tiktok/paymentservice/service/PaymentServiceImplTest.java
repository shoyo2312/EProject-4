package com.tiktok.paymentservice.service;

import com.tiktok.event.DomainEvent;
import com.tiktok.event.order.OrderItem;
import com.tiktok.event.payment.PaymentCompletedEvent;
import com.tiktok.event.payment.PaymentFailedEvent;
import com.tiktok.paymentservice.dto.response.PaymentResponse;
import com.tiktok.paymentservice.entity.OrderReference;
import com.tiktok.paymentservice.entity.PaymentStatus;
import com.tiktok.paymentservice.entity.PaymentTransaction;
import com.tiktok.paymentservice.event.producer.PaymentEventProducer;
import com.tiktok.paymentservice.exception.NotPaymentOwnerException;
import com.tiktok.paymentservice.exception.OrderReferenceNotFoundException;
import com.tiktok.paymentservice.exception.PaymentNotFoundException;
import com.tiktok.paymentservice.gateway.PaymentGateway;
import com.tiktok.paymentservice.gateway.PaymentGatewayResult;
import com.tiktok.paymentservice.mapper.PaymentMapper;
import com.tiktok.paymentservice.repository.OrderReferenceRepository;
import com.tiktok.paymentservice.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private OrderReferenceRepository orderReferenceRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    @Mock
    private PaymentMapper paymentMapper;

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(orderReferenceRepository, paymentTransactionRepository,
                paymentGateway, paymentEventProducer, paymentMapper);
    }

    @Test
    void recordOrderReference_newOrder_savesReference() {
        when(orderReferenceRepository.findByOrderId(900L)).thenReturn(Optional.empty());

        paymentService.recordOrderReference(900L, 100L);

        ArgumentCaptor<OrderReference> captor = ArgumentCaptor.forClass(OrderReference.class);
        verify(orderReferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(900L);
        assertThat(captor.getValue().getUserId()).isEqualTo(100L);
    }

    @Test
    void recordOrderReference_alreadyRecorded_isNoOp() {
        when(orderReferenceRepository.findByOrderId(900L))
                .thenReturn(Optional.of(OrderReference.builder().orderId(900L).userId(100L).build()));

        paymentService.recordOrderReference(900L, 100L);

        verify(orderReferenceRepository, never()).save(any());
    }

    @Test
    void processPayment_gatewaySucceeds_savesSucceededTransactionAndPublishesCompletedEvent() {
        when(orderReferenceRepository.findByOrderId(900L))
                .thenReturn(Optional.of(OrderReference.builder().orderId(900L).userId(100L).build()));
        when(paymentGateway.charge(eq(900L), any(BigDecimal.class))).thenReturn(PaymentGatewayResult.succeeded());
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(invocation -> {
            PaymentTransaction t = invocation.getArgument(0);
            return PaymentTransaction.builder().id(1L).orderId(t.getOrderId()).userId(t.getUserId())
                    .amount(t.getAmount()).status(t.getStatus()).failureReason(t.getFailureReason()).build();
        });

        paymentService.processPayment(900L, List.of(new OrderItem(1L, 2, new BigDecimal("9.99"))));

        ArgumentCaptor<PaymentTransaction> txCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getUserId()).isEqualTo(100L);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("19.98");
        assertThat(txCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(paymentEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PaymentCompletedEvent.class);
    }

    @Test
    void processPayment_gatewayFails_savesFailedTransactionAndPublishesFailedEvent() {
        when(orderReferenceRepository.findByOrderId(900L))
                .thenReturn(Optional.of(OrderReference.builder().orderId(900L).userId(100L).build()));
        when(paymentGateway.charge(eq(900L), any(BigDecimal.class))).thenReturn(PaymentGatewayResult.failed("card declined"));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(invocation -> {
            PaymentTransaction t = invocation.getArgument(0);
            return PaymentTransaction.builder().id(1L).orderId(t.getOrderId()).userId(t.getUserId())
                    .amount(t.getAmount()).status(t.getStatus()).failureReason(t.getFailureReason()).build();
        });

        paymentService.processPayment(900L, List.of(new OrderItem(1L, 2, new BigDecimal("9.99"))));

        ArgumentCaptor<PaymentTransaction> txCaptor = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(txCaptor.getValue().getFailureReason()).isEqualTo("card declined");

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(paymentEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PaymentFailedEvent.class);
    }

    @Test
    void processPayment_noOrderReference_throwsOrderReferenceNotFoundException() {
        when(orderReferenceRepository.findByOrderId(900L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(900L, List.of(new OrderItem(1L, 1, BigDecimal.ONE))))
                .isInstanceOf(OrderReferenceNotFoundException.class);
        verifyNoInteractions(paymentGateway, paymentEventProducer);
    }

    @Test
    void getByOrderId_calledByOwner_returnsMappedResponse() {
        PaymentTransaction transaction = PaymentTransaction.builder().id(1L).orderId(900L).userId(100L)
                .amount(new BigDecimal("19.98")).status(PaymentStatus.SUCCEEDED).createdAt(Instant.now()).build();
        PaymentResponse expected = new PaymentResponse(1L, 900L, 100L, new BigDecimal("19.98"), PaymentStatus.SUCCEEDED, null, transaction.getCreatedAt());
        when(paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDesc(900L)).thenReturn(Optional.of(transaction));
        when(paymentMapper.toResponse(transaction)).thenReturn(expected);

        assertThat(paymentService.getByOrderId(100L, 900L)).isEqualTo(expected);
    }

    @Test
    void getByOrderId_calledByNonOwner_throwsNotPaymentOwnerException() {
        PaymentTransaction transaction = PaymentTransaction.builder().id(1L).orderId(900L).userId(100L)
                .amount(new BigDecimal("19.98")).status(PaymentStatus.SUCCEEDED).createdAt(Instant.now()).build();
        when(paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDesc(900L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> paymentService.getByOrderId(200L, 900L))
                .isInstanceOf(NotPaymentOwnerException.class);
    }

    @Test
    void getByOrderId_missingPayment_throwsPaymentNotFoundException() {
        when(paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDesc(900L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getByOrderId(100L, 900L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void listByUser_mapsAllTransactionsForUser() {
        PaymentTransaction transaction = PaymentTransaction.builder().id(1L).orderId(900L).userId(100L)
                .amount(new BigDecimal("19.98")).status(PaymentStatus.SUCCEEDED).createdAt(Instant.now()).build();
        PaymentResponse expected = new PaymentResponse(1L, 900L, 100L, new BigDecimal("19.98"), PaymentStatus.SUCCEEDED, null, transaction.getCreatedAt());
        when(paymentTransactionRepository.findByUserIdOrderByCreatedAtDesc(100L)).thenReturn(List.of(transaction));
        when(paymentMapper.toResponse(transaction)).thenReturn(expected);

        assertThat(paymentService.listByUser(100L)).containsExactly(expected);
    }
}
