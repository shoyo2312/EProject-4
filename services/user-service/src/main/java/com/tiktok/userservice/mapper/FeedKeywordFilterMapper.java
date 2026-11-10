package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.KeywordFilterResponse;
import com.tiktok.userservice.entity.FeedKeywordFilter;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FeedKeywordFilterMapper {

    KeywordFilterResponse toResponse(FeedKeywordFilter filter);
}
