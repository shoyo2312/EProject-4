package com.tiktok.apigateway.service;

import com.tiktok.apigateway.dto.MeResponse;
import reactor.core.publisher.Mono;

public interface MeService {

    Mono<MeResponse> getMe(String authorizationHeader);
}
