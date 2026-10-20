package com.bm4a.platform.modules.files.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bm4a.platform.modules.files.StoredFileView;
import com.bm4a.platform.modules.files.internal.FileService;
import com.bm4a.platform.modules.shared.web.Downloads;
import com.bm4a.platform.modules.users.CurrentUser;

/**
 * Upload ({@code multipart/form-data}, part {@code file}) and download of organization files (ADR-005). Files the
 * caller may not read are 404, like missing ones.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/files")
class FileController {

    private final FileService files;

    FileController(FileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<FileResponse> upload(CurrentUser user, @PathVariable UUID orgId,
            @RequestPart("file") MultipartFile file) throws IOException {
        StoredFileView stored;
        try (InputStream content = file.getInputStream()) {
            stored = files.upload(user, orgId, file.getOriginalFilename(), file.getContentType(), content);
        }
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + orgId + "/files/" + stored.id()))
                .body(FileResponse.of(stored));
    }

    @GetMapping("/{id}")
    ResponseEntity<Resource> download(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID id) {
        FileService.Download download = files.download(user, orgId, id);
        StoredFileView file = download.file();
        return ResponseEntity.ok()
                .headers(Downloads.attachment(file.name(), MediaType.parseMediaType(file.contentType()),
                        file.sizeBytes()))
                .body(new InputStreamResource(download.content()));
    }

    @GetMapping("/{id}/meta")
    FileResponse meta(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID id) {
        return FileResponse.of(files.readable(user, orgId, id));
    }

    record FileResponse(UUID id, String name, String contentType, long sizeBytes, String sha256, Instant createdAt,
            UUID createdBy) {

        static FileResponse of(StoredFileView file) {
            return new FileResponse(file.id(), file.name(), file.contentType(), file.sizeBytes(), file.sha256(),
                    file.createdAt(), file.createdBy());
        }
    }
}
