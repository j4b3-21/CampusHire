package com.bm4a.platform.modules.metadata.internal;

import static com.bm4a.platform.support.Fixtures.EXAMPLE_ASSET;
import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.ModulePermission;
import com.bm4a.platform.modules.authorization.ModuleRelation;
import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

@IntegrationTest
class ModuleTupleReconcilerIntegrationTest {

    final MockMvc mvc;
    final MetadataRepository repository;
    final ApplicationEventPublisher events;
    final TransactionTemplate transactions;
    final AuthorizationService authorization;

    ModuleTupleReconcilerIntegrationTest(@Autowired MockMvc mvc, @Autowired MetadataRepository repository,
            @Autowired ApplicationEventPublisher events, @Autowired TransactionTemplate transactions,
            @Autowired AuthorizationService authorization) {
        this.mvc = mvc;
        this.repository = repository;
        this.events = events;
        this.transactions = transactions;
        this.authorization = authorization;
    }

    @Test
    void restoresMissingModuleTuples() throws Exception {
        TestApi api = new TestApi(mvc);
        Caller owner = api.newUser();
        Caller member = api.newUser();
        String org = createOrg(owner, "Legacy");
        createModule(owner, org, EXAMPLE_ASSET);
        addMember(owner, org, member, "MEMBER");
        UUID orgId = UUID.fromString(org);
        UUID moduleId = UUID.fromString(read(owner.get("/api/v1/orgs/{org}/modules/example_asset", org).andReturn(),
                "$.id"));
        UUID user = UUID.fromString(member.id());

        // Simulate a module created before module tuples existed.
        transactions.executeWithoutResult(status -> events.publishEvent(new RelationshipTuplesChanged(List.of(),
                List.of(RelationshipTuple.moduleOrganization(moduleId, orgId),
                        RelationshipTuple.moduleMembers(ModuleRelation.READER, moduleId, orgId),
                        RelationshipTuple.moduleMembers(ModuleRelation.WRITER, moduleId, orgId)))));
        assertThat(authorization.isAllowed(user, ModulePermission.READ, moduleId)).isFalse();

        new ModuleTupleReconciler(repository, events, transactions).run(null);
        assertThat(authorization.isAllowed(user, ModulePermission.WRITE, moduleId)).isTrue();
        assertThat(authorization.isAllowed(UUID.fromString(owner.id()), ModulePermission.WRITE, moduleId)).isTrue();
    }
}
