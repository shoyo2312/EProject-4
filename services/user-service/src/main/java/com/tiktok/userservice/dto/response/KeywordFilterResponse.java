package com.tiktok.userservice.dto.response;

public record KeywordFilterResponse(Long id, String keyword, boolean forYou, boolean following, boolean friends) {
}
