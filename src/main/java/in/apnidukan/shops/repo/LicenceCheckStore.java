package in.apnidukan.shops.repo;

import in.apnidukan.shops.domain.LicenceCheckReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The licence checks' rows in shops (V55), in plain SQL. The job takes due checks ({@link #claim}), reads them with
 * no transaction open, then writes the answer ({@link #pass}, {@link #fail}, {@link #retryAt}, {@link #unusable}) only
 * while the same check is still there: the same file, still PENDING, and the try it took (licence_check_attempts),
 * which a new check (a new file, the salon's new name or address, the field team asking) starts again from 0. So an
 * answer about an older licence or older details is dropped. None of these change the shop's version: the owner's
 * next save isn't refused because of a check.
 */
@Repository
public class LicenceCheckStore {

    /**
     * A check taken by the job.
     * @param attempt  this is the job's n-th try at this check (1 the first time); its answer must still find it
     * @param unusable answers so far the job couldn't use
     * @param draft    the salon is still registering (its owner's messages open the registration step, not More)
     */
    public record Due(UUID shopId, UUID fileId, String name, String ownerName, String addressLine, String area,
                      String city, String pincode, int attempt, int unusable, boolean draft) {
    }

    /** A licence coming to its last day (LicenceExpiryJob). */
    public record Expiring(UUID shopId, LocalDate validUntil, boolean draft) {
    }

    private final JdbcTemplate jdbc;

    public LicenceCheckStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes every waiting check due now (the AI works again after refusing our account): the licences that waited,
     * some for hours between tries, are read in the next rounds instead of at their next try. Uses
     * ix_shops_licence_due; the job still reads them {@code BATCH} at a time.
     */
    public int dueNow(Instant now) {
        return jdbc.update("UPDATE shops SET licence_check_due_at = ? WHERE licence_check_status = 'PENDING' "
                + "AND licence_check_due_at > ?", Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * Starts the check of the trade licence now there (PENDING, due {@code dueAt}, any earlier answer gone), whatever
     * the row says: called by ShopService after saving a new (or the same, failed) licence or new details, in the same
     * transaction. Written here and not only by the entity, which leaves out a column it thinks unchanged: had the job
     * just answered for the old one, its answer would otherwise stay. Only a trade licence is ever checked.
     */
    public void start(UUID shopId, Instant now, Instant dueAt) {
        jdbc.update("""
                        UPDATE shops SET licence_check_status = 'PENDING', licence_check_reason = NULL,
                            licence_number = NULL, licence_valid_until = NULL, licence_extract = NULL,
                            licence_check_attempts = 0, licence_check_unusable = 0, licence_check_due_at = ?,
                            licence_check_since = ?, licence_expiry_notice = 0, registration_doc_checked_at = NULL
                        WHERE id = ? AND registration_doc_file_id IS NOT NULL
                          AND registration_doc_type = 'TRADE_LICENCE'""",
                Timestamp.from(dueAt), Timestamp.from(now), shopId);
    }

    /**
     * Takes up to {@code limit} trade licence checks due by {@code now} (soonest first, ix_shops_licence_due),
     * skipping rows another transaction holds, and moves each one's due time to {@code retryAt}: if this server dies
     * mid-read, the check is taken again then. Counts the try.
     */
    public List<Due> claim(Instant now, Instant retryAt, int limit) {
        return jdbc.query("""
                        UPDATE shops s SET licence_check_attempts = s.licence_check_attempts + 1,
                            licence_check_due_at = ?
                        FROM (SELECT id FROM shops WHERE licence_check_status = 'PENDING' AND licence_check_due_at <= ?
                                AND registration_doc_type = 'TRADE_LICENCE'
                              ORDER BY licence_check_due_at, id LIMIT ? FOR UPDATE SKIP LOCKED) d
                        WHERE s.id = d.id
                        RETURNING s.id, s.registration_doc_file_id, s.name, s.owner_name, s.address_line, s.area,
                            s.city, s.pincode, s.licence_check_attempts, s.licence_check_unusable, s.status""",
                (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getObject("registration_doc_file_id", UUID.class),
                        rs.getString("name"), rs.getString("owner_name"), rs.getString("address_line"),
                        rs.getString("area"), rs.getString("city"), rs.getString("pincode"),
                        rs.getInt("licence_check_attempts"), rs.getInt("licence_check_unusable"),
                        "DRAFT".equals(rs.getString("status"))),
                Timestamp.from(retryAt), Timestamp.from(now), limit);
    }

    /** Another salon's check already PASSED with this licence number. */
    public boolean numberTaken(String licenceNumber, UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shops WHERE licence_number = ? "
                + "AND licence_check_status = 'PASSED' AND id <> ?)", Boolean.class, licenceNumber, shopId));
    }

    /**
     * PASSED: customers see "Licensed" until {@code validUntil}. False if the check changed meanwhile (nothing
     * written). DuplicateKeyException if another salon passed with the same number at the same moment.
     */
    public boolean pass(Due check, String licenceNumber, LocalDate validUntil, String extractJson, Instant now) {
        return jdbc.update("""
                        UPDATE shops SET licence_check_status = 'PASSED', licence_check_reason = NULL,
                            licence_number = ?, licence_valid_until = ?, licence_extract = ?::jsonb,
                            licence_check_due_at = NULL, registration_doc_checked_at = ?
                        WHERE id = ? AND registration_doc_file_id = ? AND licence_check_status = 'PENDING'
                          AND licence_check_attempts = ?""",
                licenceNumber, validUntil, extractJson, Timestamp.from(now), check.shopId(), check.fileId(),
                check.attempt()) == 1;
    }

    /** FAILED with the reason (what was read, if anything, kept for the pages). False if the check changed. */
    public boolean fail(Due check, LicenceCheckReason reason, String extractJson) {
        return jdbc.update("""
                        UPDATE shops SET licence_check_status = 'FAILED', licence_check_reason = ?,
                            licence_number = NULL, licence_valid_until = NULL, licence_extract = ?::jsonb,
                            licence_check_due_at = NULL, registration_doc_checked_at = NULL
                        WHERE id = ? AND registration_doc_file_id = ? AND licence_check_status = 'PENDING'
                          AND licence_check_attempts = ?""",
                reason.name(), extractJson, check.shopId(), check.fileId(), check.attempt()) == 1;
    }

    /** Still PENDING: tried again at {@code at} (the AI service didn't answer). */
    public void retryAt(Due check, Instant at) {
        jdbc.update("UPDATE shops SET licence_check_due_at = ? WHERE id = ? AND registration_doc_file_id = ? "
                        + "AND licence_check_status = 'PENDING' AND licence_check_attempts = ?",
                Timestamp.from(at), check.shopId(), check.fileId(), check.attempt());
    }

    /** Still PENDING after an answer that couldn't be used: counted, tried again at {@code at}. */
    public void unusable(Due check, Instant at) {
        jdbc.update("UPDATE shops SET licence_check_unusable = licence_check_unusable + 1, licence_check_due_at = ? "
                        + "WHERE id = ? AND registration_doc_file_id = ? AND licence_check_status = 'PENDING' "
                        + "AND licence_check_attempts = ?",
                Timestamp.from(at), check.shopId(), check.fileId(), check.attempt());
    }

    /** The shop's owner (to tell about the result); empty if it has none now (closed, handed over meanwhile). */
    public List<UUID> owners(UUID shopId) {
        return jdbc.queryForList("SELECT user_id FROM shop_members WHERE shop_id = ? AND role = 'OWNER'", UUID.class,
                shopId);
    }

    /**
     * Salons whose licence wasn't accepted, or has waited for its check since {@code waitingSince} or longer, longest
     * first (ix_shops_licence_follow_up).
     */
    public List<UUID> followUp(Instant waitingSince, int limit) {
        return jdbc.queryForList("SELECT id FROM shops WHERE licence_check_status IN ('PENDING', 'FAILED') "
                        + "AND (licence_check_status = 'FAILED' OR licence_check_since <= ?) "
                        + "ORDER BY licence_check_since, id LIMIT ?",
                UUID.class, Timestamp.from(waitingSince), limit);
    }

    /**
     * Passed licences whose owner hasn't been told yet that the last day is near (on or before {@code remindBy}) or,
     * past it, that it expired (ix_shops_licence_expiry): notice 0 and the day within reach, or notice below 2 and the
     * day gone. At most {@code limit}, soonest first.
     */
    public List<Expiring> expiring(LocalDate today, LocalDate remindBy, int limit) {
        return jdbc.query("""
                        SELECT id, licence_valid_until, status FROM shops
                        WHERE licence_check_status = 'PASSED' AND licence_expiry_notice < 2
                          AND licence_valid_until <= ? AND status <> 'CLOSED'
                          AND (licence_expiry_notice = 0 OR licence_valid_until < ?)
                        ORDER BY licence_valid_until, id LIMIT ?""",
                (rs, i) -> new Expiring(rs.getObject("id", UUID.class),
                        rs.getObject("licence_valid_until", LocalDate.class), "DRAFT".equals(rs.getString("status"))),
                remindBy, today, limit);
    }

    /** Marks the message sent (1: the last day is near, 2: expired), once (false if another server did it first). */
    public boolean noticeSent(UUID shopId, LocalDate validUntil, int notice) {
        return jdbc.update("UPDATE shops SET licence_expiry_notice = ? WHERE id = ? AND licence_check_status = 'PASSED' "
                + "AND licence_valid_until = ? AND licence_expiry_notice < ?", notice, shopId, validUntil, notice) == 1;
    }
}
