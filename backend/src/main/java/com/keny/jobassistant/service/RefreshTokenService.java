package com.keny.jobassistant.service;

import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.vo.TokenRefreshVO;

public interface RefreshTokenService {
    // 登录成功时生成第一个 Refresh Token
    String createRefreshToken(User user);
    // 在refresh的时候使用旧 Refresh Token 换取新的双 Token
    TokenRefreshVO refreshToken(String rawRefreshToken);
    // 退出登录时撤销当前登录会话
    void revokeTokenFamily(String rawRefreshToken);
    // 用户被删除、禁用或修改密码时撤销全部会话
    void revokeAllByUserId(Long userId);
    long getRefreshExpirationSeconds();
}