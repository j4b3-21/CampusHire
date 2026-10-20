package com.bm4a.platform.modules.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Detects the type of file content from its bytes, never from the name or the declared type. Allowed: PDF, PNG, JPEG,
 * WebP, XLSX and DOCX (Office Open XML packages without macros), and UTF-8 text (CSV or plain text). The declared
 * type and the file name only choose between {@link #CSV} and {@link #TEXT}, both inert.
 */
public final class ContentSniffer {

    public static final String PDF = "application/pdf";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String WEBP = "image/webp";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String CSV = "text/csv";
    public static final String TEXT = "text/plain";

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] ZIP_MAGIC = { 'P', 'K', 3, 4 };
    private static final Set<String> CSV_DECLARED_TYPES = Set.of("text/csv", "application/csv",
            "text/comma-separated-values", "application/vnd.ms-excel");

    private ContentSniffer() {
    }

    /** @return the detected media type, or empty when the content is not on the allow-list (or empty) */
    public static Optional<String> detect(Path file, String name, String declaredType) throws IOException {
        byte[] head = new byte[16];
        int length;
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            length = in.readNBytes(head, 0, head.length);
        }
        if (length == 0) {
            return Optional.empty();
        }
        head = Arrays.copyOf(head, length);
        if (startsWith(head, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (startsWith(head, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        if (startsWith(head, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W'
                && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of(WEBP);
        }
        if (startsWith(head, ZIP_MAGIC)) {
            return officeDocument(file);
        }
        if (!utf8Text(file)) {
            return Optional.empty();
        }
        return Optional.of(csv(name, declaredType) ? CSV : TEXT);
    }

    /** Whether the type is one of the spreadsheet formats the data exchange module reads. */
    public static boolean tabular(String contentType) {
        return XLSX.equals(contentType) || CSV.equals(contentType) || TEXT.equals(contentType);
    }

    /** Only the central directory is read; nothing is decompressed. Macro-enabled packages are rejected. */
    private static Optional<String> officeDocument(Path file) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            zip.stream().map(ZipEntry::getName).forEach(names::add);
        } catch (ZipException ex) {
            return Optional.empty();
        }
        if (!names.contains("[Content_Types].xml")) {
            return Optional.empty();
        }
        if (names.contains("xl/workbook.xml") && !names.contains("xl/vbaProject.bin")) {
            return Optional.of(XLSX);
        }
        if (names.contains("word/document.xml") && !names.contains("word/vbaProject.bin")) {
            return Optional.of(DOCX);
        }
        return Optional.empty();
    }

    /** Valid UTF-8 (optional BOM) without NUL or other control characters besides tab, line breaks and form feed. */
    private static boolean utf8Text(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(java.nio.file.Files.newInputStream(file), decoder)) {
            char[] buffer = new char[8192];
            boolean start = true;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    if (start && i == 0 && c == '\uFEFF') {
                        continue;
                    }
                    if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') || c == 0x7F) {
                        return false;
                    }
                }
                start = false;
            }
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private static boolean csv(String name, String declaredType) {
        String type = declaredType == null ? "" : declaredType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return CSV_DECLARED_TYPES.contains(type)
                || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv"));
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}
