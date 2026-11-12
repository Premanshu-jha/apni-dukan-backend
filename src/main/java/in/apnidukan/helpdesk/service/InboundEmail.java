package in.apnidukan.helpdesk.service;

import jakarta.mail.BodyPart;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A support email as the Email Worker hands it over (the raw message, or only its headers when it was over 1 MB),
 * read into what a ticket needs.
 * @param fromAddress the sender's address, lower case
 * @param fromName    the sender's display name ("" if none)
 * @param messageId   sha-256 (hex) of the sender and the email's Message-ID (made from the other fields when missing,
 *                    so a retry is still one email; with the sender in it, nobody can block another's email by reusing
 *                    its Message-ID)
 * @param text        the new text only (quoted earlier messages cut off), at most {@link #MAX_TEXT} characters
 * @param attachments how many files it carried (they stay in the Gmail copy)
 * @param automated   an auto-reply, bounce, newsletter or our own email: never a ticket, never answered
 * @param ticketNumber the CLZ number in the subject (a reply to one of our emails), or null
 * @param senderVerified Cloudflare checked the From address (DMARC pass, in the topmost ARC-Authentication-Results or
 *                    Authentication-Results it added); false means the From may be forged
 */
public record InboundEmail(String fromAddress, String fromName, String subject, String messageId, String text,
                           int attachments, boolean automated, Long ticketNumber, boolean senderVerified) {

    /** The longest text kept from one email (the column allows 20,000). */
    public static final int MAX_TEXT = 10_000;
    /** Shown instead of the text of an email too large to bring in (the worker sent its headers only). */
    static final String TOO_LARGE = "(This email was too large to bring in here. Read it in the support Gmail inbox.)";
    private static final Pattern TICKET = Pattern.compile("\\[CLZ-(\\d{1,12})]");
    /**
     * "On Thu, 12 Nov 2026 at 14:02, Asha <asha@x.com> wrote:" (Gmail, Apple Mail; may wrap onto a second line). Only
     * a line naming an address (an @) counts, so the person's own "On Monday the salon…" is never cut.
     */
    private static final Pattern WROTE = Pattern.compile("(?m)^On\\s[^\\n]{0,200}(\\n[^\\n]{0,200})?wrote:[ \\t]*$");
    /** HTML longer than this is cut before it is turned into text (the text kept is at most MAX_TEXT anyway). */
    static final int MAX_HTML = 200_000;
    /** The longest address an email column takes. */
    static final int MAX_ADDRESS = 254;
    /** Who adds the authentication results we trust: Cloudflare's inbound mail servers. */
    static final String OUR_MX = "mx.cloudflare.net";
    private static final Pattern OUTLOOK = Pattern.compile("(?m)^(-{2,}\\s*Original Message\\s*-{2,}|_{10,})\\s*$");
    private static final Set<String> ROBOTS = Set.of("mailer-daemon", "postmaster", "no-reply", "noreply",
            "do-not-reply", "donotreply", "bounce", "bounces");

    /**
     * Reads the raw email.
     * @param truncated true when the worker sent only the headers (the email was over 1 MB)
     * @param ourDomain our own domain (clozest.in): an email from it is ours coming back (a loop), never a ticket
     */
    public static InboundEmail parse(byte[] raw, boolean truncated, String ourDomain) {
        try {
            MimeMessage m = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            InternetAddress from = sender(m);
            String address = from == null ? "" : from.getAddress().strip().toLowerCase(Locale.ROOT);
            String name = from == null || from.getPersonal() == null ? "" : clean(from.getPersonal()).strip();
            String subject = m.getSubject() == null ? "" : clean(m.getSubject()).replaceAll("\\s+", " ").strip();
            String id = m.getMessageID();
            int[] files = {0};
            String body = truncated ? "" : body(m, files);
            // Only the start can be kept anyway: cut before looking for the quoted part
            String text = truncated ? TOO_LARGE : newText(clean(cut(body, MAX_TEXT * 2)));
            if (text.isBlank()) {
                text = files[0] > 0 ? "(No text: only attachments, see the support Gmail inbox.)" : "(No text.)";
            }
            if (id == null || id.isBlank()) {
                id = "made:" + subject + "|" + text + "|" + m.getHeader("Date", null);
            }
            Matcher n = TICKET.matcher(subject);
            Long number = n.find() ? Long.valueOf(n.group(1)) : null;
            boolean robot = automated(m, address, ourDomain) || address.length() > MAX_ADDRESS;
            return new InboundEmail(address, cut(name, 100), cut(subject, 200), sha256(address + "|" + id.strip()),
                    cut(text, MAX_TEXT), files[0], robot, number, !truncated && verified(m));
        } catch (MessagingException | IOException | RuntimeException e) {
            throw new IllegalArgumentException("Not a readable email: " + e.getClass().getSimpleName(), e);
        }
    }

    private static InternetAddress sender(MimeMessage m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from == null || from.length == 0 || !(from[0] instanceof InternetAddress a) || a.getAddress() == null
                || !a.getAddress().contains("@")) {
            return null;
        }
        return a;
    }

    /**
     * An auto-reply (Auto-Submitted other than "no", RFC 3834), a mass email (Precedence bulk/junk/list, List-Id),
     * a bounce (empty Return-Path, mailer-daemon), a no-reply sender, or our own address coming back.
     */
    static boolean automated(MimeMessage m, String address, String ourDomain) throws MessagingException {
        String auto = m.getHeader("Auto-Submitted", null);
        if (auto != null && !auto.strip().equalsIgnoreCase("no")) {
            return true;
        }
        String precedence = m.getHeader("Precedence", null);
        if (precedence != null && Set.of("bulk", "junk", "list", "auto_reply")
                .contains(precedence.strip().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (m.getHeader("List-Id", null) != null || m.getHeader("X-Autoreply", null) != null
                || m.getHeader("X-Autorespond", null) != null) {
            return true;
        }
        String returnPath = m.getHeader("Return-Path", null);
        if (returnPath != null && returnPath.replaceAll("\\s", "").equals("<>")) {
            return true;
        }
        if (address.isEmpty()) {
            return true;
        }
        String local = address.substring(0, address.indexOf('@'));
        String domain = address.substring(address.indexOf('@') + 1);
        return ROBOTS.contains(local) || domain.equals(ourDomain) || domain.endsWith("." + ourDomain);
    }

    /** The plain text of the email (its HTML turned into text when there is no plain part); counts the files. */
    private static String body(Part part, int[] files) throws MessagingException, IOException {
        String disposition = part.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (part.getFileName() != null && !part.isMimeType("multipart/*"))) {
            files[0]++;
            return "";
        }
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("text/html")) {
            return htmlToText(String.valueOf(part.getContent()));
        }
        if (part.isMimeType("multipart/alternative")) {
            Multipart mp = (Multipart) part.getContent();
            String html = "";
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart p = mp.getBodyPart(i);
                if (p.isMimeType("text/plain")) {
                    return String.valueOf(p.getContent());
                }
                if (html.isEmpty()) {
                    html = body(p, files);
                }
            }
            return html;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                String t = body(mp.getBodyPart(i), files);
                if (!t.isBlank()) {
                    out.append(out.isEmpty() ? "" : "\n\n").append(t);
                }
            }
            return out.toString();
        }
        if (part.isMimeType("message/rfc822")) {
            files[0]++;   // a forwarded email as a file
            return "";
        }
        return "";
    }

    /**
     * Good enough for reading: line breaks for blocks, tags dropped, script/style/head blocks dropped, the common
     * entities decoded. One pass over the text (at most MAX_HTML characters), so no email can make it slow.
     */
    static String htmlToText(String input) {
        String html = cut(input, MAX_HTML);
        StringBuilder out = new StringBuilder(Math.min(html.length(), MAX_TEXT * 2));
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int end = html.indexOf('>', i);
            if (end < 0) {
                break;   // an unclosed tag: the rest is markup
            }
            String tag = html.substring(i + 1, end).strip().toLowerCase(Locale.ROOT);
            String tagName = tag.split("[\\s/]", 2)[0];
            if (tagName.equals("script") || tagName.equals("style") || tagName.equals("head")) {
                int close = indexOfIgnoreCase(html, "</" + tagName, end);
                int closeEnd = close < 0 ? -1 : html.indexOf('>', close);
                i = closeEnd < 0 ? html.length() : closeEnd + 1;
                continue;
            }
            if (tagName.equals("br") || tag.startsWith("/p") || tag.startsWith("/div") || tag.startsWith("/li")
                    || tag.startsWith("/tr") || tag.startsWith("/blockquote") || tag.matches("/h[1-6]")) {
                out.append('\n');
            }
            i = end + 1;
        }
        return out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /**
     * Whether Cloudflare proved the From address: the topmost results header of the whole email (of either name;
     * headers added on the way in sit above everything the sender wrote) is Cloudflare's and says dmarc=pass. Any
     * results header below it, whatever it claims, was written by the sender. An email cut to its headers (over
     * 1 MB) is never proven: the worker can't keep their order.
     */
    static boolean verified(MimeMessage m) throws MessagingException {
        java.util.Enumeration<jakarta.mail.Header> all = m.getAllHeaders();
        while (all.hasMoreElements()) {
            jakarta.mail.Header h = all.nextElement();
            String name = h.getName();
            if (name.equalsIgnoreCase("ARC-Authentication-Results") || name.equalsIgnoreCase("Authentication-Results")) {
                String top = h.getValue().replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
                return top.matches("^(i=\\d+; ?)?" + Pattern.quote(OUR_MX) + ";.*")
                        && top.matches(".*\\bdmarc=pass\\b.*");
            }
        }
        return false;
    }

    /** indexOf, ASCII case ignored (positions stay those of the text itself). One pass. */
    private static int indexOfIgnoreCase(String text, String what, int from) {
        for (int i = from; i <= text.length() - what.length(); i++) {
            if (text.regionMatches(true, i, what, 0, what.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Drops what can't be stored or shown: NUL and other control and invisible characters (line breaks and tabs stay). */
    static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        // Zero-width joiners stay: Indian scripts and emoji need them
        s.codePoints().filter(c -> c == '\n' || c == '\t' || c == '\r' || c == 0x200C || c == 0x200D || !hidden(c))
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static boolean hidden(int c) {
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                || type == Character.SURROGATE || type == Character.UNASSIGNED;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Only what the person wrote this time: the quoted earlier conversation ("On ... wrote:" and below, an Outlook
     * "Original Message", trailing "&gt;" lines) is cut off; line endings made \n; at most two empty lines in a row.
     * If cutting would leave nothing, the whole text is kept.
     */
    static String newText(String body) {
        String t = body.replace("\r\n", "\n").replace('\r', '\n');
        int cut = t.length();
        Matcher w = WROTE.matcher(t);
        while (w.find()) {
            if (w.group().contains("@")) {
                cut = Math.min(cut, w.start());
                break;
            }
        }
        Matcher o = OUTLOOK.matcher(t);
        if (o.find()) {
            cut = Math.min(cut, o.start());
        }
        String kept = t.substring(0, cut);
        String[] lines = kept.split("\n", -1);
        int end = lines.length;
        while (end > 0 && (lines[end - 1].isBlank() || lines[end - 1].startsWith(">"))) {
            end--;
        }
        kept = String.join("\n", java.util.Arrays.copyOf(lines, end)).strip();
        if (kept.isEmpty()) {
            kept = t.strip();
        }
        return kept.replaceAll("\n{4,}", "\n\n\n");
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
