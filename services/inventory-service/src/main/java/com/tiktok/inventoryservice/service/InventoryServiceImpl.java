package com.tiktok.inventoryservice.service;

import com.tiktok.event.order.OrderItem;
import com.tiktok.inventoryservice.dto.response.InventoryResponse;
import com.tiktok.inventoryservice.entity.InventoryItem;
import com.tiktok.inventoryservice.entity.ReservationStatus;
import com.tiktok.inventoryservice.entity.StockReservation;
import com.tiktok.inventoryservice.exception.InventoryItemNotFoundException;
import com.tiktok.inventoryservice.exception.NotInventoryOwnerException;
import com.tiktok.inventoryservice.mapper.InventoryMapper;
import com.tiktok.inventoryservice.repository.InventoryItemRepository;
import com.tiktok.inventoryservice.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryItemRepository inventoryItemRepository;
    private final StockReservationRepository stockReservationRepository;
    private final InventoryMapper inventoryMapper;

    @Override
    @Transactional
    public void reserveStock(Long orderId, List<OrderItem> items) {
        for (OrderItem orderItem : items) {
            InventoryItem inventoryItem = findByProductId(orderItem.productId());
            inventoryItem.reserve(orderItem.quantity());

            stockReservationRepository.save(StockReservation.builder()
                    .orderId(orderId)
                    .productId(orderItem.productId())
                    .quantity(orderItem.quantity())
                    .status(ReservationStatus.RESERVED)
                    .build());
        }
    }

    @Override
    @Transactional
    public void releaseStock(Long orderId) {
        List<StockReservation> reservations = stockReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
        for (StockReservation reservation : reservations) {
            InventoryItem inventoryItem = findByProductId(reservation.getProductId());
            inventoryItem.release(reservation.getQuantity());
            reservation.markReleased();
        }
    }

    @Override
    @Transactional
    public void confirmStock(Long orderId) {
        List<StockReservation> reservations = stockReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
        for (StockReservation reservation : reservations) {
            InventoryItem inventoryItem = findByProductId(reservation.getProductId());
            inventoryItem.confirm(reservation.getQuantity());
            reservation.markConfirmed();
        }
    }

    @Override
    @Transactional
    public void provisionForProduct(Long productId, Long sellerId) {
        if (inventoryItemRepository.existsByProductIdAndDeletedAtIsNull(productId)) {
            return;
        }

        inventoryItemRepository.save(InventoryItem.builder()
                .productId(productId)
                .sellerId(sellerId)
                .availableQuantity(0)
                .reservedQuantity(0)
                .build());
    }

    @Override
    @Transactional
    public InventoryResponse restock(Long sellerId, Long productId, int quantity) {
        InventoryItem inventoryItem = findByProductId(productId);

        if (!sellerId.equals(inventoryItem.getSellerId())) {
            throw new NotInventoryOwnerException(productId);
        }

        inventoryItem.restock(quantity);

        return inventoryMapper.toResponse(inventoryItem);
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse getStock(Long productId) {
        return inventoryMapper.toResponse(findByProductId(productId));
    }

    private InventoryItem findByProductId(Long productId) {
        return inventoryItemRepository.findByProductIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new InventoryItemNotFoundException(productId));
    }
}
