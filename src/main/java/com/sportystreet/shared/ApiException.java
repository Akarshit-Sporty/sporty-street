package com.sportystreet.shared;

public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException rule(String code, String message) {
    return new ApiException(422, code, message);
  }

  public static ApiException notFound() {
    return new ApiException(404, "NOT_FOUND", "Resource not found");
  }
}
