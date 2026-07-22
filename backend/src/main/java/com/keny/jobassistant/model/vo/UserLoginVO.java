package com.keny.jobassistant.model.vo;

import com.keny.jobassistant.model.dto.UserDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录成功后的响应对象。
 * 同时返回 JWT、有效时间和用户信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO {

    /**
     * JWT Access Token。
     */
    private String accessToken;

    /**
     * Token 类型，固定为 Bearer。
     */
    private String tokenType;

    /**
     * Token 有效时间，单位为秒。
     */
    private Long expiresIn;

    /**
     * 当前登录用户的信息。
     */
    private UserDTO user;
}