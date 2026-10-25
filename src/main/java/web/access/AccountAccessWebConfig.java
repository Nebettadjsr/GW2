package web.access;

import application.access.AccountAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the account-access guard (STORY-API-010).
 *
 * <p>{@link Order} with the highest precedence is load-bearing, not decoration: Spring adds
 * interceptors in configurer order, and this one has to run before
 * {@code web.AccountDataFreshnessWebConfig}'s. Otherwise an unauthenticated account request would
 * open a connection and admit a refresh before anything checked whether the caller may see that
 * account at all.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountAccessWebConfig implements WebMvcConfigurer {

    private final AccountAccessInterceptor interceptor;

    public AccountAccessWebConfig(AccountAccessService access,
                                  ObjectMapper mapper,
                                  @Value("${gw2.account-access.enforce:false}") boolean enforce,
                                  @Value("${gw2.account-access.test-fixtures:false}") boolean fixtures) {
        this.interceptor = new AccountAccessInterceptor(access, mapper, enforce, fixtures);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/account/**");
    }
}
