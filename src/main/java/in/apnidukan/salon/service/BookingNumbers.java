package in.apnidukan.salon.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Booking numbers: "S" and 7 random characters, without 0/O and 1/I/L, so they can be read out over the phone
 * (31^7, about 27 billion). The database's unique key has the last word: a number already taken is replaced by the
 * next one from here (AppointmentStore.insertBookingIfFree).
 */
@Component
public class BookingNumbers {

    private static final String CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("S");
        for (int i = 0; i < 7; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
