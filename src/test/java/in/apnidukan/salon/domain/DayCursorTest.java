package in.apnidukan.salon.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The delta cursor: written and read back exactly; anything else is no cursor (a whole answer then). */
class DayCursorTest {

    @Test
    void aCursorReadsBackAsItWasWritten() {
        DayCursor c = new DayCursor(false, LocalDate.of(2026, 11, 10), 812345, 77, 91,
                Instant.parse("2026-11-10T04:30:12Z"), Instant.parse("2026-11-10T06:30:00Z"));
        assertThat(c.text()).isEqualTo("d3.2026-11-10.812345.77.91.1794285012.1794292200");
        assertThat(DayCursor.parse(c.text(), false)).contains(c);
        DayCursor u = new DayCursor(true, LocalDate.of(2026, 11, 10), 812345, 77, 91,
                Instant.parse("2026-11-10T04:30:12Z"), Instant.parse("2026-11-10T06:30:00Z"));
        assertThat(u.text()).isEqualTo("u3.2026-11-10.812345.77.91.1794285012.1794292200");
        assertThat(DayCursor.parse(u.text(), true)).contains(u);
        // One list's cursor is no cursor for the other
        assertThat(DayCursor.parse(c.text(), true)).isEmpty();
        assertThat(DayCursor.parse(u.text(), false)).isEmpty();
    }

    @Test
    void anythingElseIsNoCursor() {
        for (String bad : new String[] {null, "", "d3", "d1.1.2.3.4", "d3.2026-11-10.1.2.3.4",
                "d3.2026-11-10.1.2.3.4.5.6", "d3.2026-13-10.1.2.3.4.5", "d3.2026-11-10.a.2.3.4.5",
                "d3.2026-11-10.1.2.3.4." + "9".repeat(30), "x".repeat(121),
                "d2.2026-11-10.1.2.3.4"}) {   // the previous form: no cursor, one whole answer
            assertThat(DayCursor.parse(bad, false)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
