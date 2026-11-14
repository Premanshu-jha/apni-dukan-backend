package in.apnidukan.shops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * The AI that reads trade licences, only when ANTHROPIC_API_KEY is set (app.licence.ai-key). Without it there is no
 * {@link LicenceReader}: uploads still work and wait as PENDING ("Checking") until a key is set.
 */
@Configuration(proxyBeanMethods = false)
public class LicenceReaderConfig {

    @Bean
    @ConditionalOnExpression("!'${app.licence.ai-key:}'.isBlank()")
    LicenceReader anthropicLicenceReader(@Value("${app.licence.ai-key}") String key,
                                         @Value("${app.licence.ai-model:claude-sonnet-5-5}") String model,
                                         @Value("${app.licence.ai-url:}") String url) {
        return new AnthropicLicenceReader(key.strip(), model.strip(),
                url.isBlank() ? AnthropicLicenceReader.ANTHROPIC : URI.create(url.strip()));
    }
}
