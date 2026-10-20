package com.bm4a.platform.modules.files.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.files.ContentSniffer;
import com.bm4a.platform.modules.files.Files;
import com.bm4a.platform.modules.files.StoredFileView;
import com.bm4a.platform.modules.files.internal.FileRepository.FileRow;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.FileReferenceValidator;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.ActiveMembership;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores and serves files. Uploads are spooled to a temporary file (bounded by the size limit) while the SHA-256 is
 * computed, sniffed, written to the object store and then registered; a failed registration removes the object again.
 * <p>
 * Reading a file through the API is allowed for organization admins, the uploader, and members who can read (within
 * their record scope, ADR-004) an active record whose visible file or image field references it. Everyone else gets
 * 404, exactly as for a missing file.
 */
@Service
public class FileService implements Files, FileReferenceValidator {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    private static final int MAX_NAME_LENGTH = 255;
    private static final Rejection MISSING = new Rejection("invalid_reference", "referenced file does not exist");

    private final FileRepository repository;
    private final FileStorage storage;
    private final FilesProperties properties;
    private final TenantAccess tenants;
    private final ModuleAccess access;
    private final ModuleSchemas schemas;
    private final RecordReader records;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    FileService(FileRepository repository, FileStorage storage, FilesProperties properties, TenantAccess tenants,
            ModuleAccess access, ModuleSchemas schemas, RecordReader records, AuditLog audit,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.tenants = tenants;
        this.access = access;
        this.schemas = schemas;
        this.records = records;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** A file the caller may read, with its open content stream (the caller closes it). */
    public record Download(StoredFileView file, InputStream content) {
    }

    // ---- Files ---------------------------------------------------------------------------------------------

    @Override
    public StoredFileView store(TenantContext tenant, String name, String declaredType, InputStream content) {
        Path temp = null;
        try {
            temp = java.nio.file.Files.createTempFile("bm4a-upload-", ".bin");
            MessageDigest digest = sha256();
            long size = spool(content, temp, digest);
            if (size == 0) {
                throw ApiException.badRequest("empty_file", "The file is empty");
            }
            String type = ContentSniffer.detect(temp, name, declaredType)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type",
                            "Only PDF, PNG, JPEG, WebP, XLSX, DOCX and UTF-8 CSV or text files are accepted"));
            Instant now = SqlValues.now(clock);
            UUID id = UUID.randomUUID();
            String key = tenant.organizationId() + "/" + KEY_MONTH.format(now) + "/" + id;
            StoredFileView file = new StoredFileView(id, tenant.organizationId(), displayName(name), type, size,
                    HexFormat.of().formatHex(digest.digest()), now, tenant.userId());
            storage.put(key, temp, size, type);
            try {
                transactions.executeWithoutResult(status -> {
                    repository.insert(new FileRow(file, key));
                    audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "file.uploaded", "file", id)
                            .with("contentType", type)
                            .with("sizeBytes", size));
                });
            } catch (RuntimeException ex) {
                removeQuietly(key);
                throw ex;
            }
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public StoredFileView store(TenantContext tenant, String name, String declaredType, byte[] content) {
        return store(tenant, name, declaredType, new ByteArrayInputStream(content));
    }

    @Override
    public InputStream open(UUID organizationId, UUID fileId) {
        FileRow row = repository.findActive(organizationId, fileId).orElseThrow(() -> ApiException.notFound("File"));
        return storage.open(row.storageKey());
    }

    @Override
    public Optional<StoredFileView> find(UUID organizationId, UUID fileId) {
        return repository.findActive(organizationId, fileId).map(FileRow::view);
    }

    // ---- FileReferenceValidator ----------------------------------------------------------------------------

    @Override
    public Optional<Rejection> validate(UUID organizationId, String fileId, boolean image) {
        UUID id;
        try {
            id = UUID.fromString(fileId);
        } catch (IllegalArgumentException ex) {
            return Optional.of(MISSING);
        }
        Optional<FileRow> file = repository.findActive(organizationId, id);
        if (file.isEmpty()) {
            return Optional.of(MISSING);
        }
        if (image && !file.get().view().contentType().startsWith("image/")) {
            return Optional.of(new Rejection("not_an_image", "referenced file is not an image"));
        }
        return Optional.empty();
    }

    // ---- API -----------------------------------------------------------------------------------------------

    /** Any active member may upload. */
    public StoredFileView upload(CurrentUser user, UUID organizationId, String name, String declaredType,
            InputStream content) {
        ActiveMembership membership = tenants.requireMembership(user.id(), organizationId);
        return store(membership.context(), name, declaredType, content);
    }

    public StoredFileView readable(CurrentUser user, UUID organizationId, UUID fileId) {
        ActiveMembership membership = tenants.requireMembership(user.id(), organizationId);
        FileRow file = repository.findActive(organizationId, fileId).orElseThrow(() -> ApiException.notFound("File"));
        if (membership.admin() || file.view().createdBy().equals(user.id())
                || referencedByReadableRecord(user, membership, fileId)) {
            return file.view();
        }
        throw ApiException.notFound("File");
    }

    public Download download(CurrentUser user, UUID organizationId, UUID fileId) {
        StoredFileView file = readable(user, organizationId, fileId);
        return new Download(file, open(organizationId, fileId));
    }

    /**
     * Only modules the caller can read are checked (from the role documents, so denied modules produce no audit
     * noise); hidden fields do not count, and the record scope is applied in SQL.
     */
    private boolean referencedByReadableRecord(CurrentUser user, ActiveMembership membership, UUID fileId) {
        AccessSummary summary = access.describe(user, membership.organizationId());
        for (ModuleSchema module : schemas.listActive(membership.organizationId())) {
            List<FieldSchema> fileFields = module.fields().stream().filter(FieldSchema::visible)
                    .filter(field -> field.type() == FieldType.FILE_REFERENCE
                            || field.type() == FieldType.IMAGE_REFERENCE)
                    .toList();
            if (fileFields.isEmpty() || !summary.modules().containsKey(module.apiKey())) {
                continue;
            }
            ModuleGrant grant;
            try {
                grant = access.require(membership.context(), module.apiKey(), RecordAction.READ);
            } catch (ApiException ex) {
                if (ex.status().is5xxServerError()) {
                    throw ex;
                }
                continue;
            }
            List<ObjectNode> references = fileFields.stream()
                    .filter(field -> !grant.fields().hidden(field.apiKey()))
                    .map(field -> reference(field.apiKey(), fileId))
                    .toList();
            if (records.anyActiveContaining(grant, references)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode reference(String fieldKey, UUID fileId) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.putObject(fieldKey).put("fileId", fileId.toString());
        return document;
    }

    private long spool(InputStream content, Path target, MessageDigest digest) throws IOException {
        long limit = properties.maxSize().toBytes();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = java.nio.file.Files.newOutputStream(target)) {
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                            "Files must not exceed " + properties.maxSize().toMegabytes() + " MB");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /** The last path segment without control characters, at most 255 characters; never used for storage. */
    static String displayName(String name) {
        String base = name == null ? "" : name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        base.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .limit(MAX_NAME_LENGTH).forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? "file" : result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void removeQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not remove unregistered object {}: {}", key, ex.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not delete temporary upload {}: {}", path, ex.getMessage());
        }
    }
}
