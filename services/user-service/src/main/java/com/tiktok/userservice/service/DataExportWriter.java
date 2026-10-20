package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.DataExportFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lays the collected sections out as the zip the requester downloads: a folder per section with a
 * text file per list for TXT, or a single {@code user_data.json} for JSON.
 */
@Component
@RequiredArgsConstructor
public class DataExportWriter {

    static final String JSON_ENTRY = "user_data.json";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    /** Writes the archive and closes {@code out}. */
    public void write(DataExportFormat format, List<DataExportSection> sections, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            if (format == DataExportFormat.JSON) {
                zip.putNextEntry(new ZipEntry(JSON_ENTRY));
                zip.write(objectMapper.writer().withDefaultPrettyPrinter().writeValueAsBytes(asJson(sections)));
                zip.closeEntry();
                return;
            }
            for (DataExportSection section : sections) {
                for (DataExportSection.File file : section.files()) {
                    zip.putNextEntry(new ZipEntry(section.title() + "/" + file.title() + ".txt"));
                    zip.write(asText(file.records()).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
    }

    /** {@code "2026-10-18 20:30:06 UTC"}, or empty for no time at all. */
    public static String date(Instant at) {
        return at == null ? "" : DATE.format(at) + " UTC";
    }

    static Map<String, Map<String, List<Map<String, String>>>> asJson(List<DataExportSection> sections) {
        Map<String, Map<String, List<Map<String, String>>>> json = new LinkedHashMap<>();
        for (DataExportSection section : sections) {
            Map<String, List<Map<String, String>>> files = new LinkedHashMap<>();
            section.files().forEach(file -> files.put(file.title(), file.records()));
            json.put(section.title(), files);
        }
        return json;
    }

    /** One {@code Label: value} line per field, a blank line between records. */
    static String asText(List<Map<String, String>> records) {
        if (records.isEmpty()) {
            return "No data.\n";
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, String> record : records) {
            record.forEach((label, value) -> text.append(label).append(": ").append(value).append('\n'));
            text.append('\n');
        }
        return text.toString();
    }
}
