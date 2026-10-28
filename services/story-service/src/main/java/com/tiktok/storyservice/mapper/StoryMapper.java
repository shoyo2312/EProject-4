package com.tiktok.storyservice.mapper;

import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryReaction;
import com.tiktok.storyservice.entity.StoryView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Map;

@Mapper(componentModel = "spring")
public interface StoryMapper {

    /** The owner's view; {@link StoryResponse#forViewer()} strips it for anyone else. */
    @Mapping(target = "mediaUrl", source = "mediaUrl")
    @Mapping(target = "viewedByMe", source = "viewedByMe")
    @Mapping(target = "myReaction", source = "myReaction")
    @Mapping(target = "reactionCounts", source = "reactionCounts")
    StoryResponse toResponse(Story story, String mediaUrl, boolean viewedByMe, StoryReaction myReaction,
                             Map<StoryReaction, Long> reactionCounts);

    StoryViewerResponse toViewerResponse(StoryView storyView);
}
