package com.tiktok.apigateway.controller;

import com.tiktok.apigateway.dto.MeResponse;
import com.tiktok.apigateway.service.MeService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;

    @GetMapping("/me")
    public Mono<ApiResponse<MeResponse>> getMe(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        return meService.getMe(authorizationHeader).map(ApiResponse::success);
    }
}
