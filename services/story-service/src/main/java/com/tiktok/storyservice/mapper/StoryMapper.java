package com.tiktok.storyservice.mapper;

import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StoryMapper {

    /** The owner's view; {@link StoryResponse#forViewer()} strips it for anyone else. */
    @Mapping(target = "mediaUrl", source = "mediaUrl")
    @Mapping(target = "viewedByMe", source = "viewedByMe")
    StoryResponse toResponse(Story story, String mediaUrl, boolean viewedByMe);

    StoryViewerResponse toViewerResponse(StoryView storyView);
}
