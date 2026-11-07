package in.apnidukan.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every migration from V17 on waits at most 3 s for a table's lock, so a deploy next to a copy of the backend still
 * writing fails fast (and is deployed again) instead of holding every write behind it.
 */
class MigrationLockTimeoutTest {

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    @Test
    void migrationsFromV17OnSetALockTimeoutBeforeAnythingElse() throws IOException {
        int checked = 0;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            Matcher m = VERSION.matcher(String.valueOf(r.getFilename()));
            if (!m.matches() || Integer.parseInt(m.group(1)) < 17) {
                continue;
            }
            String firstStatement = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("--"))
                    .findFirst().orElse("");
            assertThat(firstStatement).as(r.getFilename()).isEqualTo("SET LOCAL lock_timeout = '3s';");
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(3);
    }
}
