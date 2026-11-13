package in.apnidukan.helpdesk.api;

import in.apnidukan.helpdesk.service.InboundMail;
import in.apnidukan.helpdesk.service.InboundSignature;
import in.apnidukan.helpdesk.service.TicketService.Received;
import in.apnidukan.common.error.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The support inbox: Cloudflare's Email Worker posts each email sent to support@clozest.in here (the raw message, or
 * only its headers when over 1 MB, with X-Helpdesk-Truncated: 1 and, when it parked the whole email in storage, its
 * key in X-Clozest-Raw among those headers; see {@link InboundMail}), signed (see {@link InboundSignature}). Open to the
 * internet without a sign-in; the signature is the check. Answers 200 {"result": CREATED | ADDED | DUPLICATE |
 * IGNORED}, so the worker knows it arrived; 403 BAD_SIGNATURE; 400 NOT_AN_EMAIL.
 */
@RestController
@RequestMapping("/api/v1/helpdesk")
public class InboundEmailController {

    /** Our own domain: an email from it coming back in is a loop, never a ticket. */
    static final String OUR_DOMAIN = "clozest.in";

    private final InboundMail mail;
    private final InboundSignature signature;

    public InboundEmailController(InboundMail mail, InboundSignature signature) {
        this.mail = mail;
        this.signature = signature;
    }

    @PostMapping(value = "/inbound", consumes = "message/rfc822")
    public Map<String, String> receive(@RequestHeader(value = "X-Helpdesk-Timestamp", required = false) String timestamp,
                                       @RequestHeader(value = "X-Helpdesk-Signature", required = false) String sig,
                                       @RequestHeader(value = "X-Helpdesk-Truncated", required = false) String truncated,
                                       @RequestBody byte[] raw) {
        signature.check(timestamp, sig, raw);
        Received result;
        try {
            result = mail.take(raw, "1".equals(truncated), OUR_DOMAIN);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("NOT_AN_EMAIL", "That isn't a readable email.");
        }
        return Map.of("result", result.name());
    }
}
