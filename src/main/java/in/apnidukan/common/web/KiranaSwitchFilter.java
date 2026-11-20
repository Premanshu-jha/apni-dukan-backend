package in.apnidukan.common.web;

import in.apnidukan.common.config.KiranaSwitch;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * While kirana is switched off ({@link KiranaSwitch}), every request under /api/v1/kirana, /api/v1/admin/kirana and
 * /api/v1/payments/razorpayx answers 404 KIRANA_OFF in the usual error shape, whoever asks. It sits in the security
 * filter chain right after CORS (so a browser can read the answer) and before the sign-in token is looked at, so the
 * answer is the same signed in or not, an admin or not: it never tells anyone more about these paths. Not a
 * {@code @Component}: SecurityConfig puts it in the chain (a component would also run a second time as a servlet
 * filter). On: it does nothing.
 */
public class KiranaSwitchFilter extends OncePerRequestFilter {

    /** The kirana API: these paths and everything under them. */
    static final List<String> PREFIXES = List.of("/api/v1/kirana", "/api/v1/admin/kirana",
            "/api/v1/payments/razorpayx");

    private static final String BODY = "{\"code\":\"" + KiranaSwitch.CODE + "\",\"message\":\"" + KiranaSwitch.MESSAGE
            + "\",\"details\":{}}";

    private final KiranaSwitch kirana;

    public KiranaSwitchFilter(KiranaSwitch kirana) {
        this.kirana = kirana;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return kirana.on();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (!kiranaPath(uri)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }

    /**
     * Whether this path (without the context path) is one of {@link #PREFIXES} or under one. Read the way the
     * controllers would see it: %-escapes decoded, ";params" and empty or "." segments dropped, ".." applied, any case
     * (so /api/v1/%6Birana/x and /api/v1//kirana/ count). /api/v1/kiranaX doesn't: only whole segments match.
     */
    static boolean kiranaPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String raw : uri.split("/", -1)) {
            int semicolon = raw.indexOf(';');
            String segment = decode(semicolon >= 0 ? raw.substring(0, semicolon) : raw);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                segments.pollLast();
                continue;
            }
            segments.addLast(segment.toLowerCase(Locale.ROOT));
        }
        String path = "/" + String.join("/", segments);
        for (String prefix : PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** %XX escapes decoded as UTF-8; a broken escape is kept as it is (such a path reaches no controller). */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(segment.length());
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int high = Character.digit(bytes[i + 1], 16);
                int low = Character.digit(bytes[i + 2], 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
