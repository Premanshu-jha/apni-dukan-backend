package in.apnidukan.common.error;

import java.util.Map;

/** The one error shape every endpoint returns: {"code": "...", "message": "...", "details": {...}}. */
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of());
    }
}
