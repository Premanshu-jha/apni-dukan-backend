package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * "Is this a person, not a script?" before we pay for an SMS: Cloudflare Turnstile. The app shows Turnstile's
 * widget and sends its token as captchaToken; we ask Cloudflare whether the token is genuine and unused.
 *
 * Off while TURNSTILE_SECRET_KEY is empty (your laptop, tests); production refuses to start without it. If
 * Cloudflare itself is down (no connection, or a 5xx), the code is sent anyway: the per-phone, per-network and
 * daily SMS limits still hold, and people can still sign in. Any other failure counts as a failed check.
 */
@Component
public class HumanCheck {

    private static final Logger log = LoggerFactory.getLogger(HumanCheck.class);

    private final RestClient http;
    private final String secret;

    @Autowired
    public HumanCheck(@Value("${app.captcha.turnstile-secret:}") String secret,
                      @Value("${app.captcha.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
                      String verifyUrl) {
        this(RestClient.builder().requestFactory(timeouts()), secret, verifyUrl);
    }

    /** For tests: a builder bound to a fake Cloudflare. */
    HumanCheck(RestClient.Builder builder, String secret, String verifyUrl) {
        this.secret = secret.strip();
        this.http = builder.baseUrl(verifyUrl).build();
    }

    public boolean enabled() {
        return !secret.isEmpty();
    }

    /** 400 CAPTCHA_REQUIRED without a token, 400 CAPTCHA_FAILED if Cloudflare says it isn't genuine. */
    public void require(String token, String ip) {
        if (!enabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("CAPTCHA_REQUIRED", "Complete the check that you're not a robot.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        if (ip != null) {
            form.add("remoteip", ip);
        }
        Map<?, ?> reply;
        try {
            reply = http.post().contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                    .body(Map.class);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            // Cloudflare down or unreachable: nothing the caller can cause, so people can still sign in
            log.warn("Turnstile didn't answer ({}); sending the code without the check", e.getClass().getSimpleName());
            return;
        } catch (RestClientException e) {
            // Anything else (a 4xx, an unreadable reply) may come from what the caller sent: never a way around
            log.warn("Turnstile refused the check ({})", e.getClass().getSimpleName());
            throw failed();
        }
        if (reply == null || !Boolean.TRUE.equals(reply.get("success"))) {
            throw failed();
        }
    }

    private static ApiException failed() {
        return ApiException.badRequest("CAPTCHA_FAILED", "The robot check didn't pass. Try it again.");
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
