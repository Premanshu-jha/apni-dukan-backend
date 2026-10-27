package in.apnidukan.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The one error shape every endpoint returns: {"code": "...", "message": "...", "details": {...}}. */
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    /** Keeps its own read-only copy of the details (in their original order). */
    public ErrorResponse {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    @Override
    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of());
    }
}
