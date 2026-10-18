package com.tiktok.userservice.dto.request;

import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.DataExportFormat;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/** POST body for {@code /users/me/data-exports}: the file format and at least one category. */
public record RequestDataExportRequest(
        @NotNull DataExportFormat format,
        @NotEmpty Set<@NotNull DataExportCategory> categories
) {
}
