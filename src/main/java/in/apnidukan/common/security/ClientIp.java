package in.apnidukan.common.security;

import java.util.HexFormat;
import java.util.Locale;

/**
 * Turns a caller's IP into the key used for per-network limits.
 * IPv4: the address itself. IPv6: its /64 prefix, because one phone or home router gets a whole /64 and can
 * pick a new address in it for every request; counting full IPv6 addresses would make the limit useless.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String limitKey(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        byte[] bytes = parseIpv6(ip);
        return bytes == null ? ip.toLowerCase(Locale.ROOT) : "v6:" + HexFormat.of().formatHex(bytes, 0, 8);
    }

    /** Parses an IPv6 literal; null if it isn't one. Only hex digits, ':' and '.' get here, so no DNS lookup. */
    private static byte[] parseIpv6(String ip) {
        String s = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (!s.matches("[0-9a-fA-F:.]{2,45}")) {
            return null;
        }
        try {
            byte[] b = java.net.InetAddress.getByName(s).getAddress();
            return b.length == 16 ? b : null;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
