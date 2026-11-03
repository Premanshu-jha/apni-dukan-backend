package in.apnidukan.notifications.service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which device addresses the server will send to. The address comes from the app, so without this check anyone
 * could make our server send requests to any address they like (to reach internal services, for example).
 * Only the browsers' real push services are allowed, over https on the default port.
 */
final class PushEndpoints {

    /** Chrome, Edge (Chromium), Opera, Samsung Internet use Google's; Firefox uses Mozilla's; Safari uses Apple's. */
    private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
            "web.push.apple.com");
    /** Legacy Edge and Windows use per-region hosts under this domain. */
    private static final String WINDOWS_SUFFIX = ".notify.windows.com";

    private PushEndpoints() {
    }

    static boolean allowed(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(uri.getScheme())
                && uri.getPort() == -1
                && uri.getUserInfo() == null
                && (HOSTS.contains(host) || host.endsWith(WINDOWS_SUFFIX));
    }

    /** scheme://host, what VAPID's "aud" claim must be. */
    static String origin(String endpoint) {
        URI uri = URI.create(endpoint);
        return uri.getScheme() + "://" + uri.getHost();
    }
}
