package in.apnidukan.shops.service;

import java.util.Optional;

/**
 * The state (or union territory) of an Indian PIN code, as Google Play names it for tax (the administrativeArea a
 * reported Razorpay payment must carry, see GooglePlay.reportExternal). India Post gives each state its own ranges of
 * the first three digits; the few that cross a state line are split here by their own three digits. The salon's PIN
 * code is the one checked by the field team at registration.
 */
final class PincodeStates {

    private PincodeStates() {
    }

    /** The state for a 6-digit PIN code, or empty for one that isn't (or a range India Post doesn't use). */
    static Optional<String> of(String pincode) {
        if (pincode == null || !pincode.matches("[1-9][0-9]{5}")) {
            return Optional.empty();
        }
        int full = Integer.parseInt(pincode);
        return Optional.ofNullable(split(full)).or(() -> Optional.ofNullable(state(full / 1000)));
    }

    /**
     * The three-digit areas that cross a state line, split by the full PIN code (India Post's post offices on each
     * side); null for any other.
     */
    private static String split(int full) {
        int p = full / 1000;
        return switch (p) {
            // Moradabad, Rampur (UP); Kashipur, Jaspur, Ramnagar (Uttarakhand: 2447xx)
            case 244 -> full / 100 == 2447 ? "UTTARAKHAND" : "UTTAR PRADESH";
            // Pauri, Chamoli, Rudraprayag, Kotdwar (Uttarakhand); Bijnor and around (UP: 2465xx and up)
            case 246 -> full < 246500 ? "UTTARAKHAND" : "UTTAR PRADESH";
            // Saharanpur (UP); Roorkee, Laksar (Uttarakhand: 2476xx)
            case 247 -> full / 100 == 2476 ? "UTTARAKHAND" : "UTTAR PRADESH";
            // Pilibhit, Lakhimpur (UP); Khatima, Sitarganj, Champawat, Pithoragarh (Uttarakhand: 2623xx to 2625xx)
            case 262 -> full >= 262300 && full < 262600 ? "UTTARAKHAND" : "UTTAR PRADESH";
            // Chandigarh; Mohali's sectors share 160 (Punjab: 160055 to 160099); Manimajra (160101) is Chandigarh's
            case 160 -> full >= 160055 && full <= 160099 ? "PUNJAB" : "CHANDIGARH";
            // Puducherry's enclaves: Karaikal (6096xx, in Tamil Nadu's 609), Mahe (673310, in Kerala's 673),
            // Yanam (533464, in Andhra Pradesh's 533)
            case 609 -> full / 100 == 6096 ? "PUDUCHERRY" : null;
            case 673 -> full == 673310 ? "PUDUCHERRY" : null;
            case 533 -> full == 533464 ? "PUDUCHERRY" : null;
            // Valsad, Vapi, Navsari (Gujarat); Daman, Silvassa (3962xx)
            case 396 -> full / 100 == 3962 ? "DADRA AND NAGAR HAVELI AND DAMAN AND DIU" : "GUJARAT";
            // Diu (362520); the rest of 362 is Gujarat
            case 362 -> full == 362520 ? "DADRA AND NAGAR HAVELI AND DAMAN AND DIU" : "GUJARAT";
            default -> null;
        };
    }

    private static String state(int p) {
        if (p == 110) {
            return "DELHI";
        }
        if (p >= 121 && p <= 136) {
            return "HARYANA";
        }
        if (p == 160) {
            return "CHANDIGARH";
        }
        if (p >= 140 && p <= 159 || p >= 161 && p <= 165) {
            return "PUNJAB";
        }
        if (p >= 171 && p <= 177) {
            return "HIMACHAL PRADESH";
        }
        if (p == 194) {
            return "LADAKH";
        }
        if (p >= 180 && p <= 193) {
            return "JAMMU AND KASHMIR";
        }
        if (p == 248 || p == 249 || p == 263) {
            return "UTTARAKHAND";
        }
        if (p >= 201 && p <= 285) {
            return "UTTAR PRADESH";
        }
        if (p >= 301 && p <= 345) {
            return "RAJASTHAN";
        }
        if (p >= 360 && p <= 396) {
            return "GUJARAT";
        }
        if (p == 403) {
            return "GOA";
        }
        if (p >= 400 && p <= 445) {
            return "MAHARASHTRA";
        }
        if (p >= 450 && p <= 488) {
            return "MADHYA PRADESH";
        }
        if (p >= 490 && p <= 497) {
            return "CHHATTISGARH";
        }
        if (p >= 500 && p <= 509) {
            return "TELANGANA";
        }
        if (p >= 515 && p <= 535) {
            return "ANDHRA PRADESH";
        }
        if (p >= 560 && p <= 591) {
            return "KARNATAKA";
        }
        if (p == 605) {
            // Mostly Puducherry (a few villages around it are Tamil Nadu's)
            return "PUDUCHERRY";
        }
        if (p >= 600 && p <= 643) {
            return "TAMIL NADU";
        }
        if (p >= 670 && p <= 695) {
            return "KERALA";
        }
        if (p == 737) {
            return "SIKKIM";
        }
        if (p == 744) {
            return "ANDAMAN AND NICOBAR ISLANDS";
        }
        if (p >= 700 && p <= 743) {
            return "WEST BENGAL";
        }
        if (p >= 751 && p <= 770) {
            return "ODISHA";
        }
        if (p >= 781 && p <= 788) {
            return "ASSAM";
        }
        if (p >= 790 && p <= 792) {
            return "ARUNACHAL PRADESH";
        }
        if (p == 793 || p == 794) {
            return "MEGHALAYA";
        }
        if (p == 795) {
            return "MANIPUR";
        }
        if (p == 796) {
            return "MIZORAM";
        }
        if (p == 797 || p == 798) {
            return "NAGALAND";
        }
        if (p == 799) {
            return "TRIPURA";
        }
        if (p >= 814 && p <= 816 || p >= 822 && p <= 835) {
            return "JHARKHAND";
        }
        if (p >= 800 && p <= 855) {
            return "BIHAR";
        }
        return null;
    }
}
