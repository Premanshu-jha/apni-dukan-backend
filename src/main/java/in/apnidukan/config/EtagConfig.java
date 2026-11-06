package in.apnidukan.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Every GET answer of the API carries an ETag (a fingerprint of its body). The apps refresh some screens every 30 to
 * 60 seconds (a salon's day, the bell, the field team's shop); they send the ETag they last got as If-None-Match, and
 * when nothing changed the answer is 304 with no body: the phone downloads nothing and redraws nothing. Lists of
 * salons and shops keep the same photo links for most of an hour (FileRefService), so their fingerprint holds too.
 *
 * Browsers aren't asked to cache the answers themselves (Cache-Control stays no-store, set by Spring Security): on a
 * shared phone, someone's bookings must not stay in the browser's cache after they sign out. The apps keep the last
 * answer in memory only.
 */
@Configuration
public class EtagConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        bean.addUrlPatterns("/api/v1/*");
        bean.setName("etags");
        return bean;
    }
}
