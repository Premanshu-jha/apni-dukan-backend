package in.apnidukan.common.context;

import in.apnidukan.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * DEVELOPMENT ONLY stand-in for sign-in: reads X-Dev-Shop-Id and X-Dev-User-Id headers.
 * Works only when app.dev-auth.enabled=true (DEV_AUTH_ENABLED in .env); otherwise every call is refused.
 * Replaced by real JWT sign-in in milestone 2.
 */
@Component
public class DevHeaderCurrentUserProvider implements CurrentUserProvider {

    static final String SHOP_HEADER = "X-Dev-Shop-Id";
    static final String USER_HEADER = "X-Dev-User-Id";

    private final boolean enabled;

    public DevHeaderCurrentUserProvider(@org.springframework.beans.factory.annotation.Value("${app.dev-auth.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public CurrentUser get() {
        if (!enabled) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Sign-in is not available yet");
        }
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        return new CurrentUser(readUuid(request, USER_HEADER), readUuid(request, SHOP_HEADER));
    }

    private static UUID readUuid(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", "Missing header " + header);
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_SIGNED_IN", header + " must be a UUID");
        }
    }
}
