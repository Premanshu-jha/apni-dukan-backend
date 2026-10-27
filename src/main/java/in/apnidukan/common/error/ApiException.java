package in.apnidukan.common.error;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A business error with a stable code the app can react to, e.g. FILE_TOO_LARGE.
 * Thrown from services; turned into the standard error JSON by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;
    private Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        // Own copy, so nobody can change an exception's details after it was thrown
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return Collections.unmodifiableMap(details); }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }

    /** Tells the app how long to wait before trying again (sent as the Retry-After header). */
    public ApiException retryAfter(long seconds) {
        this.retryAfterSeconds = Math.max(1, seconds);
        return this;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
