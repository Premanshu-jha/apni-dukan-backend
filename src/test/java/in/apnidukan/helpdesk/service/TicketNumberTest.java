package in.apnidukan.helpdesk.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TicketNumberTest {

    @Test
    void readsTheNumberHoweverItIsTypedOrPasted() {
        assertThat(TicketService.ticketNumber("1042")).isEqualTo(1042L);
        assertThat(TicketService.ticketNumber("CLZ-1042")).isEqualTo(1042L);
        assertThat(TicketService.ticketNumber("clz 1042")).isEqualTo(1042L);
        assertThat(TicketService.ticketNumber("CLZ - 1042")).isEqualTo(1042L);
        assertThat(TicketService.ticketNumber("CLZ-01002")).isEqualTo(1002L);
        assertThat(TicketService.ticketNumber("Re: [CLZ-1042] My booking")).isEqualTo(1042L);
    }

    @Test
    void anythingElseIsNoNumber() {
        assertThat(TicketService.ticketNumber("xCLZ-1042")).isNull();
        assertThat(TicketService.ticketNumber("CLZ_1042")).isNull();
        assertThat(TicketService.ticketNumber("CLZ-1042x")).isNull();
        assertThat(TicketService.ticketNumber("CLZ-1234567890")).isNull();
        assertThat(TicketService.ticketNumber("9848012345")).isNull();   // a phone number, searched as one
        assertThat(TicketService.ticketNumber("Asha")).isNull();
        assertThat(TicketService.ticketNumber("")).isNull();
    }

    @Test
    void aLongRunOfSpacesIsOnePass() {
        long start = System.nanoTime();
        assertThat(TicketService.ticketNumber("CLZ" + " ".repeat(8000) + "x")).isNull();
        assertThat(System.nanoTime() - start).isLessThan(200_000_000L);
    }
}
