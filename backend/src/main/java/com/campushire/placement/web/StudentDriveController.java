package com.campushire.placement.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.placement.internal.StudentDriveService;
import com.campushire.placement.internal.StudentDriveService.StudentDrive;

@RestController
class StudentDriveController {

    private final StudentDriveService drives;

    StudentDriveController(StudentDriveService drives) {
        this.drives = drives;
    }

    /** {@code filter}: {@code open} (default), {@code upcoming} or {@code all}. */
    @GetMapping("/api/v1/orgs/{orgId}/campushire/student/drives")
    PageResponse<StudentDrive> list(CurrentUser user, @PathVariable UUID orgId,
            @RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return drives.list(user, orgId, filter, PageQuery.of(page, size));
    }
}
