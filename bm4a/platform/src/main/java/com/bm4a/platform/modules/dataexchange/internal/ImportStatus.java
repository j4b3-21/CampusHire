package com.bm4a.platform.modules.dataexchange.internal;

public enum ImportStatus {

    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
