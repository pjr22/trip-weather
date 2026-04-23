package com.pjr22.tripweather.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

// Turns @Valid / @Validated failures into a JSON 400 body that the frontend's
// request() helper can surface to the user. Default Spring bodies lack field-level
// messages. Three exception types because Spring uses different ones depending on
// where the validation was attached:
//   - MethodArgumentNotValidException: @Valid on @RequestBody DTO
//   - HandlerMethodValidationException: @Validated on a controller, validating
//     @RequestParam / @PathVariable (Spring 6.1+ unified mechanism)
//   - ConstraintViolationException: legacy fallback for the same case
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleHandlerValidation(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> {
                            String name = result.getMethodParameter().getParameterName();
                            String defaultMessage = error.getDefaultMessage();
                            String paramLabel = name != null ? name : "parameter";
                            return defaultMessage == null
                                    ? paramLabel + " is invalid"
                                    : paramLabel + ": " + defaultMessage;
                        }))
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> {
                    // Property paths look like "method.param.field"; show only the leaf for readability.
                    String path = v.getPropertyPath().toString();
                    int dot = path.lastIndexOf('.');
                    String field = dot >= 0 ? path.substring(dot + 1) : path;
                    return field + ": " + v.getMessage();
                })
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message.isBlank() ? "Validation failed" : message);
        return ResponseEntity.badRequest().body(body);
    }

    private String formatFieldError(FieldError error) {
        String field = error.getField();
        String defaultMessage = error.getDefaultMessage();
        return defaultMessage == null ? field + " is invalid" : field + ": " + defaultMessage;
    }
}
