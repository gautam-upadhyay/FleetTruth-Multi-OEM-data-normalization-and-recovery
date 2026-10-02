package io.fleettruth.api;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(
      Map.of("error", e.getReason() == null ? "Request failed" : e.getReason())
    );
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
  })
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of(
        "error",
        "Invalid request. Check required fields, types, and permitted values."
      )
    );
  }

  @ExceptionHandler(
    org.springframework.security.access.AccessDeniedException.class
  )
  ResponseEntity<?> forbidden() {
    return ResponseEntity.status(403).body(
      Map.of("error", "Your role does not permit this action.")
    );
  }
}
