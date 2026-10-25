package com.keny.jobassistant.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 契约配置。
 * 前端从生成的契约生成 TypeScript 类型；契约快照提交在仓库根目录 openapi/openapi.json。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI jobAssistantOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JobAssistant API")
                        .version("v1")
                        .description("Unified response: { code, data, message, description }; code = 0 means success."))
                // 固定 server，避免契约里出现随测试端口变化的地址，保证快照稳定。
                .servers(List.of(new Server().url("/api")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
