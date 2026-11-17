package in.apnidukan.kirana.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A regular customer in the shop's khata book (kirana_khata_customers). The shop's own record: no Clozest money.
 * @param phone             optional, for the WhatsApp reminder; null if not given
 * @param note              optional, e.g. the flat
 * @param creditLimitPaise  the most they may owe; null: no limit
 * @param balancePaise      what they owe now (below 0: the shop owes them, after a paid sale was cancelled)
 */
public record KhataCustomer(UUID id, UUID shopId, String name, String phone, String note, Long creditLimitPaise,
                            long balancePaise, boolean active, long version, Instant updatedAt) {
}
