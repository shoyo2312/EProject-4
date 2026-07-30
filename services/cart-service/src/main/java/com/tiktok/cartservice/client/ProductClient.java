package com.tiktok.cartservice.client;

import java.util.Optional;

public interface ProductClient {

    Optional<ProductSummary> getProduct(Long productId);
}
