package com.tiktok.searchservice.mapper;

import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SearchMapper {

    VideoSearchResponse toResponse(VideoDocument document);
}
