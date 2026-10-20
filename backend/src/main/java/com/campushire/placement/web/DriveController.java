package com.campushire.placement.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.web.Downloads;
import com.bm4a.platform.modules.shared.web.ETags;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.placement.internal.ApplicantService;
import com.campushire.placement.internal.ApplicantService.Applicant;
import com.campushire.placement.internal.ApplicantService.ResumeDownload;
import com.campushire.placement.internal.ApplicationService;
import com.campushire.placement.internal.ApplicationService.ApplicationView;
import com.campushire.placement.internal.ApplicationService.ApplyResult;
import com.campushire.placement.internal.DriveLifecycleService;
import com.campushire.placement.internal.DriveLifecycleService.Action;
import com.campushire.placement.internal.DriveLifecycleService.DriveState;
import com.campushire.placement.internal.EligibilityService;
import com.campushire.placement.internal.EligibilityService.Preview;
import com.campushire.placement.internal.PipelineService;
import com.campushire.placement.internal.PipelineService.BulkResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
class DriveController {

    private static final String DRIVE = "/api/v1/orgs/{orgId}/campushire/drives/{driveId}";

    private final EligibilityService eligibility;
    private final DriveLifecycleService lifecycle;
    private final ApplicationService applications;
    private final ApplicantService applicants;
    private final PipelineService pipeline;

    DriveController(EligibilityService eligibility, DriveLifecycleService lifecycle, ApplicationService applications,
            ApplicantService applicants, PipelineService pipeline) {
        this.eligibility = eligibility;
        this.lifecycle = lifecycle;
        this.applications = applications;
        this.applicants = applicants;
        this.pipeline = pipeline;
    }

    @GetMapping(DRIVE + "/eligibility/preview")
    Preview preview(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestParam(required = false) Integer sample) {
        return eligibility.preview(user, orgId, driveId, sample);
    }

    @PostMapping(DRIVE + "/publish")
    ResponseEntity<DriveState> publish(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return lifecycle(user, orgId, driveId, ifMatch, Action.PUBLISH);
    }

    @PostMapping(DRIVE + "/close")
    ResponseEntity<DriveState> close(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return lifecycle(user, orgId, driveId, ifMatch, Action.CLOSE);
    }

    @PostMapping(DRIVE + "/start")
    ResponseEntity<DriveState> start(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return lifecycle(user, orgId, driveId, ifMatch, Action.START);
    }

    @PostMapping(DRIVE + "/complete")
    ResponseEntity<DriveState> complete(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return lifecycle(user, orgId, driveId, ifMatch, Action.COMPLETE);
    }

    @PostMapping(DRIVE + "/cancel")
    ResponseEntity<DriveState> cancel(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return lifecycle(user, orgId, driveId, ifMatch, Action.CANCEL);
    }

    /** 201 for a new application, 200 with the existing one when the student had applied before. */
    @PostMapping(DRIVE + "/apply")
    ResponseEntity<ApplicationView> apply(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId) {
        ApplyResult result = applications.apply(user, orgId, driveId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ETags.of(result.application().version()))
                .body(result.application());
    }

    @PostMapping(DRIVE + "/withdraw")
    ApplicationView withdraw(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @Valid @RequestBody(required = false) WithdrawRequest request) {
        return applications.withdraw(user, orgId, driveId, request == null ? null : request.comment());
    }

    @GetMapping(DRIVE + "/applicants")
    PageResponse<Applicant> applicants(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return applicants.list(user, orgId, driveId, stage, q, sort, PageQuery.of(page, size));
    }

    @PostMapping(DRIVE + "/applicants/transition")
    BulkResult transition(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @Valid @RequestBody TransitionRequest request) {
        return pipeline.transition(user, orgId, driveId, request.applicationIds(), request.rollNumbers(),
                request.to(), request.comment());
    }

    @GetMapping(DRIVE + "/applicants/{applicationId}/resume")
    ResponseEntity<Resource> resume(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @PathVariable UUID applicationId) {
        ResumeDownload resume = applicants.resume(user, orgId, driveId, applicationId);
        return ResponseEntity.ok()
                .headers(Downloads.attachment(resume.file().name(),
                        MediaType.parseMediaType(resume.file().contentType()), resume.file().sizeBytes()))
                .body(new InputStreamResource(resume.content()));
    }

    /** Multipart: {@code file} (XLSX, XLS or CSV with a roll number column), {@code to}, optional {@code comment}. */
    @PostMapping(path = DRIVE + "/applicants/transition/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    BulkResult upload(CurrentUser user, @PathVariable UUID orgId, @PathVariable UUID driveId,
            @RequestPart("file") MultipartFile file, @RequestParam("to") String to,
            @RequestParam(value = "comment", required = false) String comment) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.badRequest("unreadable_file", "The uploaded file could not be read");
        }
        return pipeline.upload(user, orgId, driveId, content, to, comment);
    }

    private ResponseEntity<DriveState> lifecycle(CurrentUser user, UUID orgId, UUID driveId, String ifMatch,
            Action action) {
        Long expected = ifMatch == null || ifMatch.isBlank() ? null : ETags.requireVersion(ifMatch);
        DriveState state = lifecycle.change(user, orgId, driveId, expected, action);
        return ResponseEntity.ok().eTag(ETags.of(state.version())).body(state);
    }

    record WithdrawRequest(@Size(max = 2000) String comment) {
    }

    record TransitionRequest(List<UUID> applicationIds, List<String> rollNumbers, @NotBlank String to,
            @Size(max = 2000) String comment) {
    }
}
