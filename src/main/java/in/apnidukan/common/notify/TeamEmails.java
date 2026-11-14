package in.apnidukan.common.notify;

/**
 * Emails the Apni Dukan team about the app itself (the licence-reading AI out of credit, say). Implemented by the
 * auth module: it knows who the super admins are and sends through the same provider as the sign-in codes.
 */
public interface TeamEmails {

    /**
     * Sends the email to every super admin with a verified email, one by one; one address the provider refuses
     * doesn't stop the others (it is logged, never its address).
     *
     * @return how many there were to send to, and how many it reached
     */
    Sent toSuperAdmins(String subject, String body);

    /** How many super admins have a verified email (0: nobody gets the team's emails; the dashboard says so). */
    int superAdminRecipients();

    /** @param recipients super admins with a verified email; {@code sent} of them got it */
    record Sent(int recipients, int sent) {

        /** Nobody to send to, or it reached someone: there's no point trying again. */
        public boolean done() {
            return recipients == 0 || sent > 0;
        }
    }
}
