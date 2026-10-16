package com.tiktok.adminservice.mapper;

import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.entity.ModerationTerm;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ModerationTermMapper {

    ModerationTermResponse toResponse(ModerationTerm term);

    ModerationTermSnapshotResponse.Term toSnapshotTerm(ModerationTerm term);
}
