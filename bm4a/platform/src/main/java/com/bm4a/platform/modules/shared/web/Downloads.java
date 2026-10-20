package com.bm4a.platform.modules.shared.web;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Response headers for file downloads. */
public final class Downloads {

    private static final String ATTR_CHARS = "!#$&+-.^_`|~";

    private Downloads() {
    }

    /**
     * {@code Content-Disposition: attachment} with an ASCII fallback name and the exact name as RFC 8187
     * {@code filename*}, plus {@code X-Content-Type-Options: nosniff} and {@code Cache-Control: private, no-store}.
     */
    public static HttpHeaders attachment(String filename, MediaType type, long contentLength) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        if (contentLength >= 0) {
            headers.setContentLength(contentLength);
        }
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename));
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("private, no-store");
        return headers;
    }

    static String contentDisposition(String filename) {
        StringBuilder ascii = new StringBuilder();
        filename.codePoints().forEach(c -> ascii.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? (char) c : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || ATTR_CHARS.indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
