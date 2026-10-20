package com.bm4a.platform.modules.metadata.internal;

import java.util.List;

/** A module definition with all of its fields. */
public record ModuleView(ModuleRow module, List<FieldRow> fields) {

    public ModuleView {
        fields = List.copyOf(fields);
    }
}
