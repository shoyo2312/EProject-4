package com.tiktok.orderservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Forwards the caller's own bearer token to cart-service rather than using a service
 * account, so the cart returned is always scoped to the same user checking out.
 */
@Component
@RequiredArgsConstructor
public class CartClientImpl implements CartClient {

    private final RestClient cartServiceRestClient;

    @Override
    public CartSummary getCart(String bearerToken) {
        ApiResponse<CartSummary> response = cartServiceRestClient.get()
                .uri("/api/v1/cart")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<CartSummary>>() {
                });
        return response.data();
    }

    @Override
    public void clearCart(String bearerToken) {
        cartServiceRestClient.delete()
                .uri("/api/v1/cart")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .retrieve()
                .toBodilessEntity();
    }
}
