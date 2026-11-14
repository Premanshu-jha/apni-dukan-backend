package in.apnidukan.shops.service;

/** Reads a trade licence (a photo or a PDF) into facts. AnthropicLicenceReader in the app; a fake in tests. */
public interface LicenceReader {

    /**
     * @param bytes       the uploaded file (a cleaned JPEG photo, or a PDF as it came)
     * @param contentType image/jpeg, image/png or application/pdf
     * @throws ReaderUnavailableException when the service can't answer now (network, overloaded, rate limited):
     *                                    tried again later, never the owner's fault
     * @throws UnusableAnswerException    when it answered something we can't use (tried again a few times)
     */
    LicenceExtract read(byte[] bytes, String contentType);

    /**
     * Not answering now; try later. {@link #problem()} says when it is our account's fault (out of credit, a wrong key
     * or model), for LicenceAiWatch to email the super admins; null for a network failure or the AI being busy.
     */
    class ReaderUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final LicenceAiWatch.Problem problem;

        public ReaderUnavailableException(String message) {
            this(message, null);
        }

        public ReaderUnavailableException(String message, LicenceAiWatch.Problem problem) {
            super(message);
            this.problem = problem;
        }

        public LicenceAiWatch.Problem problem() {
            return problem;
        }
    }

    /** An answer that isn't the facts asked for. */
    class UnusableAnswerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean answered;

        /** An answer the AI gave (billed, so the account works) that we can't use. */
        public UnusableAnswerException(String message) {
            this(message, true);
        }

        /** @param answered false when the request itself was refused (the file too big): nothing about the account */
        public UnusableAnswerException(String message, boolean answered) {
            super(message);
            this.answered = answered;
        }

        public boolean answered() {
            return answered;
        }
    }
}
