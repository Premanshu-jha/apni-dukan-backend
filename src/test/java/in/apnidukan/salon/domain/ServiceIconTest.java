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
        assertThat(ServiceIcon.parse("Nail_Art")).isEqualTo(ServiceIcon.NAIL_ART);
        assertThat(ServiceIcon.parse("rocket")).isNull();
        assertThat(ServiceIcon.parse(null)).isNull();
    }

    /** The database's check (V61) allows exactly the icons the app knows: one added to one must be added to both. */
    @Test
    void theDatabaseAllowsExactlyTheseIcons() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V61__service_icon.sql"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(sql.substring(sql.indexOf("icon IN")));
        Set<String> allowed = new LinkedHashSet<>();
        while (m.find()) {
            allowed.add(m.group(1));
        }
        assertThat(allowed).containsExactlyElementsOf(Arrays.stream(ServiceIcon.values()).map(Enum::name).toList());
    }
}
