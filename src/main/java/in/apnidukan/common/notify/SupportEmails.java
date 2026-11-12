package in.apnidukan.common.notify;

/**
 * Sends one support email (a ticket's acknowledgement or a support person's reply) from the support address, with
 * replies going back to it. Implemented by the auth module's email provider; used by the helpdesk module's sending
 * job. Throws (an ApiException, 503 EMAIL_FAILED) when the provider refuses or doesn't answer, so the job tries
 * again later.
 */
public interface SupportEmails {

    /**
     * @param emailId the same id for every try of one email (sent as the provider's idempotency key, so a send whose
     *                answer was lost isn't delivered twice)
     * @param logAs   what logs may say about it (the subject can be the person's own words)
     */
    void send(String emailId, String to, String subject, String body, String logAs);
}
