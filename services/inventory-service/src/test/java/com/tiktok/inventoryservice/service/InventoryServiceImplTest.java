package com.tiktok.inventoryservice.service;

import com.tiktok.event.order.OrderItem;
import com.tiktok.inventoryservice.dto.response.InventoryResponse;
import com.tiktok.inventoryservice.entity.InventoryItem;
import com.tiktok.inventoryservice.entity.ReservationStatus;
import com.tiktok.inventoryservice.entity.StockReservation;
import com.tiktok.inventoryservice.exception.InsufficientStockException;
import com.tiktok.inventoryservice.exception.InventoryItemNotFoundException;
import com.tiktok.inventoryservice.exception.NotInventoryOwnerException;
import com.tiktok.inventoryservice.mapper.InventoryMapper;
import com.tiktok.inventoryservice.repository.InventoryItemRepository;
import com.tiktok.inventoryservice.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private StockReservationRepository stockReservationRepository;

    @Mock
    private InventoryMapper inventoryMapper;

    private InventoryServiceImpl inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryServiceImpl(inventoryItemRepository, stockReservationRepository, inventoryMapper);
    }

    private InventoryItem item(Long productId, int available, int reserved) {
        return InventoryItem.builder()
                .productId(productId)
                .sellerId(500L)
                .availableQuantity(available)
                .reservedQuantity(reserved)
                .build();
    }

    @Test
    void reserveStock_allItemsAvailable_reservesEachAndRecordsReservation() {
        InventoryItem item1 = item(1L, 10, 0);
        InventoryItem item2 = item(2L, 5, 0);
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(item2));

        inventoryService.reserveStock(900L, List.of(
                new OrderItem(1L, 3, new BigDecimal("9.99")),
                new OrderItem(2L, 2, new BigDecimal("4.99"))));

        assertThat(item1.getAvailableQuantity()).isEqualTo(7);
        assertThat(item1.getReservedQuantity()).isEqualTo(3);
        assertThat(item2.getAvailableQuantity()).isEqualTo(3);
        assertThat(item2.getReservedQuantity()).isEqualTo(2);

        ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
        verify(stockReservationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> r.getOrderId().equals(900L) && r.getStatus() == ReservationStatus.RESERVED);
    }

    @Test
    void reserveStock_insufficientStockOnOneItem_throwsInsufficientStockException() {
        InventoryItem item1 = item(1L, 1, 0);
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));

        assertThatThrownBy(() -> inventoryService.reserveStock(900L, List.of(new OrderItem(1L, 5, new BigDecimal("9.99")))))
                .isInstanceOf(InsufficientStockException.class);
        verify(stockReservationRepository, never()).save(any());
    }

    @Test
    void reserveStock_productNotProvisioned_throwsInventoryItemNotFoundException() {
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.reserveStock(900L, List.of(new OrderItem(1L, 1, new BigDecimal("9.99")))))
                .isInstanceOf(InventoryItemNotFoundException.class);
    }

    @Test
    void releaseStock_releasesEveryReservedItemForOrder() {
        InventoryItem item1 = item(1L, 7, 3);
        StockReservation reservation = StockReservation.builder().orderId(900L).productId(1L).quantity(3).status(ReservationStatus.RESERVED).build();
        when(stockReservationRepository.findByOrderIdAndStatus(900L, ReservationStatus.RESERVED)).thenReturn(List.of(reservation));
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));

        inventoryService.releaseStock(900L);

        assertThat(item1.getAvailableQuantity()).isEqualTo(10);
        assertThat(item1.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void releaseStock_noReservationsForOrder_doesNothing() {
        when(stockReservationRepository.findByOrderIdAndStatus(900L, ReservationStatus.RESERVED)).thenReturn(List.of());

        inventoryService.releaseStock(900L);

        verifyNoInteractions(inventoryItemRepository);
    }

    @Test
    void confirmStock_confirmsEveryReservedItemForOrder() {
        InventoryItem item1 = item(1L, 7, 3);
        StockReservation reservation = StockReservation.builder().orderId(900L).productId(1L).quantity(3).status(ReservationStatus.RESERVED).build();
        when(stockReservationRepository.findByOrderIdAndStatus(900L, ReservationStatus.RESERVED)).thenReturn(List.of(reservation));
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));

        inventoryService.confirmStock(900L);

        assertThat(item1.getAvailableQuantity()).isEqualTo(7);
        assertThat(item1.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void provisionForProduct_newProduct_createsZeroStockInventoryItem() {
        when(inventoryItemRepository.existsByProductIdAndDeletedAtIsNull(1L)).thenReturn(false);

        inventoryService.provisionForProduct(1L, 500L);

        ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
        verify(inventoryItemRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(1L);
        assertThat(captor.getValue().getSellerId()).isEqualTo(500L);
        assertThat(captor.getValue().getAvailableQuantity()).isZero();
    }

    @Test
    void provisionForProduct_alreadyProvisioned_isNoOp() {
        when(inventoryItemRepository.existsByProductIdAndDeletedAtIsNull(1L)).thenReturn(true);

        inventoryService.provisionForProduct(1L, 500L);

        verify(inventoryItemRepository, never()).save(any());
    }

    @Test
    void restock_calledByOwner_increasesAvailableQuantity() {
        InventoryItem item1 = item(1L, 5, 0);
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));
        InventoryResponse expected = new InventoryResponse(1L, 15, 0);
        when(inventoryMapper.toResponse(item1)).thenReturn(expected);

        InventoryResponse response = inventoryService.restock(500L, 1L, 10);

        assertThat(item1.getAvailableQuantity()).isEqualTo(15);
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void restock_calledByNonOwner_throwsNotInventoryOwnerExceptionAndDoesNotChangeStock() {
        InventoryItem item1 = item(1L, 5, 0);
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));

        assertThatThrownBy(() -> inventoryService.restock(999L, 1L, 10))
                .isInstanceOf(NotInventoryOwnerException.class);
        assertThat(item1.getAvailableQuantity()).isEqualTo(5);
    }

    @Test
    void restock_missingProduct_throwsInventoryItemNotFoundException() {
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.restock(500L, 1L, 10))
                .isInstanceOf(InventoryItemNotFoundException.class);
    }

    @Test
    void getStock_existingProduct_returnsMappedResponse() {
        InventoryItem item1 = item(1L, 5, 2);
        InventoryResponse expected = new InventoryResponse(1L, 5, 2);
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(item1));
        when(inventoryMapper.toResponse(item1)).thenReturn(expected);

        assertThat(inventoryService.getStock(1L)).isEqualTo(expected);
    }

    @Test
    void getStock_missingProduct_throwsInventoryItemNotFoundException() {
        when(inventoryItemRepository.findByProductIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(1L))
                .isInstanceOf(InventoryItemNotFoundException.class);
    }
}
