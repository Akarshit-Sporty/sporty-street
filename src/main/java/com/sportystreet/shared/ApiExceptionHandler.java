package com.sportystreet.shared;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> domain(ApiException e) {
    return ResponseEntity.status(e.status()).body(ApiErrorResponse.of(e.code(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException e) {
    var fields =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "VALIDATION_FAILED", "Invalid request", fields, UUID.randomUUID().toString()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestValueException.class
  })
  ResponseEntity<ApiErrorResponse> malformed(Exception e) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("VALIDATION_FAILED", "Malformed or missing request value"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> unexpected(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse frameworkError) {
      int status = frameworkError.getStatusCode().value();
      String code = status == 404 ? "NOT_FOUND" : "VALIDATION_FAILED";
      return ResponseEntity.status(status)
          .body(ApiErrorResponse.of(code, "Request cannot be handled"));
    }
    var error = ApiErrorResponse.of("INTERNAL_ERROR", "Unexpected server error");
    LoggerFactory.getLogger(getClass()).error("Request failed; traceId={}", error.traceId(), e);
    return ResponseEntity.internalServerError().body(error);
  }
}
