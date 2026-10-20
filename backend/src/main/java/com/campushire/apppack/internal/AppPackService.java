package com.campushire.apppack.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.packs.InstalledPack;
import com.bm4a.platform.modules.packs.PackInstallReport;
import com.bm4a.platform.modules.packs.PackInstaller;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.tenancy.MembershipAdministration;
import com.bm4a.platform.modules.tenancy.MembershipAdministration.MembershipView;
import com.bm4a.platform.modules.tenancy.OrganizationSettings;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.apppack.CampusHirePack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Setup of an organization: pack install, allowed e-mail domains and the installing admin's {@code tpo} org role, in
 * one transaction. Re-running it is a no-op apart from what changed.
 */
@Service
public class AppPackService implements CampusHirePack {

    static final String PACK_RESOURCE = "campushire/campushire-pack.json";
    static final String TPO_ROLE = "tpo";
    static final int MAX_DOMAINS = 20;
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private final PackInstaller installer;
    private final TenantAccess tenants;
    private final OrganizationSettings settings;
    private final MembershipAdministration memberships;
    private final JsonNode pack;
    private final TransactionTemplate transactions;

    AppPackService(PackInstaller installer, TenantAccess tenants, OrganizationSettings settings,
            MembershipAdministration memberships, JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager) {
        this.installer = installer;
        this.tenants = tenants;
        this.settings = settings;
        this.memberships = memberships;
        this.transactions = new TransactionTemplate(transactionManager);
        try (InputStream in = new ClassPathResource(PACK_RESOURCE).getInputStream()) {
            this.pack = jsonMapper.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("CampusHire pack " + PACK_RESOURCE + " is missing", ex);
        }
    }

    public record SetupResult(boolean installed, PackInstallReport report, String roleKey,
            List<String> allowedEmailDomains) {
    }

    public record SetupStatus(boolean installed, Integer version, Instant installedAt, UUID installedBy,
            int availableVersion, boolean upgradeAvailable, List<String> allowedEmailDomains) {
    }

    /**
     * Requires {@code METADATA_MANAGE} (owners and admins); checked before the transaction so that a refusal is
     * audited. {@code allowedEmailDomains} are added to the
     * organization settings when none are configured yet; a different, already configured list is changed through
     * the organization settings ({@code PATCH /orgs/{orgId}}), so that setup never silently drops a domain.
     */
    public SetupResult setup(CurrentUser user, UUID organizationId, List<String> allowedEmailDomains) {
        TenantContext tenant = tenants.require(user, organizationId, Permission.METADATA_MANAGE);
        List<String> domains = normalizeDomains(allowedEmailDomains);
        return transactions.execute(status -> {
            if (!domains.isEmpty()) {
                storeDomains(tenant, domains);
            }
            PackInstallReport report = installer.install(organizationId, user.id(), pack);
            MembershipView membership = memberships.find(organizationId, user.id())
                    .filter(MembershipView::active)
                    .orElseThrow(() -> ApiException.notFound("Organization"));
            if (!TPO_ROLE.equals(membership.roleKey())) {
                memberships.grant(organizationId, user.id(), membership.role(), TPO_ROLE, membership.attributes(),
                        user.id());
            }
            return new SetupResult(true, report, TPO_ROLE, configuredDomains(organizationId));
        });
    }

    public SetupStatus status(CurrentUser user, UUID organizationId) {
        tenants.requireMembership(user.id(), organizationId);
        Optional<Installation> installation = installation(organizationId);
        int available = availableVersion();
        return new SetupStatus(installation.isPresent(), installation.map(Installation::version).orElse(null),
                installation.map(Installation::installedAt).orElse(null),
                installation.map(Installation::installedBy).orElse(null), available,
                installation.map(installed -> installed.version() < available).orElse(true),
                configuredDomains(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Installation> installation(UUID organizationId) {
        return installer.installed(organizationId).stream()
                .filter(installed -> KEY.equals(installed.packKey()))
                .findFirst()
                .map(AppPackService::installation);
    }

    @Override
    public int availableVersion() {
        return pack.path("version").asInt();
    }

    private void storeDomains(TenantContext tenant, List<String> domains) {
        JsonNode existing = settings.get(tenant.organizationId()).path("campushire").path("allowedEmailDomains");
        if (existing.isMissingNode() || existing.isNull() || (existing.isArray() && existing.isEmpty())) {
            ArrayNode values = JsonNodeFactory.instance.arrayNode();
            domains.forEach(values::add);
            settings.put(tenant.organizationId(), tenant.userId(), List.of("campushire", "allowedEmailDomains"),
                    values);
            return;
        }
        Set<String> configured = new LinkedHashSet<>();
        existing.values().forEach(value -> configured.add(value.asString().toLowerCase(Locale.ROOT)));
        if (!configured.containsAll(domains)) {
            throw ApiException.conflict("allowed_domains_configured",
                    "Allowed e-mail domains are already configured (" + String.join(", ", configured)
                            + "); change them in the organization settings");
        }
    }

    private List<String> configuredDomains(UUID organizationId) {
        JsonNode existing = settings.get(organizationId).path("campushire").path("allowedEmailDomains");
        List<String> domains = new ArrayList<>();
        if (existing.isArray()) {
            existing.values().forEach(value -> {
                if (value.isString()) {
                    domains.add(value.stringValue());
                }
            });
        }
        return domains;
    }

    static List<String> normalizeDomains(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (input.size() > MAX_DOMAINS) {
            violations.add(new FieldViolation("allowedEmailDomains", "size",
                    "at most " + MAX_DOMAINS + " domains are allowed"));
        }
        Set<String> domains = new LinkedHashSet<>();
        for (int i = 0; i < input.size(); i++) {
            String raw = input.get(i);
            String domain = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (domain.startsWith("@")) {
                domain = domain.substring(1);
            }
            if (!DOMAIN.matcher(domain).matches()) {
                violations.add(new FieldViolation("allowedEmailDomains[" + i + "]", "format",
                        "'" + raw + "' is not a domain name such as iiitt.ac.in"));
            } else {
                domains.add(domain);
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return List.copyOf(domains);
    }

    private static Installation installation(InstalledPack installed) {
        return new Installation(installed.version(), installed.installedAt(), installed.installedBy());
    }
}
