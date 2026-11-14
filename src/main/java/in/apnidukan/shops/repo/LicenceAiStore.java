package in.apnidukan.shops.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * What the licence-reading AI has cost per credit period, and whether it is refusing our account (V60), in plain
 * SQL. Every write is one statement, so two answers added at the same moment both count, and an email is marked sent
 * only by the call that moved the mark.
 */
@Repository
public class LicenceAiStore {

    /** A period's totals. {@code budgetNotice}: 0 nothing sent, 1 the 80% email sent, 2 the 100% one sent. */
    public record Spend(LocalDate periodStart, int reads, long inputTokens, long outputTokens, long costMicros,
                        int budgetNotice) {

        public static Spend none(LocalDate periodStart) {
            return new Spend(periodStart, 0, 0, 0, 0, 0);
        }
    }

    /**
     * The AI refusing our account: CREDIT, KEY or MODEL, since when, when its email was last claimed, and whether
     * an email about it reached someone.
     */
    public record Alert(String problem, Instant since, Instant emailedAt, boolean announced) {
    }

    private final JdbcTemplate jdbc;

    public LicenceAiStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds one answer's tokens and cost to its period; returns the period's totals after it. */
    public Spend add(LocalDate periodStart, long inputTokens, long outputTokens, long costMicros) {
        return jdbc.queryForObject("""
                        INSERT INTO licence_ai_spend AS s (period_start, reads, input_tokens, output_tokens, cost_micros)
                        VALUES (?, 1, ?, ?, ?)
                        ON CONFLICT (period_start) DO UPDATE SET reads = s.reads + 1,
                            input_tokens = s.input_tokens + EXCLUDED.input_tokens,
                            output_tokens = s.output_tokens + EXCLUDED.output_tokens,
                            cost_micros = s.cost_micros + EXCLUDED.cost_micros
                        RETURNING period_start, reads, input_tokens, output_tokens, cost_micros, budget_notice""",
                (rs, n) -> spend(rs), periodStart, inputTokens, outputTokens, costMicros);
    }

    /** The period's totals (none yet: zeros). */
    public Spend spend(LocalDate periodStart) {
        try {
            return jdbc.queryForObject("SELECT period_start, reads, input_tokens, output_tokens, cost_micros, "
                    + "budget_notice FROM licence_ai_spend WHERE period_start = ?", (rs, n) -> spend(rs), periodStart);
        } catch (EmptyResultDataAccessException none) {
            return Spend.none(periodStart);
        }
    }

    /**
     * Marks the period's budget email {@code notice} (1 or 2) sent, only if a lower one was the last: false when it
     * already was (another answer at the same moment sent it), so it goes out once.
     */
    public boolean budgetNoticeSent(LocalDate periodStart, int notice) {
        return jdbc.update("UPDATE licence_ai_spend SET budget_notice = ? WHERE period_start = ? AND budget_notice < ?",
                notice, periodStart, notice) == 1;
    }

    /** Takes a sent budget email back (nobody got it), so the next answer tries again. */
    public void budgetNoticeNotSent(LocalDate periodStart, int notice, int before) {
        jdbc.update("UPDATE licence_ai_spend SET budget_notice = ? WHERE period_start = ? AND budget_notice = ?",
                before, periodStart, notice);
    }

    public Alert alert() {
        return jdbc.queryForObject("SELECT problem, problem_since, emailed_at, announced FROM licence_ai_alert "
                + "WHERE id = 1", (rs, n) -> alert(rs));
    }

    /**
     * Records the problem (CREDIT, KEY or MODEL) and claims its email when one is due: the problem is new or
     * different, or the last claim was before {@code emailAgainBefore}. Returns the problem's start when this call
     * claimed the email (send it, then {@link #emailed} or {@link #emailFailed}); empty when there is nothing to send.
     * A different problem keeps {@code announced}: the super admins were told the checks stopped.
     */
    public Optional<Instant> problem(String problem, Instant now, Instant emailAgainBefore) {
        return jdbc.query("""
                        UPDATE licence_ai_alert SET
                            problem_since = CASE WHEN problem IS NULL THEN ? ELSE problem_since END,
                            problem = ?, emailed_at = ?
                        WHERE id = 1 AND (problem IS DISTINCT FROM ? OR emailed_at IS NULL OR emailed_at < ?)
                        RETURNING problem_since""",
                (rs, n) -> rs.getTimestamp(1).toInstant(), Timestamp.from(now), problem, Timestamp.from(now),
                problem, Timestamp.from(emailAgainBefore)).stream().findFirst();
    }

    /** The email claimed at {@code claimedAt} reached someone: "working again" is owed when it answers. */
    public void emailed(Instant claimedAt) {
        jdbc.update("UPDATE licence_ai_alert SET announced = true WHERE id = 1 AND emailed_at = ?",
                Timestamp.from(claimedAt));
    }

    /** The email claimed at {@code claimedAt} reached nobody: due again at the next refusal. */
    public void emailFailed(Instant claimedAt) {
        jdbc.update("UPDATE licence_ai_alert SET emailed_at = NULL WHERE id = 1 AND emailed_at = ?",
                Timestamp.from(claimedAt));
    }

    /**
     * The AI answered: clears the problem and returns it as it was (its "working again" email is due when it was
     * announced); empty when there was none. One caller gets it: the row is locked first.
     */
    public Optional<Alert> working() {
        return jdbc.query("""
                        WITH old AS (SELECT problem, problem_since, emailed_at, announced FROM licence_ai_alert
                                     WHERE id = 1 AND problem IS NOT NULL FOR UPDATE)
                        UPDATE licence_ai_alert a SET problem = NULL, problem_since = NULL, emailed_at = NULL,
                            announced = false
                        FROM old WHERE a.id = 1
                        RETURNING old.problem, old.problem_since, old.emailed_at, old.announced""",
                (rs, n) -> alert(rs)).stream().findFirst();
    }

    /**
     * The "working again" email reached nobody: owed, and sent by {@link #owed} on the job's next round (the problem
     * itself stays cleared: the AI works). A newer one replaces it.
     */
    public void owe(Alert was) {
        jdbc.update("UPDATE licence_ai_alert SET owed_problem = ?, owed_since = ? WHERE id = 1", was.problem(),
                Timestamp.from(was.since()));
    }

    /** A "working again" email owed, if any. */
    public Optional<Alert> owed() {
        return jdbc.query("SELECT owed_problem, owed_since, NULL::timestamptz, true FROM licence_ai_alert "
                + "WHERE id = 1 AND owed_problem IS NOT NULL", (rs, n) -> alert(rs)).stream().findFirst();
    }

    /** The owed email was sent (only the one read: a newer one owed meanwhile stays). */
    public void paid(Alert was) {
        jdbc.update("UPDATE licence_ai_alert SET owed_problem = NULL, owed_since = NULL WHERE id = 1 "
                + "AND owed_problem = ? AND owed_since = ?", was.problem(), Timestamp.from(was.since()));
    }

    /** A new problem: no "working again" is owed for an older one (it would arrive after the new stop). */
    public void forgetOwed() {
        jdbc.update("UPDATE licence_ai_alert SET owed_problem = NULL, owed_since = NULL WHERE id = 1 "
                + "AND owed_problem IS NOT NULL");
    }

    private static Spend spend(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Spend(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getInt(6));
    }

    private static Alert alert(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Alert(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)), rs.getBoolean(4));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
