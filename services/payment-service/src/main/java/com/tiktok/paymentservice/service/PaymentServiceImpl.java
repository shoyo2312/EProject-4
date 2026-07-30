package com.tiktok.paymentservice.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderReferenceRepository orderReferenceRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentMapper paymentMapper;

    @Override
    @Transactional
    public void recordOrderReference(Long orderId, Long userId) {
        if (orderReferenceRepository.findByOrderId(orderId).isPresent()) {
            return;
        }

        orderReferenceRepository.save(OrderReference.builder()
                .orderId(orderId)
                .userId(userId)
                .build());
    }

    @Override
    @Transactional
    public void processPayment(Long orderId, List<OrderItem> items) {
        OrderReference reference = orderReferenceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new OrderReferenceNotFoundException(orderId));

        BigDecimal amount = items.stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentGatewayResult result = paymentGateway.charge(orderId, amount);

        PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                .orderId(orderId)
                .userId(reference.getUserId())
                .amount(amount)
                .status(result.success() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED)
                .failureReason(result.success() ? null : result.failureReason())
                .build());

        if (result.success()) {
            paymentEventProducer.publish(orderId, PaymentCompletedEvent.of(transaction.getId(), orderId, amount));
        } else {
            paymentEventProducer.publish(orderId, PaymentFailedEvent.of(transaction.getId(), orderId, result.failureReason()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(Long userId, Long orderId) {
        PaymentTransaction transaction = paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));

        if (!userId.equals(transaction.getUserId())) {
            throw new NotPaymentOwnerException(orderId);
        }

        return paymentMapper.toResponse(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> listByUser(Long userId) {
        return paymentTransactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(paymentMapper::toResponse)
                .toList();
    }
}
