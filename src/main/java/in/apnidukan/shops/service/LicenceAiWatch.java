package in.apnidukan.shops.service;

import in.apnidukan.common.notify.TeamEmails;
import in.apnidukan.shops.repo.LicenceAiStore;
import in.apnidukan.shops.repo.LicenceCheckStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps an eye on the licence-reading AI's credit, and tells the super admins by email:
 * <ul>
 * <li>what it costs: each answer's tokens (the same counts Anthropic bills) priced at the model's rates, added up per
 * credit period, which starts on the day of the month the plan's API credits arrive ({@code app.licence.ai-budget-day},
 * India's date); an email at 80% of the period's budget and one at 100%, each once;</li>
 * <li>when Anthropic refuses our account (out of credit or over a spend cap, a wrong key, a wrong model): an email at
 * once, again while it lasts (a day later for credit, which may well wait for next month's; 6 hours for a key or
 * model, a mistake to fix), and one "working again" when it answers, after which every waiting licence is read at
 * once.</li>
 * </ul>
 * Called from LicenceCheckJob's thread (one server at a time), never inside a transaction: emails are sent with
 * nothing locked, and each is claimed in the database first, so it goes out once. The text is built before the claim,
 * and an email that reached nobody is handed back and tried at the next answer or refusal (a "working again" one at
 * the job's next round); while the email provider
 * fails for everyone, no email is tried for {@link #PROVIDER_PAUSE}, so licence checks don't wait on it. The totals are
 * exact for this app's key; anything else spending the same Console organization isn't counted (Console's Billing
 * page has the official balance).
 */
@Component
public final class LicenceAiWatch implements LicenceAiUsage {

    private static final Logger log = LoggerFactory.getLogger(LicenceAiWatch.class);
    static final Duration EMAIL_AGAIN = Duration.ofHours(6);
    static final Duration CREDIT_EMAIL_AGAIN = Duration.ofHours(24);
    static final Duration PROVIDER_PAUSE = Duration.ofMinutes(10);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final String CONSOLE_BILLING = "https://platform.claude.com/settings/billing";
    static final String CONSOLE_KEYS = "https://platform.claude.com/settings/keys";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    /** Why the AI refuses: the account's credit, billing or spend cap; the key (401/403); the model name (404). */
    public enum Problem {
        CREDIT, KEY, MODEL
    }

    /**
     * The current period, for the admin dashboard. {@code problem} null while the AI answers; {@code keySet} false
     * without ANTHROPIC_API_KEY (nothing is read); {@code alertRecipients} the super admins the emails reach.
     */
    public record Usage(LocalDate periodStart, LocalDate periodEnd, int reads, BigDecimal spentUsd,
                        BigDecimal budgetUsd, String problem, Instant problemSince, boolean keySet,
                        int alertRecipients) {
    }

    private final LicenceAiStore store;
    private final LicenceCheckStore checks;
    private final TeamEmails emails;
    private final ObjectProvider<LicenceReader> reader;
    private final Clock clock;
    private final BigDecimal budgetUsd;
    private final int budgetDay;
    private final BigDecimal inputPerMTok;
    private final BigDecimal outputPerMTok;
    /** No email is tried before this (the provider failed for everyone); this server's job thread sets it. */
    private volatile Instant emailsPausedUntil = Instant.MIN;

    public LicenceAiWatch(LicenceAiStore store, LicenceCheckStore checks, TeamEmails emails,
                          ObjectProvider<LicenceReader> reader, Clock clock,
                          @Value("${app.licence.ai-budget-usd:100}") BigDecimal budgetUsd,
                          @Value("${app.licence.ai-budget-day:1}") int budgetDay,
                          @Value("${app.licence.ai-input-usd-per-mtok:2}") BigDecimal inputPerMTok,
                          @Value("${app.licence.ai-output-usd-per-mtok:10}") BigDecimal outputPerMTok) {
        if (budgetDay < 1 || budgetDay > 28) {
            throw new IllegalStateException("app.licence.ai-budget-day (LICENCE_AI_BUDGET_DAY) must be 1 to 28");
        }
        if (budgetUsd.signum() <= 0 || inputPerMTok.signum() < 0 || outputPerMTok.signum() < 0) {
            throw new IllegalStateException("The licence AI's budget must be above 0 and its prices not below 0");
        }
        this.store = store;
        this.checks = checks;
        this.emails = emails;
        this.reader = reader;
        this.clock = clock;
        this.budgetUsd = budgetUsd;
        this.budgetDay = budgetDay;
        this.inputPerMTok = inputPerMTok;
        this.outputPerMTok = outputPerMTok;
    }

    /** One answer's tokens (AnthropicLicenceReader, for every answer it was billed for, usable or not). */
    @Override
    public void record(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            return;
        }
        try {
            LocalDate period = periodStart(today());
            LicenceAiStore.Spend spend = store.add(period, inputTokens, outputTokens, costMicros(inputTokens, outputTokens));
            budgetEmail(spend);
        } catch (RuntimeException e) {   // never lose the licence's answer over the bookkeeping
            log.warn("Licence AI cost not recorded: {}", e.getClass().getSimpleName());
        }
    }

    /** The AI refused the account: email the super admins if it is new, different, or its last email is old. */
    public void problem(Problem problem) {
        Instant now = now();
        if (paused(now)) {
            // Recorded for the dashboard (claimed and handed straight back): emailed after the pause
            store.problem(problem.name(), now, now).ifPresent(since -> store.emailFailed(now));
            return;
        }
        String body = problemBody(problem, now, spendLine());   // built first: nothing between claim and send fails
        Duration again = problem == Problem.CREDIT ? CREDIT_EMAIL_AGAIN : EMAIL_AGAIN;
        Optional<Instant> since = store.problem(problem.name(), now, now.minus(again));
        if (since.isEmpty()) {
            return;
        }
        if (!since.get().equals(now)) {
            body = problemBody(problem, since.get(), spendLine());   // still going: the email says since when
        }
        store.forgetOwed();   // stopped again: a "working again" still owed would now be wrong
        boolean sent = send(subject(problem), body);
        if (sent) {
            store.emailed(now);
        } else {
            store.emailFailed(now);
        }
        log.error("Licence AI refused the account: {} (super admins {})", problem, sent ? "emailed" : "not reached");
    }

    /**
     * The AI answered. If it was refusing our account: every waiting licence is read now, and when the super admins
     * were told it stopped, they are told it works again (tried again at the next answer if it reaches nobody).
     */
    public void working() {
        Optional<LicenceAiStore.Alert> cleared = store.working();
        if (cleared.isEmpty()) {
            return;
        }
        LicenceAiStore.Alert was = cleared.get();
        int waiting = checks.dueNow(now());
        log.info("Licence AI answers again after {}; {} waiting licences due now", was.problem(), waiting);
        if (!was.announced()) {
            return;   // nobody heard of the problem: nothing to take back
        }
        if (!sendWorking(was)) {
            store.owe(was);   // sent by retryOwed on the job's next rounds
        }
    }

    /**
     * Sends a "working again" email that reached nobody before (LicenceCheckJob, each round, even with nothing to
     * read): the dashboard already shows the checks working, so only the email is owed.
     */
    public void retryOwed() {
        if (paused(now())) {
            return;
        }
        store.owed().ifPresent(was -> {
            if (sendWorking(was)) {
                store.paid(was);
            }
        });
    }

    private boolean sendWorking(LicenceAiStore.Alert was) {
        if (paused(now())) {
            return false;
        }
        String body = "Anthropic is reading trade licences again (it refused from " + time(was.since())
                + ", India time). The licences that waited are being checked now; salons get their answer in the next "
                + "minutes.\n\n" + spendLine();
        return send("Clozest: licence checks are working again", body);
    }

    /** The current period's totals, any refusal, and whether alerts can work, for the admin dashboard. */
    public Usage usage() {
        LocalDate start = periodStart(today());
        LicenceAiStore.Spend spend = store.spend(start);
        LicenceAiStore.Alert alert = store.alert();
        return new Usage(start, start.plusMonths(1).minusDays(1), spend.reads(), usd(spend.costMicros()), budgetUsd,
                alert.problem(), alert.since(), reader.getIfAvailable() != null, emails.superAdminRecipients());
    }

    /** The day the current credit period began: the budget day of this month, or of last month before it. */
    LocalDate periodStart(LocalDate today) {
        LocalDate thisMonth = today.withDayOfMonth(budgetDay);
        return today.isBefore(thisMonth) ? thisMonth.minusMonths(1) : thisMonth;
    }

    /** The cost in millionths of a dollar: a price per million tokens is micro-dollars per token. */
    long costMicros(long inputTokens, long outputTokens) {
        return inputPerMTok.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPerMTok.multiply(BigDecimal.valueOf(outputTokens)))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static String subject(Problem problem) {
        return switch (problem) {
            case CREDIT -> "Clozest: licence checks stopped (Anthropic API credit)";
            case KEY -> "Clozest: licence checks stopped (Anthropic API key)";
            case MODEL -> "Clozest: licence checks stopped (AI model name)";
        };
    }

    String problemBody(Problem problem, Instant since, String spend) {
        LocalDate nextCredit = periodStart(today()).plusMonths(1);
        String why = switch (problem) {
            case CREDIT -> "Anthropic refused to read trade licences because of the account's credit, billing or spend "
                    + "limit: the month's API credit has probably run out.";
            case KEY -> "Anthropic refused our API key: ANTHROPIC_API_KEY on the backend in Railway is wrong, disabled "
                    + "or expired.";
            case MODEL -> "Anthropic doesn't know the AI model the backend asks for: LICENCE_AI_MODEL on the backend in "
                    + "Railway is misspelt, or that model was retired. The API key itself works.";
        };
        String fix = switch (problem) {
            case CREDIT -> "To go on now, buy credits (or turn on auto-reload) in the Claude Console, Billing: "
                    + CONSOLE_BILLING + ". The waiting licences are read within minutes of that. Otherwise the checks "
                    + "start again by themselves when the next month's credit arrives (about " + DAY.format(nextCredit)
                    + "). We'll remind you once a day meanwhile.";
            case KEY -> "Create a key in the Claude Console (" + CONSOLE_KEYS + ") and paste it in Railway: the "
                    + "apni-dukan-backend service, Variables, ANTHROPIC_API_KEY, then Deploy.";
            case MODEL -> "In Railway (the apni-dukan-backend service, Variables), remove LICENCE_AI_MODEL to use the "
                    + "default (claude-sonnet-5-5), or set a current model name, then Deploy.";
        };
        return why + "\n\nSince " + time(since) + " (India time), salons that upload a trade licence see "
                + "\"Checking…\": nothing is accepted or refused, no licence is failed for this, and every one waiting "
                + "is read as soon as Anthropic answers again.\n\n" + fix + "\n\n" + spend
                + "\n\nThe admin website's Businesses page shows this too. You'll get one email when the checks work "
                + "again.";
    }

    private void budgetEmail(LicenceAiStore.Spend spend) {
        BigDecimal spent = usd(spend.costMicros());
        int due = spent.compareTo(budgetUsd) >= 0 ? 2
                : spent.compareTo(budgetUsd.multiply(new BigDecimal("0.8"))) >= 0 ? 1 : 0;
        if (due <= spend.budgetNotice() || paused(now())) {
            return;
        }
        LocalDate next = spend.periodStart().plusMonths(1);
        String subject = due == 2
                ? "Clozest: the licence checks have reached this month's API budget"
                : "Clozest: 80% of the licence checks' API budget used";
        String body = "By Clozest's count, the trade licence checks have cost $" + money(spent) + " of the $"
                + money(budgetUsd) + " budget for " + DAY.format(spend.periodStart()) + " to "
                + DAY.format(next.minusDays(1)) + " (" + licences(spend.reads()) + " read).\n\n"
                + (due == 2
                        ? "When the credit runs out, Anthropic stops reading licences until the next credit arrives "
                                + "(about " + DAY.format(next) + "); salons see \"Checking…\" and nothing is refused, "
                                + "and you'll get an email if that happens. To go on, buy credits or turn on "
                                + "auto-reload in the Claude Console, Billing: " + CONSOLE_BILLING
                        : "Nothing to do yet. If it runs out, the checks wait until about " + DAY.format(next)
                                + " unless you add credits in the Claude Console, Billing: " + CONSOLE_BILLING)
                + "\n\nThe Console's Billing page has the official balance (it also counts anything else using the "
                + "same Console organization).";
        if (!store.budgetNoticeSent(spend.periodStart(), due)) {
            return;   // another answer at the same moment sends it
        }
        if (!send(subject, body)) {
            store.budgetNoticeNotSent(spend.periodStart(), due, spend.budgetNotice());
        }
    }

    /**
     * Sends to every super admin; true when there is no point trying again (it reached someone, or there is nobody to
     * send to). False, and no email tried for {@link #PROVIDER_PAUSE}, when it reached nobody.
     */
    private boolean send(String subject, String body) {
        TeamEmails.Sent sent;
        try {
            sent = emails.toSuperAdmins(subject, body);
        } catch (RuntimeException e) {   // reading who to send to failed: the claim is handed back
            log.warn("Licence AI email \"{}\" not sent: {}", subject, e.getClass().getSimpleName());
            return false;
        }
        if (!sent.done()) {
            emailsPausedUntil = now().plus(PROVIDER_PAUSE);
        }
        return sent.done();
    }

    /** Within the pause after the provider failed (a pause longer than it can be means the clock went back: none). */
    private boolean paused(Instant now) {
        Instant until = emailsPausedUntil;
        return now.isBefore(until) && !until.isAfter(now.plus(PROVIDER_PAUSE));
    }

    private String spendLine() {
        LocalDate start = periodStart(today());
        LicenceAiStore.Spend s = store.spend(start);
        return "This period (" + DAY.format(start) + " to " + DAY.format(start.plusMonths(1).minusDays(1)) + "): $"
                + money(usd(s.costMicros())) + " of $" + money(budgetUsd) + " used by Clozest's count, "
                + licences(s.reads()) + " read.";
    }

    private static String money(BigDecimal usd) {
        return usd.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String licences(int n) {
        return n == 1 ? "1 licence" : n + " licences";
    }

    private static BigDecimal usd(long micros) {
        return BigDecimal.valueOf(micros, 6);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), INDIA);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String time(Instant at) {
        return TIME.format(at.atZone(INDIA));
    }
}
