package com.keny.jobassistant.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.domain.User;
import com.keny.jobassistant.model.domain.request.UserLoginRequest;
import com.keny.jobassistant.model.domain.request.UserRegisterRequest;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.keny.jobassistant.constant.UserConstant.ADMIN_ROLE;
import static com.keny.jobassistant.constant.UserConstant.USER_LOGIN_STATE;


@RestController
@RequestMapping("/user")

public class UserController {
    @Resource
    private UserService userService;

    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null){
            //return ResultUtils.error(ErrorCode.PARAMS_ERROR);
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)){
            return null;
        }
        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        //return new BaseResponse<>(0,result,"ok");
        return ResultUtils.success(result);
    }

    @PostMapping("/login")
    public BaseResponse<User> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        if (userLoginRequest == null){
            return null;
        }
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword)){
            return null;
        }
        User user = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(user);
    }

    @PostMapping("/logout")
    public BaseResponse<Integer> userLogout(HttpServletRequest request) {
        if (request == null){
            return null;
        }
        int result = userService.userLogout(request);
        return ResultUtils.success(result);

    }

    @GetMapping("/search")
    public BaseResponse<List<User>> searchUser(String username, HttpServletRequest request){
        if (!isAdmin(request)){
            return ResultUtils.success(new ArrayList<>());
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        if (StringUtils.isNotBlank(username)){
            queryWrapper.like("username", username);
        }
        List<User> userList = userService.list(queryWrapper);//模糊查询
        List<User> result = userList.stream().map(user -> {
            user.setUserPassword(null);
            return userService.getSafetyUser(user);
        }).collect(Collectors.toList());
        return ResultUtils.success(result);
    }

    @PostMapping("/delete")//用Post请求来处理删除操作支持请求体
    public BaseResponse<Boolean> deleteUser(@RequestBody long id, HttpServletRequest request){
        if (!isAdmin(request)){
            throw new BusinessException(ErrorCode.NO_AUTH);}
        if (id<=0){
            return null;
        }
        boolean result = userService.removeById(id);
        return ResultUtils.success(result);

    }
    private boolean isAdmin(HttpServletRequest request){
        //仅管理员可查询
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;//强制转化成User类型
        if (user == null || user.getUserRole() != ADMIN_ROLE){
            return false;
        }
        return true;
    }



}
