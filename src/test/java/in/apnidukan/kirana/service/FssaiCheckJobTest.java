package in.apnidukan.kirana.service;

import in.apnidukan.common.context.ShopDirectory;
import in.apnidukan.common.context.ShopMemberships;
import in.apnidukan.common.context.UserDirectory;
import in.apnidukan.kirana.repo.OnlineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rules an FSSAI certificate read by the AI must pass before the shop is verified. */
class FssaiCheckJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final OnlineStore.Checking CHECKING = new OnlineStore.Checking(SHOP, "13624013000111",
            UUID.randomUUID(), 0);

    private FssaiCheckJob job;

    @BeforeEach
    void setUp() {
        ShopDirectory shops = mock(ShopDirectory.class);
        when(shops.find(SHOP)).thenReturn(Optional.of(new ShopDirectory.ShopCard(SHOP, "Sharma General Store", null,
                null, null, null, null, null, "APPROVED", "KIRANA", null, null, null)));
        ShopMemberships members = mock(ShopMemberships.class);
        when(members.members(SHOP)).thenReturn(List.of(new ShopMemberships.Member(OWNER, "OWNER")));
        UserDirectory users = mock(UserDirectory.class);
        when(users.find(any())).thenReturn(Map.of(OWNER, new UserDirectory.Person(OWNER, "Ramesh Kumar", "9848000000")));
        job = new FssaiCheckJob(null, null, null, shops, members, users, null, null, null, null, 500);
    }

    private static KiranaDocumentAi.FssaiFacts facts(boolean readable, boolean fssai, String number, String business,
                                                     String operator, LocalDate until) {
        return new KiranaDocumentAi.FssaiFacts(readable, fssai, number, business, operator, null, until);
    }

    @Test
    void aClearCertificateOfThisShopWithItsNumberIsAccepted() {
        assertThat(job.problem(CHECKING, facts(true, true, "1362 4013 0001 11", "M/s Sharma Provision Stores", null,
                TODAY.plusYears(2)), TODAY)).isNull();
        // the owner's name is enough when the business name differs
        assertThat(job.problem(CHECKING, facts(true, true, "13624013000111", "RK Traders", "Ramesh Kumar",
                TODAY.plusYears(1)), TODAY)).isNull();
    }

    @Test
    void unreadableOtherDocumentsWrongNumbersExpiredLicencesAndOtherShopsAreRefused() {
        assertThat(job.problem(CHECKING, facts(false, false, null, null, null, null), TODAY))
                .startsWith("We couldn't read");
        assertThat(job.problem(CHECKING, facts(true, false, null, null, null, null), TODAY))
                .startsWith("This isn't an FSSAI");
        assertThat(job.problem(CHECKING, facts(true, true, null, "Sharma Stores", null, null), TODAY))
                .startsWith("We couldn't find the FSSAI number");
        assertThat(job.problem(CHECKING, facts(true, true, "13624013000999", "Sharma Stores", null, null), TODAY))
                .contains("13624013000999").contains("Check the number");
        assertThat(job.problem(CHECKING, facts(true, true, "13624013000111", "Sharma Stores", null,
                TODAY.minusDays(1)), TODAY)).startsWith("This licence expired");
        // no last day readable: a licence is never verified without one
        assertThat(job.problem(CHECKING, facts(true, true, "13624013000111", "Sharma Stores", null, null), TODAY))
                .startsWith("We couldn't read the date");
        // only the usual shop words in common: not a match
        assertThat(job.problem(CHECKING, facts(true, true, "13624013000111", "Gupta General Store", "Anil Gupta",
                TODAY.plusYears(1)), TODAY)).contains("doesn't match");
    }

    @Test
    void namesAreComparedByTheWordsThatTellThemApart() {
        assertThat(FssaiCheckJob.words("M/s. Sri Lakshmi Kirana & General Stores")).containsExactlyInAnyOrder(
                "lakshmi");
    }
}
