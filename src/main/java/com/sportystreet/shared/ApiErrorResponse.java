package com.sportystreet.shared;

import java.util.List;
import java.util.UUID;

public record ApiErrorResponse(
    String code, String message, List<FieldError> fieldErrors, String traceId) {
  public record FieldError(String field, String message) {}

  public static ApiErrorResponse of(String code, String message) {
    return new ApiErrorResponse(code, message, List.of(), UUID.randomUUID().toString());
  }
}
