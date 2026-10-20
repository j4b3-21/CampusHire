package com.bm4a.platform.modules.dataexchange.internal;

/** {@code CREATE} adds every row; {@code UPSERT} patches the record matched by a unique field, else creates. */
public enum ImportMode {
    CREATE, UPSERT
}
