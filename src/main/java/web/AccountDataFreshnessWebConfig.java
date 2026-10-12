package web;

import application.AccountRefreshCoordinator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Duration;

@Configuration
public class AccountDataFreshnessWebConfig implements WebMvcConfigurer {
    private final AccountDataFreshnessInterceptor interceptor;
    public AccountDataFreshnessWebConfig(AccountRefreshCoordinator refreshCoordinator,
            @Value("${gw2.account-data.max-age-ms:900000}") long maxAgeMs) {
        interceptor = new AccountDataFreshnessInterceptor(refreshCoordinator, Duration.ofMillis(maxAgeMs));
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/**"); }
}
