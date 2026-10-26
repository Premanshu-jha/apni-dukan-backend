package in.apnidukan.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Lets @Async methods (e.g. sending email) run in the background so requests don't wait for them. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
