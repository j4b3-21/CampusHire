package com.bm4a.platform.modules.shared.web;

import java.util.List;
import java.util.function.Function;

/**
 * Offset-based page of results.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalElements) {
        int totalPages = (int) ((totalElements + query.size() - 1) / query.size());
        return new PageResponse<>(List.copyOf(items), query.page(), query.size(), totalElements, totalPages);
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
