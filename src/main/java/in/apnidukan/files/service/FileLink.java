package in.apnidukan.files.service;

import java.time.Instant;

/** A temporary link to a file and when it stops working. */
public record FileLink(String url, Instant expiresAt) {
}
