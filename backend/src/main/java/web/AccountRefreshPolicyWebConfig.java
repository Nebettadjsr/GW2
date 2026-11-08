package web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Advertise the configured account cache lifetime, so open browser views need no duplicate TTL. */
@Configuration
public class AccountRefreshPolicyWebConfig implements WebMvcConfigurer {
    private final long maxAgeMs;
    public AccountRefreshPolicyWebConfig(@Value("${gw2.account-data.max-age-ms:900000}") long maxAgeMs) {
        this.maxAgeMs = maxAgeMs;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                response.setHeader("X-Account-Max-Age-Ms", Long.toString(maxAgeMs));
                return true;
            }
        }).addPathPatterns("/api/account/**");
    }
}
