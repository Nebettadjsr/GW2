package web;

import application.AccountRefreshService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import web.task.BackgroundTaskService;
import java.time.Duration;

@Configuration
public class AccountDataFreshnessWebConfig implements WebMvcConfigurer {
    private final AccountDataFreshnessInterceptor interceptor;
    public AccountDataFreshnessWebConfig(AccountRefreshService refresh, BackgroundTaskService tasks,
            @Value("${gw2.account-data.max-age-ms:900000}") long maxAgeMs) {
        interceptor = new AccountDataFreshnessInterceptor(refresh, tasks, Duration.ofMillis(maxAgeMs));
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/**"); }
}
