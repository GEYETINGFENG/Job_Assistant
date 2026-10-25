package com.keny.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 契约快照测试。
 * 从运行中的应用导出 /v3/api-docs，与仓库中提交的 openapi/openapi.json 比较：
 * 后端改了接口却没有更新快照时测试失败，前端也就不会在不知情的情况下用旧契约生成类型。
 *
 * 更新快照：OPENAPI_UPDATE=true ./mvnw verify -DskipUnitTests -Dit.test=OpenApiContractIntegrationTest
 * 然后在 frontend 目录执行 npm run gen:api。
 */
@SpringBootTest(properties = "springdoc.writer-with-order-by-keys=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class OpenApiContractIntegrationTest {

    // Maven 在 backend 目录运行测试，快照位于仓库根目录。
    private static final Path SNAPSHOT = Path.of("..", "openapi", "openapi.json");

    @Resource
    private MockMvc mockMvc;

    @Resource
    private ObjectMapper objectMapper;

    @Test
    void openApiContractShouldMatchCommittedSnapshot() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode generated = objectMapper.readTree(body);

        if ("true".equalsIgnoreCase(System.getenv("OPENAPI_UPDATE"))) {
            Files.createDirectories(SNAPSHOT.getParent());
            String pretty = objectMapper.copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(generated);
            Files.writeString(SNAPSHOT, pretty + System.lineSeparator(), StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .as("OpenAPI snapshot %s is missing; run with OPENAPI_UPDATE=true to create it", SNAPSHOT.toAbsolutePath())
                .isTrue();
        JsonNode committed = objectMapper.readTree(Files.readString(SNAPSHOT, StandardCharsets.UTF_8));
        assertThat(generated)
                .as("API contract changed. Regenerate openapi/openapi.json with OPENAPI_UPDATE=true, "
                        + "then run `npm run gen:api` in frontend and fix any type errors.")
                .isEqualTo(committed);
    }
}
