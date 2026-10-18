package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.RequestDataExportRequest;
import com.tiktok.userservice.dto.response.DataExportAdminSummaryResponse;
import com.tiktok.userservice.dto.response.DataExportDownloadResponse;
import com.tiktok.userservice.dto.response.DataExportResponse;

import java.util.List;

/** "Download your data" for its owner, and for the admin console watching it. {@code DataExportWorker} builds the file. */
public interface DataExportService {

    /** The caller's latest requests, newest first. */
    List<DataExportResponse> list(Long userId);

    /**
     * Queues a new archive. The previous file, if one is ready, expires — as it does live. 409
     * DATA_EXPORT_IN_PROGRESS while another request is still processing.
     */
    DataExportResponse request(Long userId, RequestDataExportRequest request);

    /** 404 DATA_EXPORT_NOT_FOUND; 409 DATA_EXPORT_NOT_CANCELABLE once it is no longer processing. */
    DataExportResponse cancel(Long userId, Long requestId);

    /** 404 DATA_EXPORT_NOT_FOUND; 409 DATA_EXPORT_UNAVAILABLE unless READY and unexpired. */
    DataExportDownloadResponse downloadLink(Long userId, Long requestId);

    /** What is waiting, stuck or given up on, platform-wide. */
    DataExportAdminSummaryResponse adminSummary();
}
