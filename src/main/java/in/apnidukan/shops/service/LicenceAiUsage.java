package in.apnidukan.shops.service;

/** Told the tokens of every answer the AI was billed for (LicenceAiWatch adds up their cost). */
@FunctionalInterface
public interface LicenceAiUsage {

    void record(long inputTokens, long outputTokens);

    /** Counts nothing (tests that don't care). */
    LicenceAiUsage NONE = (in, out) -> {
    };
}
