package com.bm4a.platform.modules.relationships.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.relationships.RelationshipDefinitions;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.Cardinality;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.OnDelete;
import com.bm4a.platform.modules.relationships.internal.RelationshipService.RelationshipInput;
import com.bm4a.platform.modules.relationships.internal.RelationshipService.RelationshipView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

@Service
class RelationshipDefinitionsService implements RelationshipDefinitions {

    private final RelationshipService relationships;

    RelationshipDefinitionsService(RelationshipService relationships) {
        this.relationships = relationships;
    }

    @Override
    public Optional<RelationshipDefinition> find(UUID organizationId, String apiKey) {
        return relationships.find(organizationId, apiKey).map(RelationshipDefinitionsService::definition);
    }

    @Override
    public RelationshipDefinition create(UUID organizationId, UUID actorUserId, RelationshipDefinition d) {
        List<FieldViolation> violations = new ArrayList<>();
        Cardinality cardinality = parse(Cardinality.class, d.cardinality(), "cardinality", violations);
        OnDelete onDelete = d.onDelete() == null ? null : parse(OnDelete.class, d.onDelete(), "onDelete", violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return definition(relationships.create(organizationId, actorUserId, new RelationshipInput(d.apiKey(),
                d.label(), d.inverseLabel(), d.sourceModule(), d.targetModule(), cardinality, onDelete)));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field,
            List<FieldViolation> violations) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        if (value != null) {
            violations.add(new FieldViolation(field, "invalid", "unsupported value '" + value + "'"));
        }
        return null;
    }

    private static RelationshipDefinition definition(RelationshipView view) {
        RelationshipRow row = view.relationship();
        return new RelationshipDefinition(row.apiKey(), row.label(), row.inverseLabel(), view.sourceModule(),
                view.targetModule(), row.cardinality().name(), row.onDelete().name());
    }
}
