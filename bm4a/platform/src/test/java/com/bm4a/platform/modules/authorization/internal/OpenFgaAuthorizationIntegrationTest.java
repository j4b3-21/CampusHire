package com.bm4a.platform.modules.authorization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.AuthorizationUnavailableException;
import com.bm4a.platform.modules.authorization.OrganizationRelation;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.support.IntegrationTest;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;

@IntegrationTest
class OpenFgaAuthorizationIntegrationTest {

    @Autowired
    AuthorizationService authorization;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    private final UUID org = UUID.randomUUID();

    @Test
    void ownerHoldsEveryPermission() {
        UUID owner = UUID.randomUUID();
        publish(RelationshipTuplesChanged.write(RelationshipTuple.organization(owner, OrganizationRelation.OWNER, org)));

        for (Permission permission : Permission.values()) {
            assertThat(authorization.isAllowed(owner, permission, org)).as(permission.name()).isTrue();
        }
    }

    @Test
    void memberCanUseRecordsButNotAdministrate() {
        UUID member = UUID.randomUUID();
        publish(RelationshipTuplesChanged.write(RelationshipTuple.organization(member, OrganizationRelation.MEMBER, org)));

        Set<Permission> allowed = EnumSet.of(Permission.ORGANIZATION_VIEW, Permission.RECORDS_READ,
                Permission.RECORDS_WRITE);
        for (Permission permission : Permission.values()) {
            assertThat(authorization.isAllowed(member, permission, org)).as(permission.name())
                    .isEqualTo(allowed.contains(permission));
        }
    }

    @Test
    void adminCannotArchiveOrganization() {
        UUID admin = UUID.randomUUID();
        publish(RelationshipTuplesChanged.write(RelationshipTuple.organization(admin, OrganizationRelation.ADMIN, org)));

        assertThat(authorization.isAllowed(admin, Permission.METADATA_MANAGE, org)).isTrue();
        assertThat(authorization.isAllowed(admin, Permission.MEMBERS_MANAGE, org)).isTrue();
        assertThat(authorization.isAllowed(admin, Permission.ORGANIZATION_ARCHIVE, org)).isFalse();
    }

    @Test
    void permissionsAreScopedToTheOrganization() {
        UUID owner = UUID.randomUUID();
        publish(RelationshipTuplesChanged.write(RelationshipTuple.organization(owner, OrganizationRelation.OWNER, org)));

        assertThat(authorization.isAllowed(owner, Permission.RECORDS_READ, UUID.randomUUID())).isFalse();
        assertThat(authorization.isAllowed(UUID.randomUUID(), Permission.RECORDS_READ, org)).isFalse();
    }

    @Test
    void deletingTupleRevokesAccessAndReplaysAreIdempotent() {
        UUID user = UUID.randomUUID();
        RelationshipTuple tuple = RelationshipTuple.organization(user, OrganizationRelation.MEMBER, org);
        publish(RelationshipTuplesChanged.write(tuple));
        publish(RelationshipTuplesChanged.write(tuple));
        assertThat(authorization.isAllowed(user, Permission.RECORDS_READ, org)).isTrue();

        publish(RelationshipTuplesChanged.delete(tuple));
        publish(RelationshipTuplesChanged.delete(tuple));
        assertThat(authorization.isAllowed(user, Permission.RECORDS_READ, org)).isFalse();
    }

    @Test
    void roleChangeReplacesRelation() {
        UUID user = UUID.randomUUID();
        RelationshipTuple member = RelationshipTuple.organization(user, OrganizationRelation.MEMBER, org);
        RelationshipTuple admin = RelationshipTuple.organization(user, OrganizationRelation.ADMIN, org);
        publish(RelationshipTuplesChanged.write(member));
        publish(RelationshipTuplesChanged.replace(member, admin));
        assertThat(authorization.isAllowed(user, Permission.METADATA_MANAGE, org)).isTrue();

        publish(RelationshipTuplesChanged.replace(admin, member));
        assertThat(authorization.isAllowed(user, Permission.METADATA_MANAGE, org)).isFalse();
        assertThat(authorization.isAllowed(user, Permission.RECORDS_READ, org)).isTrue();
    }

    @Test
    void tupleChangesGoThroughThePersistentPublicationRegistry() {
        UUID user = UUID.randomUUID();
        publish(RelationshipTuplesChanged.write(RelationshipTuple.organization(user, OrganizationRelation.MEMBER, org)));

        Long completed = jdbc.sql("""
                SELECT count(*) FROM event_publication
                 WHERE event_type = :type AND completion_date IS NOT NULL AND serialized_event LIKE :user
                """)
                .param("type", RelationshipTuplesChanged.class.getName())
                .param("user", "%" + user + "%")
                .query(Long.class).single();
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void unreachableOpenFgaFailsClosed() throws Exception {
        OpenFgaProperties properties = new OpenFgaProperties("http://127.0.0.1:1", null, "bm4a", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "01ARZ3NDEKTSV4RRFFQ69G5FAV", false, Duration.ofMillis(300), Duration.ofSeconds(30));
        OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.apiUrl())
                .storeId(properties.storeId()).authorizationModelId(properties.modelId())
                .connectTimeout(properties.requestTimeout()).readTimeout(properties.requestTimeout()).maxRetries(0));
        AuthorizationService unreachable = new OpenFgaAuthorizationService(client, properties);

        assertThatThrownBy(() -> unreachable.isAllowed(UUID.randomUUID(), Permission.RECORDS_READ, org))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }

    private void publish(RelationshipTuplesChanged event) {
        transactions.executeWithoutResult(status -> events.publishEvent(event));
    }
}
