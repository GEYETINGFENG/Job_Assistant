package com.keny.jobassistant.controller;

import com.keny.jobassistant.config.SecurityConfig;
import com.keny.jobassistant.model.dto.ResumeCacheStatsDTO;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.security.CustomAccessDeniedHandler;
import com.keny.jobassistant.security.CustomAuthenticationEntryPoint;
import com.keny.jobassistant.service.JwtTokenService;
import com.keny.jobassistant.service.ResumeParseCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 JWT 与项目安全配置验证管理员访问边界。 */
@WebMvcTest(ResumeCacheAdminController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, CustomAccessDeniedHandler.class, CustomAuthenticationEntryPoint.class, JwtTokenService.class})
class ResumeCacheAdminControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtTokenService tokens;
    @MockBean
    private ResumeParseCacheService cache;

    @Test
    void adminShouldSeeStats() throws Exception {
        when(cache.stats()).thenReturn(new ResumeCacheStatsDTO("test-model", "v1", 1, 1, 2, 0.5));
        mvc.perform(get("/admin/resume-cache/stats").header("Authorization", "Bearer " + token(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hit").value(1)).andExpect(jsonPath("$.data.miss").value(1))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.hitRate").value(0.5))
                .andExpect(jsonPath("$.data.modelVersion").value("test-model"))
                .andExpect(jsonPath("$.data.promptVersion").value("v1"));
    }

    @Test
    void ordinaryUserShouldBeForbidden() throws Exception {
        mvc.perform(get("/admin/resume-cache/stats").header("Authorization", "Bearer " + token(0))).andExpect(status().isForbidden());
        verifyNoInteractions(cache);
    }

    @Test
    void anonymousShouldBeUnauthorized() throws Exception {
        mvc.perform(get("/admin/resume-cache/stats")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cache);
    }

    private String token(int role) {
        User user = new User();
        user.setId(123L);
        user.setUserAccount("cache-test");
        user.setUserRole(role);
        return tokens.generateAccessToken(user);
    }
}
