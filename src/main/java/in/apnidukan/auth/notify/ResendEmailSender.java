package in.apnidukan.auth.notify;

import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends email through Resend's HTTP API (POST https://api.resend.com/emails with the API key as a Bearer token),
 * used instead of Gmail SMTP once RESEND_API_KEY is set (EmailConfig). Gmail allows a few hundred emails a day from
 * one account; Resend is made for an app's own email in volume, and one HTTPS call answers in well under a second.
 *
 * Sends while the request waits, with short timeouts (3 s to connect, 5 s for the answer): a code email is sent
 * before the app is told 202, so the person can expect it within seconds, and if Resend refuses or doesn't answer,
 * the app is told to try again (503 EMAIL_FAILED) instead of waiting for a code that never comes. Never logs the
 * email's text or the API key (only the subject and Resend's HTTP status).
 */
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    public static final URI RESEND = URI.create("https://api.resend.com/emails");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(5);

    private final String apiKey;
    private final String from;
    private final URI endpoint;
    private final HttpClient http;

    /**
     * @param from     who the email is from, e.g. "Apni Dukan &lt;no-reply@apnidukan.in&gt;" (an address on a domain
     *                 verified in Resend; onboarding@resend.dev only reaches the Resend account's own address)
     * @param endpoint {@link #RESEND}; a local fake in tests
     */
    public ResendEmailSender(String apiKey, String from, URI endpoint) {
        if (apiKey.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Email: RESEND_API_KEY and EMAIL_FROM are both needed to send through "
                    + "Resend.");
        }
        this.apiKey = apiKey;
        this.from = from;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** True while sending from Resend's shared test address, which can't reach real customers (see ProductionSafetyCheck). */
    public boolean usesTestSender() {
        return from.contains("@resend.dev");
    }

    @Override
    public void send(String to, String subject, String body) {
        String json = "{\"from\":" + json(from) + ",\"to\":[" + json(to) + "],\"subject\":" + json(subject)
                + ",\"text\":" + json(body) + "}";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(ANSWER_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        int status;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {   // includes the timeouts
            log.error("Could not send email ({}): {}", subject, e.getClass().getSimpleName());
            throw failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed();
        }
        if (status < 200 || status >= 300) {
            log.error("Resend refused the email ({}): HTTP {}", subject, status);
            throw failed();
        }
    }

    private static ApiException failed() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_FAILED",
                "We couldn't send the email. Please try again in a minute.");
    }

    /** A JSON string literal, quotes included. */
    static String json(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
