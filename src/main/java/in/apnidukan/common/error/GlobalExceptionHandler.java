package in.apnidukan.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "Some fields are not valid", fields));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "Missing '" + e.getParameterName() + "'"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "Missing '" + e.getRequestPartName() + "'"));
    }

    /** A value of the wrong kind, e.g. purpose=INVOICE or a broken id. Says which values are allowed. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        Class<?> type = e.getRequiredType();
        if (type != null && type.isEnum()) {
            details.put("allowed", Arrays.stream(type.getEnumConstants()).map(Object::toString).toList());
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST",
                "'" + e.getValue() + "' is not a valid value for '" + e.getName() + "'", details));
    }

    /** The request body isn't valid JSON (or has a value of the wrong type). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof in.apnidukan.common.web.RequestSizeLimitFilter.RequestTooLargeException) {
                return ResponseEntity.status(413)
                        .body(ErrorResponse.of("REQUEST_TOO_LARGE", "This request is too large"));
            }
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "The request body is not valid JSON"));
    }

    /** Validation on path or query values (e.g. @Positive on a @RequestParam). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "Some values are not valid", fields));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of("FILE_TOO_LARGE", "Files can be at most 10 MB"));
    }

    /**
     * Spring's own web errors (unknown URL, wrong HTTP method, wrong content type, ...) carry their proper status.
     * Keep it (404, 405, 415...) instead of turning them into 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse web) {
            HttpStatus status = HttpStatus.resolve(web.getStatusCode().value());
            String code = status != null ? status.name() : "HTTP_" + web.getStatusCode().value();
            String message = web.getBody().getDetail() != null ? web.getBody().getDetail() : code;
            return ResponseEntity.status(web.getStatusCode()).body(ErrorResponse.of(code, message));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Something went wrong. Please try again."));
    }
}
