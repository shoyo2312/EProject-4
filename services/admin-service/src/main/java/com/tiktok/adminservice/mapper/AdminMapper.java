package com.tiktok.adminservice.mapper;

import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.Report;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AdminMapper {

    ReportResponse toResponse(Report report);

    ModerationActionResponse toResponse(ModerationAction action);
}
