package com.bm4a.platform.modules.access.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.tenancy.OrgRoleCatalog;

@Service
class JdbcOrgRoleCatalog implements OrgRoleCatalog {

    private final RoleRepository repository;

    JdbcOrgRoleCatalog(RoleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Optional<OrgRoleRef> findForAssignment(UUID organizationId, String roleKey) {
        return repository.findForShare(organizationId, roleKey).map(JdbcOrgRoleCatalog::ref);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrgRoleRef> findAll(UUID organizationId, Collection<UUID> roleIds) {
        return repository.findAll(organizationId, roleIds).stream()
                .collect(Collectors.toMap(RoleRow::id, JdbcOrgRoleCatalog::ref));
    }

    private static OrgRoleRef ref(RoleRow row) {
        return new OrgRoleRef(row.id(), row.apiKey(), row.label());
    }
}
