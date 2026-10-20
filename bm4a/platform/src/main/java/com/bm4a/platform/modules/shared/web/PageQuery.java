package com.bm4a.platform.modules.shared.web;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Validated paging parameters. Page numbers start at 0; page size is capped at {@link #MAX_SIZE}.
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0) {
            throw ApiException.validation("page", "min", "page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw ApiException.validation("size", "range", "size must be between 1 and " + MAX_SIZE);
        }
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) page * size;
    }
}
