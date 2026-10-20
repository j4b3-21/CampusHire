package com.bm4a.platform.modules.dataexchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.http.MediaType;

/** A generated or stored file to send; the content stream is closed by whoever writes it to the response. */
public record DownloadFile(String fileName, MediaType mediaType, long sizeBytes, InputStream content) {

    /** Serves a temporary file and deletes it once the stream is closed. */
    static DownloadFile temporary(Path file, String fileName, MediaType mediaType) {
        try {
            return new DownloadFile(fileName, mediaType, Files.size(file),
                    Files.newInputStream(file, StandardOpenOption.DELETE_ON_CLOSE));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
