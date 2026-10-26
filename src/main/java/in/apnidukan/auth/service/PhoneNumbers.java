package in.apnidukan.auth.service;

import in.apnidukan.common.error.ApiException;

/** Turns what people type ("+91 98480 12345", "098480-12345") into the 10-digit form we store. */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    public static String normalize(String input) {
        String digits = input == null ? "" : input.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("[6-9]\\d{9}")) {
            throw ApiException.badRequest("INVALID_PHONE", "Enter a 10-digit Indian mobile number");
        }
        return digits;
    }
}
