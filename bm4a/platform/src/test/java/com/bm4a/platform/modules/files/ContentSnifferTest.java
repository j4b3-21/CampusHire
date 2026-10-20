package com.bm4a.platform.modules.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bm4a.platform.support.Fixtures;

class ContentSnifferTest {

    @TempDir
    Path dir;

    @Test
    void detectsAllowedTypesFromMagicBytes() throws IOException {
        assertThat(detect("%PDF-1.7\n%...".getBytes(StandardCharsets.US_ASCII), "a.pdf", null))
                .contains(ContentSniffer.PDF);
        assertThat(detect(Fixtures.PNG, "a.png", "image/png")).contains(ContentSniffer.PNG);
        assertThat(detect(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10 }, "a.jpg", null))
                .contains(ContentSniffer.JPEG);
        assertThat(detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1), "a.webp",
                null)).contains(ContentSniffer.WEBP);
        assertThat(detect(zip("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml"),
                "a.xlsx", null)).contains(ContentSniffer.XLSX);
        assertThat(detect(zip("[Content_Types].xml", "word/document.xml"), "a.docx", null))
                .contains(ContentSniffer.DOCX);
    }

    @Test
    void textIsCsvOrPlainTextDependingOnlyOnTheHints() throws IOException {
        byte[] csv = "\uFEFFroll,name\n21CS001,Asha\n".getBytes(StandardCharsets.UTF_8);
        assertThat(detect(csv, "students.csv", null)).contains(ContentSniffer.CSV);
        assertThat(detect(csv, "students", "application/vnd.ms-excel")).contains(ContentSniffer.CSV);
        assertThat(detect(csv, "notes.txt", "text/plain")).contains(ContentSniffer.TEXT);
        assertThat(detect("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "x.html", "text/html"))
                .contains(ContentSniffer.TEXT);
    }

    @Test
    void spoofedExtensionsAndDeclaredTypesAreIgnored() throws IOException {
        assertThat(detect(Fixtures.PNG, "resume.pdf", "application/pdf")).contains(ContentSniffer.PNG);
        assertThat(detect("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "photo.png", "image/png"))
                .contains(ContentSniffer.PDF);
        assertThat(detect(new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0 }, "resume.pdf", "application/pdf"))
                .isEmpty();
    }

    @Test
    void rejectsEverythingElse() throws IOException {
        assertThat(detect(new byte[0], "empty.csv", null)).isEmpty();
        assertThat(detect(zip("[Content_Types].xml", "xl/workbook.xml", "xl/vbaProject.bin"), "macro.xlsx", null))
                .as("macro-enabled workbook").isEmpty();
        assertThat(detect(zip("payload.exe"), "archive.xlsx", null)).as("plain zip").isEmpty();
        assertThat(detect(new byte[] { 'P', 'K', 3, 4, 1, 2, 3 }, "broken.xlsx", null)).as("corrupt zip").isEmpty();
        assertThat(detect(new byte[] { 'a', 0, 'b' }, "a.csv", "text/csv")).as("NUL byte").isEmpty();
        assertThat(detect(new byte[] { 'n', 'a', 'm', (byte) 0xE9, '\n' }, "latin1.csv", "text/csv"))
                .as("not UTF-8").isEmpty();
        assertThat(detect(new byte[] { 'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0 }, "a.gif",
                "image/gif")).as("GIF is not on the allow-list").isEmpty();
    }

    private Optional<String> detect(byte[] content, String name, String declaredType) throws IOException {
        Path file = Files.createTempFile(dir, "sniff", ".bin");
        Files.write(file, content);
        return ContentSniffer.detect(file, name, declaredType);
    }

    private static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
