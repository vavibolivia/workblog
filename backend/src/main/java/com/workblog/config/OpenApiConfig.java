package com.workblog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI(/swagger-ui/index.html) 와 OpenAPI JSON(/v3/api-docs). 사람용 문서는 /api/guide/api. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI workblogOpenApi() {
        return new OpenAPI()
                .info(new Info().title("WorkBlog API").version("1.0")
                        .description("사내 지식 공유 블로그 API. 인증: 웹 로그인 JWT 또는 개인 API 토큰(wbt_...)을 "
                                + "`Authorization: Bearer <token>` 헤더로 보낸다. 사용 안내는 /api/guide/api, "
                                + "Claude Code 연동은 /api/guide/claude-code.")
                        .contact(new Contact().name("WorkBlog")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("로그인 JWT 또는 개인 API 토큰(wbt_...)")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
