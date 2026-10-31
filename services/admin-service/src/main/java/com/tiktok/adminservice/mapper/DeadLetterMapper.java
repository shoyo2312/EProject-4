package com.tiktok.adminservice.mapper;

import com.tiktok.adminservice.dto.response.DeadLetterRecordResponse;
import com.tiktok.adminservice.dto.response.DeadLetterReplayResponse;
import com.tiktok.adminservice.dto.response.DeadLetterTopicResponse;
import com.tiktok.adminservice.entity.DeadLetterReplay;
import com.tiktok.kafka.dlt.DeadLetter;
import com.tiktok.kafka.dlt.DeadLetterTopic;
import com.tiktok.kafka.dlt.ReplayResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DeadLetterMapper {

    @Mapping(target = "topic", source = "name")
    DeadLetterTopicResponse toResponse(DeadLetterTopic topic);

    DeadLetterReplayResponse toResponse(ReplayResult result);

    @Mapping(target = "parkedAt", source = "letter.parkedAt")
    @Mapping(target = "lastReplay", source = "lastReplay")
    DeadLetterRecordResponse toResponse(DeadLetter letter, DeadLetterReplay lastReplay);

    @Mapping(target = "at", source = "createdAt")
    DeadLetterRecordResponse.LastReplay toLastReplay(DeadLetterReplay replay);
}
