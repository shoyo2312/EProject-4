package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.DataExportFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DataExportWriterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final DataExportWriter writer = new DataExportWriter(objectMapper);

    private static final List<DataExportSection> SECTIONS = List.of(
            new DataExportSection("Profile and Settings", List.of(
                    new DataExportSection.File("Profile Info", List.of(record("Username", "alice", "Bio", ""))))),
            new DataExportSection("Likes and Favorites", List.of(
                    new DataExportSection.File("Like List", List.of(
                            record("Date", "2026-10-18 20:30:06 UTC", "Video ID", "11"),
                            record("Date", "2026-10-17 08:00:00 UTC", "Video ID", "12"))),
                    new DataExportSection.File("Favorite Videos", List.of()))));

    @Test
    void txtIsAFolderPerSectionAndAFilePerList() throws Exception {
        Map<String, String> entries = unzip(DataExportFormat.TXT);

        assertThat(entries).containsOnlyKeys(
                "Profile and Settings/Profile Info.txt",
                "Likes and Favorites/Like List.txt",
                "Likes and Favorites/Favorite Videos.txt");
        assertThat(entries.get("Likes and Favorites/Like List.txt")).isEqualTo("""
                Date: 2026-10-18 20:30:06 UTC
                Video ID: 11

                Date: 2026-10-17 08:00:00 UTC
                Video ID: 12

                """);
        assertThat(entries.get("Profile and Settings/Profile Info.txt")).isEqualTo("Username: alice\nBio: \n\n");
    }

    @Test
    void anEmptyListStillGetsItsFile() throws Exception {
        assertThat(unzip(DataExportFormat.TXT).get("Likes and Favorites/Favorite Videos.txt")).isEqualTo("No data.\n");
    }

    @Test
    void jsonIsOneFileKeyedBySectionThenList() throws Exception {
        Map<String, String> entries = unzip(DataExportFormat.JSON);

        assertThat(entries).containsOnlyKeys(DataExportWriter.JSON_ENTRY);
        JsonNode json = objectMapper.readTree(entries.get(DataExportWriter.JSON_ENTRY));
        assertThat(json.propertyNames()).containsExactly("Profile and Settings", "Likes and Favorites");
        assertThat(json.at("/Likes and Favorites/Like List/1/Video ID").asString()).isEqualTo("12");
        assertThat(json.at("/Likes and Favorites/Favorite Videos").isEmpty()).isTrue();
    }

    @Test
    void datesAreUtcAndSayWhichZone() {
        assertThat(DataExportWriter.date(Instant.parse("2026-10-18T13:30:06.789Z"))).isEqualTo("2026-10-18 13:30:06 UTC");
        assertThat(DataExportWriter.date(null)).isEmpty();
    }

    private Map<String, String> unzip(DataExportFormat format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(format, SECTIONS, out);

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static Map<String, String> record(String... labelsAndValues) {
        Map<String, String> record = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            record.put(labelsAndValues[i], labelsAndValues[i + 1]);
        }
        return record;
    }
}
