package in.apnidukan.salon.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceIconTest {

    @Test
    void anIconIsReadInAnyCaseAndAnythingElseIsNone() {
        assertThat(ServiceIcon.parse(" haircut ")).isEqualTo(ServiceIcon.HAIRCUT);
        assertThat(ServiceIcon.parse("Head_Massage")).isEqualTo(ServiceIcon.HEAD_MASSAGE);
        assertThat(ServiceIcon.parse("nail_art")).isEqualTo(ServiceIcon.MANICURE);   // retired in V63, from an old app
        assertThat(ServiceIcon.parse("CLEANUP")).isEqualTo(ServiceIcon.FACIAL);
        assertThat(ServiceIcon.parse("rocket")).isNull();
        assertThat(ServiceIcon.parse(null)).isNull();
    }

    /** The database's check (now V63) allows exactly the icons the app knows: one added to one must be added to both. */
    @Test
    void theDatabaseAllowsExactlyTheseIcons() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V63__service_icon_set_2.sql"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(sql.substring(sql.indexOf("icon IN")));
        Set<String> allowed = new LinkedHashSet<>();
        while (m.find()) {
            allowed.add(m.group(1));
        }
        assertThat(allowed).containsExactlyElementsOf(Arrays.stream(ServiceIcon.values()).map(Enum::name).toList());
    }
}
