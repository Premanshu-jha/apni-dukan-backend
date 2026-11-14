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

    /** Not answering now; try later. */
    class ReaderUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReaderUnavailableException(String message) {
            super(message);
        }
    }

    /** An answer that isn't the facts asked for. */
    class UnusableAnswerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnusableAnswerException(String message) {
            super(message);
        }
    }
}
