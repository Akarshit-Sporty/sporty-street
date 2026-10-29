package com.sportystreet.shared;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long total) {
  public static <T> PageResponse<T> of(List<T> all, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(400, "VALIDATION_FAILED", "page >= 0 and size 1–100 required");
    int start = (int) Math.min((long) page * size, all.size());
    return new PageResponse<>(
        all.subList(start, Math.min(start + size, all.size())), page, size, all.size());
  }
}
