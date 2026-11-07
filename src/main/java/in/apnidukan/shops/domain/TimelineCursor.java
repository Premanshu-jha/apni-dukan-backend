package in.apnidukan.shops.domain;

import java.time.Instant;

/**
 * Where a page of a shop's timeline ended: the last entry's time and seq (the order of entries made in the same
 * instant). The timeline is newest first, so the next page is everything before this pair.
 */
public record TimelineCursor(Instant afterAt, long afterSeq) {
}
