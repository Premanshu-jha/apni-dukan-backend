package in.apnidukan.salon.service;

import in.apnidukan.common.context.CurrentUser;
import in.apnidukan.salon.repo.SalonVersionStore;
import in.apnidukan.salon.service.SalonAccess.Caller;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ETags for the partner app's day board and upcoming list, which it asks for every 30 and 60 seconds. When nothing
 * changed, the answer is 304 after one primary-key read (salon_versions), before any other query.
 *
 * A tag stands for: which answer (day or upcoming, and its date or page), the salon's change counter, who asked
 * (their id and role: a barber sees only himself, the phone number only goes to the owner and helpers) and until when
 * time alone can't change the answer ({@code validUntil}: the next start of a waiting booking, which turns it
 * "not started", the next end, which drops it off the upcoming list, or midnight). All of it goes into a SHA-256
 * fingerprint; validUntil is also written in the tag in the clear, so the check can tell an expired tag without
 * building anything. A tag changed by hand no longer matches its fingerprint.
 *
 * Shop and role come from the sign-in token, so the 304 check needs no query to find the caller. A token always
 * carries the shop its person works in: removing someone from a salon ends their sessions at once, and any change to
 * who works there or may add walk-ins moves the counter. A token without a shop (or naming another shop or role than
 * the database, a few seconds after a change) gets no tag of its own (EtagConfig fingerprints that answer as before).
 */
@Component
public class SalonEtags {

    public static final String DAY = "day";
    public static final String UPCOMING = "upcoming";

    /** "s1-<validUntil, epoch seconds>-<fingerprint>", quoted as HTTP wants. */
    private static final Pattern OURS = Pattern.compile("\"s1-(\\d{1,12})-([0-9a-f]{32})\"");
    /** At most this many tags of one If-None-Match header are looked at. */
    private static final int MAX_TAGS = 10;

    /** The salon's counter, read for the caller the token names. */
    public record Version(UUID shopId, String role, UUID userId, long version) {
    }

    private final SalonVersionStore versions;
    private final Clock clock;

    public SalonEtags(SalonVersionStore versions, Clock clock) {
        this.versions = versions;
        this.clock = clock;
    }

    /** The counter of the token's salon (one primary-key read); empty when the token names no salon. */
    public Optional<Version> version(CurrentUser me) {
        if (me.shopId() == null || me.role() == null) {
            return Optional.empty();
        }
        return Optional.of(new Version(me.shopId(), me.role(), me.userId(), versions.find(me.shopId()).version()));
    }

    /**
     * The tag of an answer built for this caller after the counter was read; empty if the database says the caller is
     * someone else than the token did (a change of the last few seconds: no tag rather than a wrong one).
     */
    Optional<String> tag(Version v, Caller c, String kind, String params, Instant validUntil) {
        if (!v.shopId().equals(c.shopId()) || !v.role().equals(c.role()) || !v.userId().equals(c.userId())) {
            return Optional.empty();
        }
        return Optional.of(tag(kind, v, params, validUntil.getEpochSecond()));
    }

    /**
     * The tag the app sent back (If-None-Match) that still describes the answer, if one does: same salon counter,
     * caller and request, and not past its validUntil. Tags that aren't ours (or have run out) cost no query at all.
     */
    public Optional<String> unchanged(CurrentUser me, String kind, String params, List<String> ifNoneMatch) {
        long now = clock.instant().getEpochSecond();
        List<String[]> sent = ifNoneMatch.stream()
                .flatMap(header -> {
                    Matcher m = OURS.matcher(header);
                    return m.results().map(r -> new String[] {r.group(), r.group(1)});
                })
                .filter(t -> now < Long.parseLong(t[1]))
                .limit(MAX_TAGS)
                .toList();
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        Optional<Version> v = version(me);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        for (String[] t : sent) {
            if (tag(kind, v.get(), params, Long.parseLong(t[1])).equals(t[0])) {
                return Optional.of(t[0]);
            }
        }
        return Optional.empty();
    }

    private static String tag(String kind, Version v, String params, long validUntil) {
        String what = String.join("|", kind, v.shopId().toString(), Long.toString(v.version()), v.userId().toString(),
                v.role(), params, Long.toString(validUntil));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(what.getBytes(StandardCharsets.UTF_8));
            return "\"s1-" + validUntil + "-" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }
}
