package com.bm4a.platform.modules.records;

import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.ModuleSchema;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Helpers for record payloads. */
public final class RecordData {

    private RecordData() {
    }

    /** The part of a stored payload that API clients may see: values of non-archived fields of the module. */
    public static ObjectNode visible(ModuleSchema module, ObjectNode data) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (FieldSchema field : module.fields()) {
            if (field.visible() && data.has(field.apiKey())) {
                result.set(field.apiKey(), data.get(field.apiKey()));
            }
        }
        return result;
    }
}
