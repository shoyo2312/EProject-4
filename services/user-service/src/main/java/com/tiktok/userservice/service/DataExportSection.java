package com.tiktok.userservice.service;

import java.util.List;
import java.util.Map;

/**
 * One kind of data in a "Download your data" archive: a folder of text files in the TXT archive,
 * a key of {@code user_data.json} in the JSON one. Values are already formatted for reading, so
 * both formats show the same text.
 */
public record DataExportSection(String title, List<File> files) {

    /** One list of records, each an ordered set of labelled values. */
    public record File(String title, List<Map<String, String>> records) {
    }
}
