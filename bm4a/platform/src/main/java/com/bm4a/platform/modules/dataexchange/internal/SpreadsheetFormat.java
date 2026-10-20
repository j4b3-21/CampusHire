package com.bm4a.platform.modules.dataexchange.internal;

import java.util.Locale;
import java.util.Optional;

import org.springframework.http.MediaType;

import com.bm4a.platform.modules.files.ContentSniffer;
import com.bm4a.platform.modules.shared.error.ApiException;

public enum SpreadsheetFormat {

    XLSX("xlsx", ContentSniffer.XLSX), CSV("csv", "text/csv;charset=UTF-8");

    private final String extension;
    private final MediaType mediaType;

    SpreadsheetFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = MediaType.parseMediaType(mediaType);
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    /** The {@code format} request parameter; absent means XLSX. */
    public static SpreadsheetFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return XLSX;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "xlsx" -> XLSX;
            case "csv" -> CSV;
            default -> throw ApiException.badRequest("invalid_format", "format must be xlsx or csv");
        };
    }

    /** The format of sniffed file content (CSV and plain text are both read as CSV). */
    static Optional<SpreadsheetFormat> ofContentType(String contentType) {
        if (ContentSniffer.XLSX.equals(contentType)) {
            return Optional.of(XLSX);
        }
        return ContentSniffer.tabular(contentType) ? Optional.of(CSV) : Optional.empty();
    }
}
