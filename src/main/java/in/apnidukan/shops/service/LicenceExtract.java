package in.apnidukan.shops.service;

import java.time.LocalDate;

/**
 * What the AI read from an uploaded trade licence. Only facts read off the page; nothing here decides by itself
 * (LicenceRules does, in code). Text fields are null when not on the document.
 *
 * @param readable          the page could be read at all
 * @param tradeLicence      it is a municipal trade licence (not another certificate, a receipt or a random photo)
 * @param looksEdited       signs of editing (text pasted over, mismatched fonts around the number, name or dates)
 * @param provisional       marked provisional (issued on payment, before the inspection)
 * @param salonTrade        the trade it allows is a salon, barber shop, beauty parlour or hair dressing
 * @param issuingBody       e.g. "Greater Hyderabad Municipal Corporation"
 * @param licenceNumber     the licence (TIN) number as printed
 * @param tradeName         the business name (title of trade)
 * @param ownerName         the licence holder's name
 * @param address           the premises address as printed
 * @param pincode           the 6-digit pincode in that address
 * @param tradeCategory     the trade as printed
 * @param validUntil        the last day it is valid ("valid up to", or the end of its licence year)
 */
public record LicenceExtract(boolean readable, boolean tradeLicence, boolean looksEdited, boolean provisional,
                             boolean salonTrade, String issuingBody, String licenceNumber, String tradeName,
                             String ownerName, String address, String pincode, String tradeCategory,
                             LocalDate validUntil) {
}
