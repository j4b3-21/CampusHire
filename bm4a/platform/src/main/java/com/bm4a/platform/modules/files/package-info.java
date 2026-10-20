/**
 * File storage (ADR-005): uploads with magic-byte type detection, an S3-compatible or filesystem object store behind
 * the internal {@code FileStorage} port, and downloads authorized for the uploader, organization admins and readers of
 * a record that references the file. Other modules store and read files through {@link Files}; the record engine
 * validates file references through this module's implementation of the records {@code FileReferenceValidator}.
 */
@ApplicationModule(id = "files", displayName = "Files",
        allowedDependencies = { "shared", "users", "tenancy", "metadata", "access", "records", "audit" })
package com.bm4a.platform.modules.files;

import org.springframework.modulith.ApplicationModule;
