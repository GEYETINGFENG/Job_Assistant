package com.keny.jobassistant.model.entity.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Refresh token request.
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token cannot be blank")
    private String refreshToken;
}