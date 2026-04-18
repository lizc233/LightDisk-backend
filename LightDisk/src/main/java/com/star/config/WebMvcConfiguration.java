package com.star.config;

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
import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;

@Configuration
@EnableKnife4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenInterceptor jwtTokenInterceptor;
    protected void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/api/**") // 拦截所有业务接口
                .excludePathPatterns(
                        "/api/login",
                        "/api/register"
                        ); // 放行登录接口

    }
    @Bean
    public Docket docket() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("轻量级云盘系统接口文档")
                .version("1.0")
                .description("基于SpringBoot的轻量级云盘系统接口定义")
                .build();

        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                // 这里一定要指向你写 Controller 的包
                .apis(RequestHandlerSelectors.basePackage("com.star.controller"))
                .paths(PathSelectors.any())
                .build();
    }
}
