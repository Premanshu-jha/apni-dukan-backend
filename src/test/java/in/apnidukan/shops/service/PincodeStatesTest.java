package in.apnidukan.shops.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The state Google Play's tax address needs, from a salon's PIN code. */
class PincodeStatesTest {

    @Test
    void namesTheStateAsGooglePlayDoes() {
        assertThat(PincodeStates.of("500084")).contains("TELANGANA");
        assertThat(PincodeStates.of("530001")).contains("ANDHRA PRADESH");
        assertThat(PincodeStates.of("110001")).contains("DELHI");
        assertThat(PincodeStates.of("400001")).contains("MAHARASHTRA");
        assertThat(PincodeStates.of("403001")).contains("GOA");
        assertThat(PincodeStates.of("560001")).contains("KARNATAKA");
        assertThat(PincodeStates.of("600001")).contains("TAMIL NADU");
        assertThat(PincodeStates.of("605001")).contains("PUDUCHERRY");
        assertThat(PincodeStates.of("700001")).contains("WEST BENGAL");
        assertThat(PincodeStates.of("160017")).contains("CHANDIGARH");
        assertThat(PincodeStates.of("248001")).contains("UTTARAKHAND");
        assertThat(PincodeStates.of("226001")).contains("UTTAR PRADESH");
        assertThat(PincodeStates.of("834001")).contains("JHARKHAND");
        assertThat(PincodeStates.of("800001")).contains("BIHAR");
        assertThat(PincodeStates.of("682001")).contains("KERALA");
        assertThat(PincodeStates.of("781001")).contains("ASSAM");
    }

    @Test
    void areasCrossingAStateLineGoByTheFullPinCode() {
        assertThat(PincodeStates.of("244001")).contains("UTTAR PRADESH");     // Moradabad
        assertThat(PincodeStates.of("244713")).contains("UTTARAKHAND");       // Kashipur
        assertThat(PincodeStates.of("246001")).contains("UTTARAKHAND");       // Pauri
        assertThat(PincodeStates.of("246701")).contains("UTTAR PRADESH");     // Bijnor
        assertThat(PincodeStates.of("247001")).contains("UTTAR PRADESH");     // Saharanpur
        assertThat(PincodeStates.of("247667")).contains("UTTARAKHAND");       // Roorkee
        assertThat(PincodeStates.of("262001")).contains("UTTAR PRADESH");     // Pilibhit
        assertThat(PincodeStates.of("262308")).contains("UTTARAKHAND");       // Khatima
        assertThat(PincodeStates.of("263153")).contains("UTTARAKHAND");       // Rudrapur
        assertThat(PincodeStates.of("160062")).contains("PUNJAB");            // Mohali
        assertThat(PincodeStates.of("160101")).contains("CHANDIGARH");        // Manimajra
        assertThat(PincodeStates.of("609602")).contains("PUDUCHERRY");        // Karaikal
        assertThat(PincodeStates.of("609001")).contains("TAMIL NADU");        // Mayiladuthurai
        assertThat(PincodeStates.of("673310")).contains("PUDUCHERRY");        // Mahe
        assertThat(PincodeStates.of("673001")).contains("KERALA");            // Kozhikode
        assertThat(PincodeStates.of("533464")).contains("PUDUCHERRY");        // Yanam
        assertThat(PincodeStates.of("533001")).contains("ANDHRA PRADESH");    // Kakinada
        assertThat(PincodeStates.of("396001")).contains("GUJARAT");           // Valsad
        assertThat(PincodeStates.of("396210")).contains("DADRA AND NAGAR HAVELI AND DAMAN AND DIU");   // Daman
        assertThat(PincodeStates.of("362520")).contains("DADRA AND NAGAR HAVELI AND DAMAN AND DIU");   // Diu
        assertThat(PincodeStates.of("362001")).contains("GUJARAT");           // Junagadh
    }

    @Test
    void anythingElseHasNone() {
        assertThat(PincodeStates.of("012345")).isEmpty();
        assertThat(PincodeStates.of("99999")).isEmpty();
        assertThat(PincodeStates.of("abcdef")).isEmpty();
        assertThat(PincodeStates.of(null)).isEmpty();
        assertThat(PincodeStates.of("990001")).isEmpty();
    }
}
