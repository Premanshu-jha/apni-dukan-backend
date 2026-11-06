package in.apnidukan.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The per-network and daily limits on sign-in codes, from application.properties (each overridable on the server).
 *
 * Per network: mobile operators in India put thousands of phones behind one IPv4 address (carrier-grade NAT), so
 * an IPv4 address gets a high limit; it only stops one machine spraying codes. An IPv6 caller is counted per /64
 * (ClientIp.limitKey), which is one phone or one home, so it gets a much smaller one. The per-phone limits in
 * OtpService stay as they are; they are what protects each account.
 *
 * @param ipSendsPerHour      codes requested (SMS or email) per hour from one IPv4 address
 * @param ipv6SendsPerHour    the same from one IPv6 /64
 * @param ipVerifiesPerHour   codes tried per hour from one IPv4 address
 * @param ipv6VerifiesPerHour the same from one IPv6 /64
 * @param smsDailyLimit       sign-in SMS per day from everyone together (the bill's safety net)
 */
@Component
public record SignInLimits(int ipSendsPerHour, int ipv6SendsPerHour, int ipVerifiesPerHour, int ipv6VerifiesPerHour,
                           int smsDailyLimit) {

    public SignInLimits(@Value("${app.auth.ip-sends-per-hour:200}") int ipSendsPerHour,
                        @Value("${app.auth.ipv6-sends-per-hour:20}") int ipv6SendsPerHour,
                        @Value("${app.auth.ip-verifies-per-hour:600}") int ipVerifiesPerHour,
                        @Value("${app.auth.ipv6-verifies-per-hour:60}") int ipv6VerifiesPerHour,
                        @Value("${app.sms.daily-limit:50000}") int smsDailyLimit) {
        this.ipSendsPerHour = ipSendsPerHour;
        this.ipv6SendsPerHour = ipv6SendsPerHour;
        this.ipVerifiesPerHour = ipVerifiesPerHour;
        this.ipv6VerifiesPerHour = ipv6VerifiesPerHour;
        this.smsDailyLimit = smsDailyLimit;
    }

    /** @param networkKey from ClientIp.limitKey: "v6:…" for an IPv6 /64, otherwise an IPv4 address */
    public int sendsPerHour(String networkKey) {
        return isIpv6(networkKey) ? ipv6SendsPerHour : ipSendsPerHour;
    }

    public int verifiesPerHour(String networkKey) {
        return isIpv6(networkKey) ? ipv6VerifiesPerHour : ipVerifiesPerHour;
    }

    /** The count at which the once-a-day "80% used" warning is logged (at least 1). */
    public static long warnAt(int dailyLimit) {
        return Math.max(1, dailyLimit * 8L / 10);
    }

    private static boolean isIpv6(String networkKey) {
        return networkKey.startsWith("v6:");
    }
}
