package com.tiktok.orderservice.client;

public interface CartClient {

    CartSummary getCart(String bearerToken);

    void clearCart(String bearerToken);
}
