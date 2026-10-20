package com.bm4a.platform.modules.packs;

import java.time.Instant;
import java.util.UUID;

/** A pack installed into an organization, with the version and document hash of the last install or upgrade. */
public record InstalledPack(String packKey, int version, String label, String definitionSha256, Instant installedAt,
        UUID installedBy) {
}
