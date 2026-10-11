package com.star.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import com.star.interceptor.AdminAuthInterceptor;
import com.star.interceptor.JwtTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

@Configuration
@EnableKnife4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenInterceptor jwtTokenInterceptor;

    @Autowired
    private AdminAuthInterceptor adminAuthInterceptor;

    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
        // 1) 登录校验：解析 JWT，把 userId 放进 ThreadLocal
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/login",
                        "/api/register",
                        "/api/password/reset/send-code",
                        "/api/password/reset",
                        "/api/share/public/*",
                        "/api/share/public/*/download"
                );
        // 2) 管理员二次鉴权：/api/admin/** 要求 is_admin >= 1（防止普通用户越权调用管理接口）
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    @Bean
    public Docket docket() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("LightDisk API")
                .version("1.0")
                .description("LightDisk backend api documentation")
                .build();

        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.star.controller"))
                .paths(PathSelectors.any())
                .build();
    }
}
