package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.entity.request.RefreshTokenRequest;
import com.keny.jobassistant.model.entity.request.UserLoginRequest;
import com.keny.jobassistant.model.entity.request.UserRegisterRequest;
import com.keny.jobassistant.model.vo.TokenRefreshVO;
import com.keny.jobassistant.model.vo.UserLoginVO;
import com.keny.jobassistant.service.RefreshTokenService;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;
    @Resource
    private RefreshTokenService refreshTokenService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long userId = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(userId);
    }

    /**
     * 用户登录
     * 登录成功后返回：
     * 1. Access Token
     * 2. Refresh Token
     * 3. Token 有效时间
     * 4. 当前用户信息
     */
    @PostMapping("/login")
    public BaseResponse<UserLoginVO> userLogin(@RequestBody UserLoginRequest userLoginRequest) {
        if (userLoginRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        UserLoginVO loginResult = userService.userLogin(userAccount, userPassword);
        return ResultUtils.success(loginResult);
    }
    /**
     * 刷新 Token。
     * 客户端提交当前 Refresh Token，服务端撤销旧 Refresh Token，
     * 然后返回新的 Access Token 和 Refresh Token。
     */
    @PostMapping("/refresh")
    public BaseResponse<TokenRefreshVO> refreshToken(@RequestBody RefreshTokenRequest refreshTokenRequest) {
        if (refreshTokenRequest == null || StringUtils.isBlank(refreshTokenRequest.getRefreshToken())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Refresh token cannot be blank");
        }

        TokenRefreshVO refreshResult = refreshTokenService.refreshToken(refreshTokenRequest.getRefreshToken());
        return ResultUtils.success(refreshResult);
    }

    /**
     * 用户退出登录。服务端撤销当前 Refresh Token 所属的整个 Token Family。
     * 客户端收到成功响应后，还需要删除本地保存的：Access Token 和 Refresh Token
     */
    @PostMapping("/logout")
    public BaseResponse<Integer> userLogout(@RequestBody RefreshTokenRequest refreshTokenRequest) {
        if (refreshTokenRequest == null || StringUtils.isBlank(refreshTokenRequest.getRefreshToken())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Refresh token cannot be blank");
        }
        refreshTokenService.revokeTokenFamily(refreshTokenRequest.getRefreshToken());
        return ResultUtils.success(1);
    }
}