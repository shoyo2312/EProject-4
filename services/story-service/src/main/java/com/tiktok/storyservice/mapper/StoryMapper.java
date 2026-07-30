package com.tiktok.storyservice.mapper;

import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryView;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface StoryMapper {

    StoryResponse toResponse(Story story);

    StoryViewerResponse toViewerResponse(StoryView storyView);
}
