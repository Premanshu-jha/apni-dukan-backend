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
 * A value that isn't an IP address (a misconfigured proxy, or a caller writing the header itself where no proxy
 * replaces it) is ignored: the connection's own address is used, so junk can't become a key of its own.
 *
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
                return isIp(part) ? part : request.getRemoteAddr();
            }
        }
        return request.getRemoteAddr();
    }

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("(" + OCTET + "\\.){3}" + OCTET);
    private static final java.util.regex.Pattern IPV6 = java.util.regex.Pattern.compile("\\[?[0-9a-fA-F:.]{2,45}]?");

    /** An IPv4 or IPv6 literal (checked by its characters first, so no name is ever looked up). */
    static boolean isIp(String value) {
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (value.indexOf(':') < 0 || !IPV6.matcher(value).matches()) {
            return false;
        }
        String bare = value.startsWith("[") ? value.substring(1, value.length() - 1) : value;
        try {
            // Only hex digits, colons and dots got here, so this parses and never looks a name up. An IPv4-mapped
            // address (::ffff:1.2.3.4) comes back as IPv4: still a valid client address
            java.net.InetAddress.getByName(bare);
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    public boolean configured() {
        return !header.isEmpty();
    }
}
