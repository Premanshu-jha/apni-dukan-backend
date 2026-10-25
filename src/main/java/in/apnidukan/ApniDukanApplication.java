package in.apnidukan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApniDukanApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApniDukanApplication.class, args);
    }
}
