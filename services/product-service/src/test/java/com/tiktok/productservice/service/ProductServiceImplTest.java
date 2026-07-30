package com.tiktok.productservice.service;

import com.tiktok.event.product.ProductCreatedEvent;
import com.tiktok.productservice.dto.request.CreateProductRequest;
import com.tiktok.productservice.dto.request.UpdateProductRequest;
import com.tiktok.productservice.dto.response.ProductResponse;
import com.tiktok.productservice.entity.Product;
import com.tiktok.productservice.entity.ProductStatus;
import com.tiktok.productservice.event.producer.ProductEventProducer;
import com.tiktok.productservice.exception.NotProductOwnerException;
import com.tiktok.productservice.exception.ProductNotFoundException;
import com.tiktok.productservice.mapper.ProductMapper;
import com.tiktok.productservice.repository.ProductRepository;
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
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductEventProducer productEventProducer;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productRepository, productMapper, productEventProducer);
    }

    private Product product(Long id, Long sellerId) {
        return Product.builder()
                .id(id)
                .sellerId(sellerId)
                .name("Phone case")
                .description("A sturdy case")
                .price(new BigDecimal("9.99"))
                .category("Accessories")
                .imageUrl("http://img/1.jpg")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void create_buildsActiveProductAndPublishesCreatedEvent() {
        CreateProductRequest request = new CreateProductRequest("Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg");
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            return Product.builder()
                    .id(1L)
                    .sellerId(p.getSellerId())
                    .name(p.getName())
                    .description(p.getDescription())
                    .price(p.getPrice())
                    .category(p.getCategory())
                    .imageUrl(p.getImageUrl())
                    .status(p.getStatus())
                    .build();
        });
        ProductResponse expected = new ProductResponse(1L, 100L, "Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg", ProductStatus.ACTIVE, Instant.now());
        when(productMapper.toResponse(any(Product.class))).thenReturn(expected);

        ProductResponse response = productService.create(100L, request);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        Product saved = captor.getValue();
        assertThat(saved.getSellerId()).isEqualTo(100L);
        assertThat(saved.getName()).isEqualTo("Phone case");
        assertThat(saved.getPrice()).isEqualByComparingTo("9.99");
        assertThat(saved.getStatus()).isEqualTo(ProductStatus.ACTIVE);

        ArgumentCaptor<ProductCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ProductCreatedEvent.class);
        verify(productEventProducer).publishProductCreated(eventCaptor.capture());
        assertThat(eventCaptor.getValue().productId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().sellerId()).isEqualTo(100L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void update_calledByOwner_updatesDetails() {
        Product existing = product(1L, 100L);
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));
        UpdateProductRequest request = new UpdateProductRequest("New name", "New desc", new BigDecimal("19.99"), "New cat", "http://img/2.jpg");
        ProductResponse expected = new ProductResponse(1L, 100L, "New name", "New desc", new BigDecimal("19.99"), "New cat", "http://img/2.jpg", ProductStatus.ACTIVE, existing.getCreatedAt());
        when(productMapper.toResponse(existing)).thenReturn(expected);

        ProductResponse response = productService.update(100L, 1L, request);

        assertThat(existing.getName()).isEqualTo("New name");
        assertThat(existing.getDescription()).isEqualTo("New desc");
        assertThat(existing.getPrice()).isEqualByComparingTo("19.99");
        assertThat(existing.getCategory()).isEqualTo("New cat");
        assertThat(existing.getImageUrl()).isEqualTo("http://img/2.jpg");
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void update_calledByNonOwner_throwsNotProductOwner() {
        Product existing = product(1L, 100L);
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));
        UpdateProductRequest request = new UpdateProductRequest("New name", "New desc", new BigDecimal("19.99"), "New cat", "http://img/2.jpg");

        assertThatThrownBy(() -> productService.update(200L, 1L, request))
                .isInstanceOf(NotProductOwnerException.class);
        assertThat(existing.getName()).isEqualTo("Phone case");
    }

    @Test
    void update_missingProduct_throwsProductNotFound() {
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        UpdateProductRequest request = new UpdateProductRequest("New name", "New desc", new BigDecimal("19.99"), "New cat", "http://img/2.jpg");

        assertThatThrownBy(() -> productService.update(100L, 1L, request))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void getById_existingProduct_returnsMappedResponse() {
        Product existing = product(1L, 100L);
        ProductResponse expected = new ProductResponse(1L, 100L, "Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg", ProductStatus.ACTIVE, existing.getCreatedAt());
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));
        when(productMapper.toResponse(existing)).thenReturn(expected);

        assertThat(productService.getById(1L)).isEqualTo(expected);
    }

    @Test
    void getById_missingProduct_throwsProductNotFound() {
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(1L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void listActive_mapsOnlyActiveProducts() {
        Product p1 = product(1L, 100L);
        Product p2 = product(2L, 200L);
        ProductResponse r1 = new ProductResponse(1L, 100L, "Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg", ProductStatus.ACTIVE, p1.getCreatedAt());
        ProductResponse r2 = new ProductResponse(2L, 200L, "Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg", ProductStatus.ACTIVE, p2.getCreatedAt());
        when(productRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(ProductStatus.ACTIVE)).thenReturn(List.of(p1, p2));
        when(productMapper.toResponse(p1)).thenReturn(r1);
        when(productMapper.toResponse(p2)).thenReturn(r2);

        assertThat(productService.listActive()).containsExactly(r1, r2);
    }

    @Test
    void listBySeller_mapsAllProductsForSeller() {
        Product p1 = product(1L, 100L);
        ProductResponse r1 = new ProductResponse(1L, 100L, "Phone case", "A sturdy case", new BigDecimal("9.99"), "Accessories", "http://img/1.jpg", ProductStatus.ACTIVE, p1.getCreatedAt());
        when(productRepository.findBySellerIdAndDeletedAtIsNullOrderByCreatedAtDesc(100L)).thenReturn(List.of(p1));
        when(productMapper.toResponse(p1)).thenReturn(r1);

        assertThat(productService.listBySeller(100L)).containsExactly(r1);
    }

    @Test
    void delete_calledByOwner_marksProductDeleted() {
        Product existing = product(1L, 100L);
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));

        productService.delete(100L, 1L);

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void delete_calledByNonOwner_throwsNotProductOwnerAndDoesNotDelete() {
        Product existing = product(1L, 100L);
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> productService.delete(200L, 1L))
                .isInstanceOf(NotProductOwnerException.class);
        assertThat(existing.isDeleted()).isFalse();
    }

    @Test
    void delete_missingProduct_throwsProductNotFound() {
        when(productRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.delete(100L, 1L))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
