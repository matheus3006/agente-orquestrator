package com.orquestrador.config;

import com.orquestrador.common.security.CompanyScopeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CompanyScopeInterceptor companyScopeInterceptor;

    public WebConfig(CompanyScopeInterceptor companyScopeInterceptor) {
        this.companyScopeInterceptor = companyScopeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(companyScopeInterceptor).addPathPatterns("/api/companies/**");
    }
}
