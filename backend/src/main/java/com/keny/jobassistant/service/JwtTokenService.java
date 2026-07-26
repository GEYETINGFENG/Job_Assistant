package com.keny.jobassistant.service;

import com.keny.jobassistant.model.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JWT Token 服务，负责在用户登录成功后生成 JWT。
 */
@Service
public class JwtTokenService {

    /**
     * 数据库中管理员角色的值。
     */
    private static final int ADMIN_ROLE = 1;

    /**
     * JWT 编码器，由 SecurityConfig 提供。
     */
    private final JwtEncoder jwtEncoder;

    /**
     * JWT 签发者。
     */
    private final String issuer;

    /**
     * JWT 有效时间，单位为秒。
     */
    private final long expirationSeconds;

    public JwtTokenService(JwtEncoder jwtEncoder,
                           @Value("${jwt.issuer}") String issuer,
                           @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 为用户生成 JWT。
     *
     * @param user 已经完成账号密码校验的用户
     * @return JWT 字符串
     */
    public String generateAccessToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(expirationSeconds);
        String role = Integer.valueOf(ADMIN_ROLE).equals(user.getUserRole()) ? "ADMIN" : "USER";

        JwtClaimsSet claims = JwtClaimsSet.builder()
                // JWT 签发者。
                .issuer(issuer)
                // JWT 签发时间。
                .issuedAt(issuedAt)
                // JWT 过期时间。
                .expiresAt(expiresAt)
                // 将用户 ID 放入 sub，后续可以通过 authentication.getName() 获取。
                .subject(String.valueOf(user.getId()))
                // 为每个 JWT 生成唯一标识，保证每次登录生成的 Token 不同。
                .id(UUID.randomUUID().toString())
                // 保存用户账号。
                .claim("userAccount", user.getUserAccount())
                // 保存用户角色。
                .claim("roles", List.of(role))
                .build();

        // 指定 JWT 使用 HS256 签名算法。
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

        JwtEncoderParameters parameters = JwtEncoderParameters.from(header, claims);

        // 使用 SecurityConfig 中的 JwtEncoder 对 JWT 进行签名。
        return jwtEncoder.encode(parameters).getTokenValue();
    }

    //获取 JWT 有效时间
    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}