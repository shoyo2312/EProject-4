package com.tiktok.cartservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductClientImpl implements ProductClient {

    private final RestClient productServiceRestClient;

    @Override
    public Optional<ProductSummary> getProduct(Long productId) {
        try {
            ApiResponse<ProductSummary> response = productServiceRestClient.get()
                    .uri("/api/v1/products/{productId}", productId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<ProductSummary>>() {
                    });
            return Optional.ofNullable(response).map(ApiResponse::data);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
