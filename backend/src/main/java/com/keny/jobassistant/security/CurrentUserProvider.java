package com.keny.jobassistant.security;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

//从 Spring Security 保存的认证信息中获取当前登录用户 ID
@Component
public class CurrentUserProvider {

    /**
     * 获取当前登录用户 ID。
     * 生成 JWT 时，用户 ID 被保存到了 sub 字段。
     * SecurityConfig 又将 sub 设置为当前认证用户名称，
     * 因此 authentication.getName() 返回的是当前用户 ID。
     *
     * @return 当前登录用户 ID
     */
    public Long getCurrentUserId() {
        //SecurityContextHolder是Spring Security 保存当前登录信息的位置
        //getContext()获取当前安全上下文，里面有Authentication
        //getAuthentication(),获取当前用户认证对象
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        //没有登录或者认证失败
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        //获取用户 ID，这里的getName()就是为了获取sub
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.NOT_LOGIN, "Invalid authenticated user ID");
        }
    }
}