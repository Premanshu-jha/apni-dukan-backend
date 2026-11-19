package in.apnidukan.kirana.domain;

import java.util.Locale;

/**
 * Why a photo or file couldn't be read, as a code the app words in the shop's language (Hindi, Telugu …), with the
 * English kept in the database. A page of the shop's list is told the same in its own words ("This list …").
 */
public enum ReadFailure {
    /** A product's photo replaced or removed before its check. */
    PHOTO_NOT_WAITING("This photo is no longer waiting for its product."),
    /** The AI isn't set up on this server. */
    READING_OFF("Reading photos isn't switched on yet. Enter it by hand."),
    CANT_OPEN("We couldn't open the photo. Take it again."),
    TOO_LARGE("This photo is too large. Take it again."),
    FILE_PASSWORD("This PDF has a password. Send it without one, or a photo of the bill."),
    FILE_CANT_OPEN("We couldn't open this file. Send the bill as a PDF or a photo."),
    /** Too many pages or too much text at once ("This PDF has 30 pages: send at most …" too). */
    TOO_LONG("This bill is too long to read at once. Send it in parts."),
    /** A product's photo the AI couldn't make out. */
    PRODUCT_UNCLEAR("We couldn't see the product clearly in this photo. Take it again in good light, holding the "
            + "phone steady."),
    NOT_A_BILL("This doesn't look like a distributor's bill. Take a photo of the bill."),
    BILL_UNREADABLE("We couldn't read the bill's items. Take a clear, straight photo of each page in good light."),
    NOT_A_LIST("This doesn't look like a list of products. Take a photo of one page of your list."),
    LIST_UNREADABLE("We couldn't read the products on this page. Take a clear, straight photo of the whole page in "
            + "good light."),
    PAPER_BILL_UNREADABLE("We couldn't read the bill. Take a clear photo of the whole paper in good light."),
    LABEL_UNREADABLE("We couldn't read the pack's label. Take a photo of the front of the pack."),
    PHOTO_UNREADABLE("We couldn't read this photo. Take a clear, straight photo in good light."),
    /** The AI didn't answer after several tries. */
    TRY_LATER("We couldn't read it just now. Send it again a little later, or enter it by hand."),
    /** A reason not listed here (kept from an older version). */
    OTHER("");

    private final String text;

    ReadFailure(String text) {
        this.text = text;
    }

    /** The reason in English, as stored and shown when the app has no words for the code. */
    public String text() {
        return text;
    }

    /** As told for a page of the shop's list. */
    public static String forList(String billWords) {
        return billWords.replace("This bill", "This list").replace("the bill", "your list");
    }

    /** The code of a stored reason (null for none), the list's wording and a long file's page count included. */
    public static String code(String failure) {
        if (failure == null) {
            return null;
        }
        for (ReadFailure f : values()) {
            if (f != OTHER && (f.text.equals(failure) || forList(f.text).equals(failure))) {
                return f.name();
            }
        }
        return failure.toLowerCase(Locale.ROOT).startsWith("this pdf has ") && failure.contains(" pages: send at most")
                ? TOO_LONG.name() : OTHER.name();
    }
}
