package in.apnidukan.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JSON bodies are small (a phone number, a code, an order). Anything over 64 KB is refused before it is read, so
 * nobody can tie up the server's memory with a huge body. Only the file upload (POST /api/v1/files) may be larger;
 * it has its own 10 MB limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_JSON_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        // Only the file upload may send a big multipart body (it has its own 10 MB limit)
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")
                && "POST".equals(request.getMethod()) && "/api/v1/files".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"This request is too large\",\"details\":{}}");
            return;
        }
        // No length given (chunked): count while reading and stop at the limit.
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** Thrown while reading a body that turns out to be too large; answered as 413 by GlobalExceptionHandler. */
    public static final class RequestTooLargeException extends IOException {
        RequestTooLargeException() {
            super("Request body over " + MAX_JSON_BYTES + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedStream(super.getInputStream());
            }
            return limited;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream in;
        private long read;

        LimitedStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = in.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_JSON_BYTES) {
                throw new RequestTooLargeException();
            }
        }

        @Override
        public boolean isFinished() { return in.isFinished(); }

        @Override
        public boolean isReady() { return in.isReady(); }

        @Override
        public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
    }
}
