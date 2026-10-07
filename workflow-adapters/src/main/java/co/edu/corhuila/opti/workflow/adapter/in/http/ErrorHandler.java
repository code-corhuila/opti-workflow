package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import co.edu.corhuila.opti.workflow.adapter.Correlation;
import co.edu.corhuila.opti.workflow.domain.model.DomainException;

/**
 * The one place where errors become responses: domain kind to status, framework failures to
 * the common envelope, and everything unexpected to a neutral 500 (details only in the log).
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e) {
        ErrorCode code = switch (e.kind()) {
            case VALIDATION -> ErrorCode.VALIDATION_ERROR;
            case UNAUTHENTICATED -> ErrorCode.UNAUTHORIZED;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_STATUS_TRANSITION -> ErrorCode.INVALID_STATUS_TRANSITION;
            case BUSINESS_RULE_VIOLATION -> ErrorCode.BUSINESS_RULE_VIOLATION;
        };
        List<ApiError.Detail> details = e.fields().isEmpty() ? null
                : e.fields().stream().map(f -> new ApiError.Detail(f.field(), f.message())).toList();
        return respond(code, e.getMessage(), details);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return respond(e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.Detail(fe.getField(), fe.getDefaultMessage()))
                .distinct().toList();
        return respond(ErrorCode.VALIDATION_ERROR, "the request has invalid fields", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson(HttpMessageNotReadableException e) {
        LOG.debug("unreadable body", e);
        if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = fieldPath(mapping);
            String reason = mapping instanceof UnrecognizedPropertyException ? "unknown field"
                    : "has an invalid value or type";
            return respond(ErrorCode.VALIDATION_ERROR, "the request has invalid fields",
                    List.of(new ApiError.Detail(field, reason)));
        }
        return respond(ErrorCode.VALIDATION_ERROR, "the request body is not valid JSON",
                List.of(new ApiError.Detail("body", "malformed JSON")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "the request has invalid fields",
                List.of(new ApiError.Detail(e.getHeaderName(), "header required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "the request has invalid fields",
                List.of(new ApiError.Detail(e.getName(), "has an invalid format")));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ApiError> unknownRoute(Exception e) {
        return respond(ErrorCode.NOT_FOUND, "the requested resource does not exist", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        LOG.error("unexpected failure", e);
        return respond(ErrorCode.INTERNAL_ERROR, "an unexpected error occurred", null);
    }

    /** {@code items[0].quantity}: field names joined by dots, list positions in brackets. */
    private static String fieldPath(JsonMappingException mapping) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : mapping.getPath()) {
            if (ref.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(ref.getFieldName());
            } else {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, String message, List<ApiError.Detail> details) {
        return ResponseEntity.status(code.status())
                .body(new ApiError(code.name(), message, details, Correlation.current()));
    }
}
