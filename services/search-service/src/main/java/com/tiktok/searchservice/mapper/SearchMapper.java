package com.tiktok.searchservice.mapper;

import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.dto.response.ProductSearchResponse;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SearchMapper {

    VideoSearchResponse toResponse(VideoDocument document);

    ProductSearchResponse toResponse(ProductDocument document);
}
