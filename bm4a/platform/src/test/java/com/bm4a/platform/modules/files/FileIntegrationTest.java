package com.bm4a.platform.modules.files;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.Fixtures.uploadFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * Upload, type detection, download headers, the download authorization matrix (uploader, admins, scoped readers of
 * a referencing record, everyone else 404) and file reference validation on record writes (ADR-005).
 */
@IntegrationTest
class FileIntegrationTest {

    static final String FILES = "/api/v1/orgs/{org}/files";
    static final String FILE = FILES + "/{id}";
    static final String DOCS = "/api/v1/orgs/{org}/modules/applicant_doc/records";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final String MODULE = """
            {"apiKey": "applicant_doc", "labelSingular": "Applicant document", "labelPlural": "Applicant documents",
             "settings": {"accessMode": "ROLES"},
             "fields": [
               {"apiKey": "title", "label": "Title", "type": "TEXT", "required": true},
               {"apiKey": "resume", "label": "Resume", "type": "FILE_REFERENCE"},
               {"apiKey": "photo", "label": "Photo", "type": "IMAGE_REFERENCE"},
               {"apiKey": "notes_file", "label": "Notes", "type": "FILE_REFERENCE"}]}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordOperations records;

    @Autowired
    Files files;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Files");
        createModule(owner, org, MODULE);
        createRole(owner, org, """
                {"apiKey": "candidate", "label": "Candidate",
                 "permissions": {"modules": {"applicant_doc": {"read": "OWN"}}}}
                """);
        createRole(owner, org, """
                {"apiKey": "reviewer", "label": "Reviewer",
                 "permissions": {"modules": {"applicant_doc": {"read": "ALL", "hiddenFields": ["notes_file"]}}}}
                """);
    }

    @Test
    void uploadReturnsMetadataAndDownloadsAreAttachments() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Fixtures.PNG));
        MvcResult upload = owner.multipart(FILES, List.of(new MockMultipartFile("file", "C:\\photos\\pic.png",
                "image/png", Fixtures.PNG)), org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("pic.png"))
                .andReturn();
        String id = TestApi.read(upload, "$.id");
        assertThat(upload.getResponse().getHeader("Location")).isEqualTo("/api/v1/orgs/" + org + "/files/" + id);
        owner.get(FILE + "/meta", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("pic.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(Fixtures.PNG.length))
                .andExpect(jsonPath("$.sha256").value(sha256))
                .andExpect(jsonPath("$.createdBy").value(owner.id()))
                .andExpect(jsonPath("$.createdAt").exists());
        owner.get(FILE, org, id)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"pic.png\"; filename*=UTF-8''pic.png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().bytes(Fixtures.PNG));

        String unicode = uploadFile(owner, org, "résumé \"final\".pdf", "application/pdf", PDF);
        owner.get(FILE, org, unicode)
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"r_sum_ _final_.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20%22final%22.pdf"));
    }

    @Test
    void contentTypeIsSniffedAndNotTrusted() throws Exception {
        owner.multipart(FILES, List.of(new MockMultipartFile("file", "resume.pdf", "application/pdf", Fixtures.PNG)),
                org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.name").value("resume.pdf"));
        owner.multipart(FILES, List.of(new MockMultipartFile("file", "resume.pdf", "application/pdf",
                new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0 })), org)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_file_type"));
        owner.multipart(FILES, List.of(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0])), org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("empty_file"));
        owner.multipart(FILES, List.of(new MockMultipartFile("file", "big.csv", "text/csv",
                new byte[25 * 1024 * 1024 + 1])), org)
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("file_too_large"));
        api.newUser().multipart(FILES, List.of(new MockMultipartFile("file", "a.png", "image/png", Fixtures.PNG)), org)
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadsAreAllowedForUploaderAdminsAndScopedReadersOfAReferencingRecord() throws Exception {
        Caller admin = api.newUser();
        Caller candidate = api.newUser();
        Caller otherCandidate = api.newUser();
        Caller reviewer = api.newUser();
        Caller member = api.newUser();
        addMember(owner, org, admin, "ADMIN");
        addMemberWithRole(owner, org, candidate, "candidate", null);
        addMemberWithRole(owner, org, otherCandidate, "candidate", null);
        addMemberWithRole(owner, org, reviewer, "reviewer", null);
        addMember(owner, org, member, "MEMBER");

        String resume = uploadFile(owner, org, "resume.pdf", "application/pdf", PDF);
        String notes = uploadFile(owner, org, "notes.pdf", "application/pdf", PDF);
        String record = createRecord(owner, org, "applicant_doc", "{\"title\":\"Asha\",\"resume\":{\"fileId\":\""
                + resume + "\"},\"notes_file\":{\"fileId\":\"" + notes + "\"}}");
        records.transferOwner(ownerContext(), "applicant_doc", UUID.fromString(record), UUID.fromString(candidate.id()));

        owner.get(FILE, org, resume).andExpect(status().isOk());
        admin.get(FILE, org, resume).andExpect(status().isOk());
        candidate.get(FILE, org, resume).andExpect(status().isOk()).andExpect(content().bytes(PDF));
        candidate.get(FILE + "/meta", org, resume).andExpect(jsonPath("$.name").value("resume.pdf"));
        reviewer.get(FILE, org, resume).andExpect(status().isOk());
        otherCandidate.get(FILE, org, resume).andExpect(status().isNotFound());
        otherCandidate.get(FILE + "/meta", org, resume).andExpect(status().isNotFound());
        member.get(FILE, org, resume).andExpect(status().isNotFound());
        api.newUser().get(FILE, org, resume).andExpect(status().isNotFound());
        // The reviewer cannot see the field that references the notes, so the file stays hidden too.
        reviewer.get(FILE, org, notes).andExpect(status().isNotFound());
        candidate.get(FILE, org, notes).andExpect(status().isOk());
        owner.get(FILE, org, UUID.randomUUID()).andExpect(status().isNotFound());

        // Uploaders can always read their own files; nobody else gains access through that.
        String own = uploadFile(otherCandidate, org, "mine.png", "image/png", Fixtures.PNG);
        otherCandidate.get(FILE, org, own).andExpect(status().isOk());
        candidate.get(FILE, org, own).andExpect(status().isNotFound());

        // Only active records grant access.
        owner.delete(DOCS + "/{id}", "\"2\"", org, record).andExpect(status().isOk());
        candidate.get(FILE, org, resume).andExpect(status().isNotFound());
        reviewer.get(FILE, org, resume).andExpect(status().isNotFound());
        admin.get(FILE, org, resume).andExpect(status().isOk());

        // Files of another organization are invisible.
        Caller stranger = api.newUser();
        String otherOrg = createOrg(stranger, "Other");
        stranger.get(FILE, otherOrg, resume).andExpect(status().isNotFound());
    }

    @Test
    void recordWritesValidateFileReferences() throws Exception {
        String pdf = uploadFile(owner, org, "resume.pdf", "application/pdf", PDF);
        String png = uploadFile(owner, org, "photo.png", "image/png", Fixtures.PNG);
        Caller stranger = api.newUser();
        String foreign = uploadFile(stranger, createOrg(stranger, "Foreign"), "x.pdf", "application/pdf", PDF);

        owner.post(DOCS, "{\"data\":{\"title\":\"A\",\"resume\":{\"fileId\":\"" + UUID.randomUUID() + "\"}}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("data.resume"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));
        owner.post(DOCS, "{\"data\":{\"title\":\"A\",\"resume\":{\"fileId\":\"files/1.pdf\"}}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));
        owner.post(DOCS, "{\"data\":{\"title\":\"A\",\"resume\":{\"fileId\":\"" + foreign + "\"}}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("invalid_reference"));
        owner.post(DOCS, "{\"data\":{\"title\":\"A\",\"photo\":{\"fileId\":\"" + pdf + "\"}}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("data.photo"))
                .andExpect(jsonPath("$.errors[0].code").value("not_an_image"));
        String id = createRecord(owner, org, "applicant_doc", "{\"title\":\"A\",\"resume\":{\"fileId\":\"" + pdf
                + "\",\"name\":\"resume.pdf\"},\"photo\":{\"fileId\":\"" + png + "\"}}");
        owner.patch(DOCS + "/{id}", "\"1\"", "{\"data\":{\"photo\":{\"fileId\":\"" + pdf + "\"}}}", org, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("not_an_image"));
        owner.patch(DOCS + "/{id}", "\"1\"", "{\"data\":{\"title\":\"B\"}}", org, id).andExpect(status().isOk());
    }

    @Test
    void publishedApiStoresFindsAndOpensFiles() throws Exception {
        StoredFileView stored = files.store(ownerContext(), "../../etc/passwd.csv", "text/csv",
                "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.name()).isEqualTo("passwd.csv");
        assertThat(stored.contentType()).isEqualTo(ContentSniffer.CSV);
        assertThat(files.find(UUID.fromString(org), stored.id())).contains(stored);
        assertThat(files.find(UUID.randomUUID(), stored.id())).isEmpty();
        try (InputStream content = files.open(UUID.fromString(org), stored.id())) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("a,b\n1,2\n");
        }
    }

    private TenantContext ownerContext() throws Exception {
        return new TenantContext(UUID.fromString(org), UUID.fromString(owner.id()), MembershipRole.OWNER);
    }
}
