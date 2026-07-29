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
//链式创建对象
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO {

    /**
     * 短期 JWT Access Token。
     */
    private String accessToken;

    /**
     * 长期 Refresh Token，只用于调用刷新接口，不能直接访问普通业务接口。
     */
    private String refreshToken;
    /**
     * Token 类型，固定为 Bearer。
     */
    private String tokenType;

    /**
     *  Access Token 有效时间，单位为秒
     */
    private Long expiresIn;

    /**
     * Refresh Token 有效时间，单位为秒。
     */
    private Long refreshExpiresIn;

    /**
     * 当前登录用户的信息。
     */
    private UserDTO user;
}