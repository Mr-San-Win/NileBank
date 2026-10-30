package com.vaultbank.common;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ExceptionHandlerAdvice {
  private static final Logger log = LoggerFactory.getLogger(ExceptionHandlerAdvice.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> business(ApiException e) {
    return ResponseEntity.status(e.status()).body(ApiError.of(e.status().value(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    Map<String, String> fields = new TreeMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(new ApiError(400, "Please check your input", fields, Instant.now()));
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiError> auth() {
    return ResponseEntity.status(401).body(ApiError.of(401, "Invalid email or password"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> conflict() {
    return ResponseEntity.status(409)
        .body(ApiError.of(409, "The operation conflicts with existing data"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class,
    org.springframework.web.bind.ServletRequestBindingException.class
  })
  ResponseEntity<ApiError> malformed() {
    return ResponseEntity.badRequest().body(ApiError.of(400, "Invalid request"));
  }

  @ExceptionHandler(PessimisticLockingFailureException.class)
  ResponseEntity<ApiError> busy() {
    return ResponseEntity.status(409)
        .body(ApiError.of(409, "Account busy; please retry the operation"));
  }

  @ExceptionHandler(org.springframework.web.ErrorResponseException.class)
  ResponseEntity<ApiError> framework(org.springframework.web.ErrorResponseException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(ApiError.of(e.getStatusCode().value(), "Request could not be processed"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> methodNotAllowed(
      org.springframework.web.HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(405)
        .headers(e.getHeaders())
        .body(ApiError.of(405, "Method not allowed"));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> unsupportedMedia() {
    return ResponseEntity.status(415).body(ApiError.of(415, "Use application/json"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ApiError> notFound() {
    return ResponseEntity.status(404).body(ApiError.of(404, "Resource not found"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e) {
    log.error("Unexpected request failure", e);
    return ResponseEntity.internalServerError()
        .body(ApiError.of(500, "An unexpected error occurred"));
  }
}
