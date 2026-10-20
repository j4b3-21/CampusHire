package com.bm4a.platform.modules.access;

/** Record operations governed by module permissions. {@code READ} needs {@code can_read}, the others {@code can_write}. */
public enum RecordAction {
    READ, CREATE, UPDATE, DELETE
}
