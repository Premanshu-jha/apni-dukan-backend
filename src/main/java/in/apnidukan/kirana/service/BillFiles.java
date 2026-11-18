package in.apnidukan.kirana.service;

import in.apnidukan.kirana.service.KiranaDocumentAi.BillLine;
import in.apnidukan.kirana.service.KiranaDocumentAi.Page;
import in.apnidukan.kirana.service.KiranaDocumentAi.PurchaseFacts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * A bill's files made ready for reading, in code, before any AI: whatever channel a distributor's bill comes by (a
 * photo, a PDF, an Excel or Word file, a CSV, a GST e-invoice's JSON, text), it becomes either its facts read here with
 * no AI at all (a spreadsheet or CSV whose columns are found, an e-invoice), or pages for the AI: photos and scanned
 * PDFs as they are, everything else as text (far fewer tokens than pictures).
 */
@Component
class BillFiles {

    /** Pages one PDF may have. */
    static final int MAX_PDF_PAGES = 20;
    /** Letters of text all of a bill's files may have together. */
    static final int MAX_TEXT = 120_000;
    /** Rows and columns read from a spreadsheet's sheet. */
    private static final int MAX_ROWS = 3000;
    private static final int MAX_COLUMNS = 40;
    private static final int MAX_SHEETS = 5;

    /**
     * What the files became: {@code facts} (read here; no AI needed), else {@code pages} for the AI; {@code problem}
     * when they can't be read at all (in words for the shop).
     */
    record Prepared(PurchaseFacts facts, List<Page> pages, String problem) {

        Prepared {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }

        static Prepared problem(String why) {
            return new Prepared(null, List.of(), why);
        }
    }

    /** The files of a read of this kind (PURCHASE or PAPER_BILL), in order. */
    Prepared prepare(String kind, List<Page> files) {
        boolean purchase = "PURCHASE".equals(kind);
        List<Page> pages = new ArrayList<>();
        int text = 0;
        for (Page f : files) {
            String type = f.contentType();
            Page page;
            try {
                if (type.startsWith("image/")) {
                    page = f;
                } else if ("application/pdf".equals(type)) {
                    page = pdf(f.bytes());
                } else if (type.contains("spreadsheetml") || "application/vnd.ms-excel".equals(type)) {
                    List<List<List<String>>> sheets = sheets(f.bytes());
                    if (purchase && files.size() == 1) {
                        for (List<List<String>> rows : sheets) {
                            PurchaseFacts facts = BillTables.facts(rows);
                            if (facts != null) {
                                return new Prepared(facts, List.of(), null);
                            }
                        }
                    }
                    page = textPage(tabbed(sheets));
                } else if (type.contains("wordprocessingml")) {
                    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(f.bytes()));
                         XWPFWordExtractor words = new XWPFWordExtractor(doc)) {
                        page = textPage(words.getText());
                    }
                } else if ("application/json".equals(type)) {
                    String json = new String(f.bytes(), StandardCharsets.UTF_8);
                    if (purchase && files.size() == 1) {
                        PurchaseFacts facts = EInvoices.facts(json);
                        if (facts != null) {
                            return new Prepared(facts, List.of(), null);
                        }
                    }
                    page = textPage(json);
                } else if ("text/csv".equals(type)) {
                    String csv = new String(f.bytes(), StandardCharsets.UTF_8);
                    List<List<String>> rows = CatalogueAdminService.parse(csv.indexOf('\t') >= 0
                            && csv.indexOf(',') < 0 ? csv.replace('\t', ',') : csv);
                    if (purchase && files.size() == 1) {
                        PurchaseFacts facts = BillTables.facts(rows);
                        if (facts != null) {
                            return new Prepared(facts, List.of(), null);
                        }
                    }
                    page = textPage(tabbed(List.of(rows)));
                } else {
                    page = textPage(new String(f.bytes(), StandardCharsets.UTF_8));
                }
            } catch (InvalidPasswordException e) {
                return Prepared.problem("This PDF has a password. Send it without one, or a photo of the bill.");
            } catch (TooLong e) {
                return Prepared.problem(e.getMessage());
            } catch (IOException | RuntimeException e) {
                return Prepared.problem("We couldn't open this file. Send the bill as a PDF or a photo.");
            }
            if (page.contentType().startsWith("text/")) {
                text += page.bytes().length;
                if (text > MAX_TEXT * 3) {
                    return Prepared.problem("This bill is too long to read at once. Send it in parts.");
                }
            }
            pages.add(page);
        }
        return new Prepared(null, pages, null);
    }

    /** A PDF's text when it has text in it (made by billing software), else the PDF itself (a scan) for the AI. */
    private static Page pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pages = doc.getNumberOfPages();
            if (pages > MAX_PDF_PAGES) {
                throw new TooLong("This PDF has " + pages + " pages: send at most " + MAX_PDF_PAGES + " at a time.");
            }
            String text = new PDFTextStripper().getText(doc);
            long letters = text.chars().filter(Character::isLetterOrDigit).count();
            if (letters >= 40L * Math.max(1, pages)) {
                return textPage(text);
            }
        }
        return new Page(bytes, "application/pdf");
    }

    private static Page textPage(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        if (t.length() > MAX_TEXT) {
            throw new TooLong("This bill is too long to read at once. Send it in parts.");
        }
        return new Page(t.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /** Each sheet's rows (cells as shown in Excel), at most 5 sheets, 3,000 rows, 40 columns. */
    private static List<List<List<String>>> sheets(byte[] bytes) throws IOException {
        List<List<List<String>>> out = new ArrayList<>();
        DataFormatter shown = new DataFormatter(Locale.ENGLISH);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < Math.min(MAX_SHEETS, book.getNumberOfSheets()); s++) {
                Sheet sheet = book.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (rows.size() >= MAX_ROWS) {
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(MAX_COLUMNS, Math.max(0, row.getLastCellNum()));
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : shown.formatCellValue(cell).strip());
                    }
                    if (cells.stream().anyMatch(x -> !x.isEmpty())) {
                        rows.add(cells);
                    }
                }
                out.add(rows);
            }
        }
        return out;
    }

    private static String tabbed(List<List<List<String>>> sheets) {
        StringBuilder s = new StringBuilder();
        for (List<List<String>> rows : sheets) {
            for (List<String> row : rows) {
                s.append(String.join("\t", row)).append('\n');
            }
            s.append('\n');
        }
        return s.toString();
    }

    /** Too long or too many pages to read. */
    private static final class TooLong extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooLong(String why) {
            super(why);
        }
    }

    // ---------- a bill's table found in code ----------

    /** The item table of a spreadsheet or CSV bill, by its column names; null when it can't be found. */
    static final class BillTables {

        private static final Pattern NAME = Pattern.compile(
                "^(item|items|item name|item description|description|product|product name|particulars|"
                        + "description of goods|goods|name|sku name|material)( name)?$");
        private static final Pattern QTY = Pattern.compile("^(qty|quantity|qnty|pcs|nos|units|billed qty|"
                + "bill qty|sale qty|qty\\.?\\s*\\(pcs\\)|quantity \\(pcs\\))$");
        private static final Pattern FREE = Pattern.compile("^(free|free qty|sch qty|scheme qty|fr qty|free pcs)$");
        private static final Pattern RATE = Pattern.compile("^(rate|price|unit price|net rate|ptr|pts|rate/unit|"
                + "rate per unit|basic rate|purchase rate|landing rate)$");
        private static final Pattern AMOUNT = Pattern.compile("^(amount|amt|value|net amount|total|net value|"
                + "line total|taxable value|total amount)$");
        private static final Pattern MRP = Pattern.compile("^(mrp|m\\.r\\.p\\.?|mrp/unit)$");
        private static final Pattern BILL_NO = Pattern.compile("(invoice|bill|inv)\\s*(no|number|#)\\.?\\s*:?\\s*(.*)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern DATE = Pattern.compile("(invoice date|bill date|inv date|date)\\s*:?\\s*(.*)",
                Pattern.CASE_INSENSITIVE);

        private BillTables() {
        }

        static PurchaseFacts facts(List<List<String>> rows) {
            int header = -1;
            int name = -1;
            int qty = -1;
            int free = -1;
            int rate = -1;
            int amount = -1;
            int mrp = -1;
            for (int r = 0; r < Math.min(40, rows.size()) && header < 0; r++) {
                List<String> row = rows.get(r);
                int n = -1;
                int q = -1;
                for (int c = 0; c < row.size(); c++) {
                    String h = label(row.get(c));
                    if (n < 0 && NAME.matcher(h).matches()) {
                        n = c;
                    } else if (q < 0 && QTY.matcher(h).matches()) {
                        q = c;
                    }
                }
                if (n >= 0 && q >= 0) {
                    header = r;
                    name = n;
                    qty = q;
                    for (int c = 0; c < row.size(); c++) {
                        String h = label(row.get(c));
                        if (free < 0 && FREE.matcher(h).matches()) {
                            free = c;
                        } else if (rate < 0 && RATE.matcher(h).matches()) {
                            rate = c;
                        } else if (amount < 0 && AMOUNT.matcher(h).matches()) {
                            amount = c;
                        } else if (mrp < 0 && MRP.matcher(h).matches()) {
                            mrp = c;
                        }
                    }
                }
            }
            if (header < 0) {
                return null;
            }
            String distributor = null;
            String billNo = null;
            LocalDate billDate = null;
            for (int r = 0; r < header; r++) {
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String cell = row.get(c);
                    if (cell.isEmpty()) {
                        continue;
                    }
                    String next = c + 1 < row.size() ? row.get(c + 1) : "";
                    Matcher no = BILL_NO.matcher(cell);
                    Matcher day = DATE.matcher(cell);
                    if (billNo == null && no.matches()) {
                        String v = no.group(3).isBlank() ? next : no.group(3);
                        billNo = v.isBlank() ? null : cut(v.strip(), 40);
                    } else if (billDate == null && day.matches()) {
                        billDate = date(day.group(2).isBlank() ? next : day.group(2));
                    } else if (distributor == null && r < 3 && cell.chars().anyMatch(Character::isLetter)
                            && !cell.contains(":")) {
                        distributor = cut(cell, 100);
                    }
                }
            }
            List<BillLine> lines = new ArrayList<>();
            for (int r = header + 1; r < rows.size() && lines.size() < 200; r++) {
                List<String> row = rows.get(r);
                String wording = cell(row, name);
                if (wording.isEmpty()) {
                    continue;
                }
                String w = wording.toLowerCase(Locale.ROOT);
                if (w.startsWith("total") || w.startsWith("grand total") || w.startsWith("sub total")) {
                    break;
                }
                BigDecimal q = number(cell(row, qty));
                if (q == null || q.signum() <= 0) {
                    continue;
                }
                BigDecimal f = number(cell(row, free));
                if (f != null && f.signum() > 0) {
                    q = q.add(f);   // free pieces came too
                }
                if (q.compareTo(new BigDecimal("99999")) > 0) {
                    continue;
                }
                lines.add(new BillLine(cut(wording, 150), null, null, null, q.stripTrailingZeros(),
                        paise(cell(row, rate)), paise(cell(row, mrp)), paise(cell(row, amount))));
            }
            if (lines.isEmpty()) {
                return null;
            }
            return new PurchaseFacts(true, true, distributor, billNo, billDate, null, lines);
        }

        private static String label(String s) {
            return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", " ").replaceAll("[.:]+$", "").strip();
        }

        private static String cell(List<String> row, int c) {
            return c < 0 || c >= row.size() ? "" : row.get(c).strip();
        }

        static BigDecimal number(String s) {
            String digits = s.replace(",", "").replace("₹", "").replaceAll("(?i)rs\\.?", "").strip();
            if (digits.isEmpty()) {
                return null;
            }
            Matcher m = Pattern.compile("^-?\\d+(\\.\\d+)?").matcher(digits);
            return m.find() ? new BigDecimal(m.group()) : null;
        }

        private static Long paise(String s) {
            BigDecimal r = number(s);
            if (r == null || r.signum() < 0 || r.compareTo(new BigDecimal("10000000")) > 0) {
                return null;
            }
            return r.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }

        static LocalDate date(String s) {
            String v = s.strip();
            for (String f : List.of("dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "d-M-yyyy", "dd.MM.yyyy", "yyyy-MM-dd",
                    "dd/MM/yy", "dd-MM-yy", "dd-MMM-yyyy", "d-MMM-yyyy", "dd MMM yyyy", "d MMM yyyy")) {
                try {
                    return LocalDate.parse(v, DateTimeFormatter.ofPattern(f, Locale.ENGLISH));
                } catch (DateTimeParseException e) {
                    // the next way of writing it
                }
            }
            return null;
        }

        private static String cut(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max);
        }
    }

    // ---------- GST e-invoices ----------

    /** A GST e-invoice (the IRP's JSON, signed or not): its seller, number, date and item list. */
    static final class EInvoices {

        private EInvoices() {
        }

        static PurchaseFacts facts(String json) {
            JsonNode root;
            try {
                root = JSON.readTree(json);
            } catch (RuntimeException e) {
                return null;
            }
            if (root.isArray() && !root.isEmpty()) {
                root = root.get(0);
            }
            JsonNode inv = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            if (inv.has("SignedInvoice") && inv.get("SignedInvoice").isString()) {
                inv = signed(inv.get("SignedInvoice").asString());
                if (inv == null) {
                    return null;
                }
            }
            JsonNode items = inv.path("ItemList");
            if (!items.isArray() || items.isEmpty()) {
                return null;
            }
            List<BillLine> lines = new ArrayList<>();
            for (JsonNode it : items) {
                if (lines.size() >= 200) {
                    break;
                }
                String desc = it.path("PrdDesc").asString("").strip();
                BigDecimal q = decimal(it.path("Qty"));
                BigDecimal free = decimal(it.path("FreeQty"));
                if (desc.isEmpty() || q == null || q.signum() <= 0) {
                    continue;
                }
                if (free != null && free.signum() > 0) {
                    q = q.add(free);
                }
                lines.add(new BillLine(desc.length() > 150 ? desc.substring(0, 150) : desc, null, null, null,
                        q.stripTrailingZeros(), KiranaDocumentAi.paise(it.path("UnitPrice")), null,
                        KiranaDocumentAi.paise(it.path("TotItemVal"))));
            }
            if (lines.isEmpty()) {
                return null;
            }
            JsonNode seller = inv.path("SellerDtls");
            String name = seller.path("TrdNm").asString("").isBlank() ? seller.path("LglNm").asString("")
                    : seller.path("TrdNm").asString("");
            String no = inv.path("DocDtls").path("No").asString("");
            LocalDate day = BillTables.date(inv.path("DocDtls").path("Dt").asString(""));
            return new PurchaseFacts(true, true, name.isBlank() ? null : cut(name, 100),
                    no.isBlank() ? null : cut(no, 40), day, KiranaDocumentAi.paise(inv.path("ValDtls")
                    .path("TotInvVal")), lines);
        }

        /** The invoice inside a signed e-invoice (a JWT whose payload's "data" is the invoice's JSON). */
        private static JsonNode signed(String jwt) {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            try {
                JsonNode payload = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                        StandardCharsets.UTF_8));
                JsonNode data = payload.path("data");
                return data.isString() ? JSON.readTree(data.asString()) : data.isObject() ? data : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static BigDecimal decimal(JsonNode v) {
            return v.isNumber() ? v.decimalValue() : null;
        }

        private static String cut(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max);
        }
    }
}
