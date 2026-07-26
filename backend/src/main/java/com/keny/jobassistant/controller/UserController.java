package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.entity.request.UserLoginRequest;
import com.keny.jobassistant.model.entity.request.UserRegisterRequest;
import com.keny.jobassistant.model.vo.UserLoginVO;
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
     * 用户登录,登录成功后返回 JWT 和用户信息
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
     * 用户退出登录
     * JWT 是无状态的，后端不再销毁 Session。
     * 客户端收到成功响应后删除本地保存的 JWT。
     */
    @PostMapping("/logout")
    public BaseResponse<Integer> userLogout() {
        return ResultUtils.success(1);
    }
}