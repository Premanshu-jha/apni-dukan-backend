package in.apnidukan.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The caller's real IP, for the per-network limits on sign-in codes. Behind a proxy the connection comes from the
 * proxy, so the IP is taken from the header the proxy sets (CLIENT_IP_HEADER):
 * <ul>
 *   <li>X-Forwarded-For (Railway): the LAST entry, the one Railway's edge added. Entries further left come from
 *       the caller and can be made up.</li>
 *   <li>Any other header (CF-Connecting-IP behind Cloudflare): its value as the proxy wrote it.</li>
 *   <li>Not set (your laptop, tests of other modules): the connection's own address.</li>
 * </ul>
 * Production refuses to start without it (ProductionSafetyCheck): otherwise every caller would share the proxy's
 * IP and one person could use up everyone's limit.
 */
@Component
public class ClientAddress {

    private final String header;

    public ClientAddress(@Value("${app.client-ip.header:}") String header) {
        this.header = header.strip();
    }

    public String of(HttpServletRequest request) {
        if (header.isEmpty()) {
            return request.getRemoteAddr();
        }
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return request.getRemoteAddr();
        }
        // A list ("a, b, c") keeps its last entry: the one added by the proxy nearest to us
        String[] parts = value.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].strip();
            if (!part.isEmpty()) {
                return part;
            }
        }
        return request.getRemoteAddr();
    }

    public boolean configured() {
        return !header.isEmpty();
    }
}
