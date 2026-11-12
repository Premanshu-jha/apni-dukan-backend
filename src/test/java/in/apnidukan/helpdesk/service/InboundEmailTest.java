package in.apnidukan.helpdesk.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InboundEmailTest {

    private static InboundEmail parse(String raw) {
        return InboundEmail.parse(raw.getBytes(StandardCharsets.UTF_8), false, "clozest.in");
    }

    @Test
    void readsAPlainEmail() {
        InboundEmail e = parse("From: \"Asha Rao\" <Asha@Gmail.com>\r\nSubject: =?UTF-8?Q?Booking_=E2=82=B9200?=\r\n"
                + "Message-ID: <m1@gmail.com>\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\nthere");
        assertThat(e.fromAddress()).isEqualTo("asha@gmail.com");
        assertThat(e.fromName()).isEqualTo("Asha Rao");
        assertThat(e.subject()).isEqualTo("Booking ₹200");
        assertThat(e.messageId()).hasSize(64);   // sha-256 of the sender and the Message-ID
        assertThat(parse("From: other@gmail.com\r\nMessage-ID: <m1@gmail.com>\r\n\r\nHi").messageId())
                .isNotEqualTo(e.messageId());   // the same Message-ID from someone else is another email
        assertThat(e.senderVerified()).isFalse();   // no proof from Cloudflare in it
        assertThat(e.text()).isEqualTo("Hello\nthere");
        assertThat(e.automated()).isFalse();
        assertThat(e.ticketNumber()).isNull();
    }

    @Test
    void findsTheTicketNumberInAReply() {
        assertThat(parse("From: a@b.com\r\nSubject: Re: [CLZ-1042] We've got your request\r\n\r\nok").ticketNumber())
                .isEqualTo(1042L);
    }

    @Test
    void prefersThePlainPartAndCountsAttachments() {
        String raw = "From: a@b.com\r\nSubject: x\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"B1\"\r\n\r\n"
                + "--B1\r\nContent-Type: multipart/alternative; boundary=\"B2\"\r\n\r\n"
                + "--B2\r\nContent-Type: text/plain\r\n\r\nPlain words\r\n"
                + "--B2\r\nContent-Type: text/html\r\n\r\n<p>Html words</p>\r\n--B2--\r\n"
                + "--B1\r\nContent-Type: image/png\r\nContent-Disposition: attachment; filename=\"a.png\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\niVBORw0KGgo=\r\n--B1--\r\n";
        InboundEmail e = parse(raw);
        assertThat(e.text()).isEqualTo("Plain words");
        assertThat(e.attachments()).isEqualTo(1);
    }

    @Test
    void turnsHtmlOnlyIntoText() {
        InboundEmail e = parse("From: a@b.com\r\nSubject: x\r\nContent-Type: text/html\r\n\r\n"
                + "<html><head><style>p{}</style></head><body><p>Line one</p><div>Line&nbsp;two &amp; more</div>"
                + "</body></html>");
        assertThat(e.text()).isEqualTo("Line one\nLine two & more");
    }

    @Test
    void cloudflaresDmarcPassOnTopProvesTheSenderAndNothingElseDoes() {
        assertThat(parse("ARC-Authentication-Results: i=1; mx.cloudflare.net; dkim=pass;\r\n dmarc=pass header.from=b.com\r\n"
                + "From: a@b.com\r\n\r\nHi").senderVerified()).isTrue();
        assertThat(parse("Authentication-Results: mx.cloudflare.net; dmarc=pass\r\nFrom: a@b.com\r\n\r\nHi")
                .senderVerified()).isTrue();
        assertThat(parse("ARC-Authentication-Results: i=1; mx.cloudflare.net; dmarc=fail\r\n"
                + "ARC-Authentication-Results: i=1; mx.cloudflare.net; dmarc=pass\r\nFrom: a@b.com\r\n\r\nHi")
                .senderVerified()).isFalse();   // the lower one was written by the sender
        assertThat(parse("Authentication-Results: mx.evil.example; dmarc=pass\r\nFrom: a@b.com\r\n\r\nHi")
                .senderVerified()).isFalse();
    }

    @Test
    void hugeOrBrokenHtmlIsReadQuickly() {
        long started = System.nanoTime();
        String lt = "<".repeat(900_000);
        String heads = "<head>".repeat(150_000);
        InboundEmail.htmlToText(lt);
        InboundEmail.htmlToText(heads);
        InboundEmail.htmlToText("<style>".repeat(150_000));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
        assertThat(InboundEmail.htmlToText("<p>Hi <b>there</b></p><script>x()</script>ok")).isEqualTo("Hi there\nok");
    }

    @Test
    void controlCharactersAndNulAreDropped() {
        InboundEmail e = parse("From: a@b.com\r\nSubject: =?UTF-8?Q?a=00b?=\r\nContent-Type: text/plain\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\nx=00y");
        assertThat(e.subject()).isEqualTo("ab");
        assertThat(e.text()).isEqualTo("xy");
    }

    @Test
    void aVeryLongAddressIsNeverATicket() {
        assertThat(parse("From: " + "a".repeat(250) + "@b.com\r\n\r\nHi").automated()).isTrue();
    }

    @Test
    void cutsTheQuotedConversation() {
        assertThat(InboundEmail.newText("Thanks, fixed now.\n\nOn Thu, 12 Nov 2026 at 14:02, Clozest Support\n"
                + "<support@clozest.in> wrote:\n> Hi Asha,\n> We have refunded it.")).isEqualTo("Thanks, fixed now.");
        assertThat(InboundEmail.newText("Ok\r\n\r\n-----Original Message-----\r\nFrom: x")).isEqualTo("Ok");
        assertThat(InboundEmail.newText("Ok\n> quoted\n> more\n")).isEqualTo("Ok");
        // Their own line starting with "On" is never taken for the quote's header
        assertThat(InboundEmail.newText("Hi,\nOn Monday the salon cancelled.\n\nOn Thu, 12 Nov 2026, A <a@b.com> wrote:\n> x"))
                .isEqualTo("Hi,\nOn Monday the salon cancelled.");
        // Nothing but a quote: kept whole rather than empty
        assertThat(InboundEmail.newText("> only quoted")).isEqualTo("> only quoted");
    }

    @Test
    void autoRepliesBouncesNewslettersAndOurOwnAreAutomated() {
        assertThat(parse("From: a@b.com\r\nAuto-Submitted: auto-replied\r\n\r\nAway").automated()).isTrue();
        assertThat(parse("From: a@b.com\r\nAuto-Submitted: no\r\n\r\nHi").automated()).isFalse();
        assertThat(parse("From: a@b.com\r\nPrecedence: bulk\r\n\r\nSale").automated()).isTrue();
        assertThat(parse("From: a@b.com\r\nList-Id: <news.b.com>\r\n\r\nSale").automated()).isTrue();
        assertThat(parse("From: a@b.com\r\nReturn-Path: <>\r\n\r\nBounce").automated()).isTrue();
        assertThat(parse("From: MAILER-DAEMON@b.com\r\n\r\nBounce").automated()).isTrue();
        assertThat(parse("From: support@clozest.in\r\n\r\nLoop").automated()).isTrue();
        assertThat(parse("From: x@mail.clozest.in\r\n\r\nLoop").automated()).isTrue();
        assertThat(parse("Subject: no sender\r\n\r\nHi").automated()).isTrue();
    }

    @Test
    void aTooLargeEmailKeepsOnlyItsHeadersAndSaysWhereToRead() {
        InboundEmail e = InboundEmail.parse("From: a@b.com\r\nSubject: Photos\r\nMessage-ID: <big@b>\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8), true, "clozest.in");
        assertThat(e.text()).isEqualTo(InboundEmail.TOO_LARGE);
        assertThat(e.subject()).isEqualTo("Photos");
    }

    @Test
    void anEmailWithoutAMessageIdGetsTheSameMadeUpOneEachTime() {
        String raw = "From: a@b.com\r\nSubject: x\r\nDate: Thu, 12 Nov 2026 10:00:00 +0530\r\n\r\nHi";
        assertThat(parse(raw).messageId()).isEqualTo(parse(raw).messageId());
    }

    @Test
    void garbageHasNoSenderSoItIsNeverATicket() {
        assertThat(InboundEmail.parse(new byte[] {0, 1, 2}, false, "clozest.in").automated()).isTrue();
    }
}
