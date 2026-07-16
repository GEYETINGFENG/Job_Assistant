package com.keny.jobassistant.service.impl;
import java.util.Date;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.domain.User;
import com.keny.jobassistant.service.UserService;
import com.keny.jobassistant.mapper.UserMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.keny.jobassistant.constant.UserConstant.USER_LOGIN_STATE;

/**
* @author Keny
* @description 针对表【user(用户表)】的数据库操作Service实现
*/
@Service
@Slf4j
//Slf4j的作用是显示日志
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService{

    @Resource
    UserMapper userMapper;
    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "keny";
    /**
     * 用户登录态键
     */

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        //1.校验是否非空,这里要调用一个库
        if(StringUtils.isAnyBlank(userAccount,userPassword,checkPassword)){
            //return -1;
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"参数为空");
        }
        if ((userAccount.length()<4)){
            //return -1;
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户账号过短");
        }
        if ((userPassword.length()<8 || checkPassword.length()<8)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户密码过短");
        }
        //账户不能包含特殊字符，这个去网上搜索正则表达式
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);//如果账号里找到了特殊字符，就返回 -1
        if (matcher.find()){
            return -1;
        }
        //密码和校验密码相同
        if (!checkPassword.equals(userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"密码与校验密码不相同");
        }
        // 账户不能重复,要放在校验之后，如果校验都没通过，那么更不需要查询数据库
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        Long count = userMapper.selectCount(queryWrapper);
        if (count>0){
            return -1;
        }
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT+userPassword).getBytes());
        //插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        boolean saveResult = this.save(user);
        if (!saveResult){
            return -1;
        }
        return user.getId();
    }

    @Override
    public User userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        //1.校验是否非空,这里要调用一个库
        if(StringUtils.isAnyBlank(userAccount,userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"参数为空");
        }
        if ((userAccount.length()<4)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户账号过短");
        }
        if ((userPassword.length()<8)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户密码过短");
        }
        //账户不能包含特殊字符，这个去网上搜索正则表达式
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);//如果账号里找到了特殊字符，就返回 -1
        if (matcher.find()){
            return null;
        }
        //加密以及去数据库进行匹配
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT+userPassword).getBytes());
        //查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = userMapper.selectOne(queryWrapper);
        //用户不存在
        if (user==null){
            log.info("User login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号密码不匹配");
        }
        //用户信息脱敏
        User safetyUser = getSafetyUser(user);
        //记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE,safetyUser);
        return safetyUser;

    }

    @Override
    public User getSafetyUser(User originUser){
        User safetyUser = new User();
        safetyUser.setId(originUser.getId());
        safetyUser.setUsername(originUser.getUsername());
        safetyUser.setUserAccount(originUser.getUserAccount());
        safetyUser.setAvatarUrl(originUser.getAvatarUrl());
        safetyUser.setGender(originUser.getGender());
        safetyUser.setEmail(originUser.getEmail());
        safetyUser.setUserStatus(originUser.getUserStatus());
        safetyUser.setPhone(originUser.getPhone());
        safetyUser.setUserRole(originUser.getUserRole());
        safetyUser.setCreateTime(new Date());
        return safetyUser;

    }

    @Override
    public int userLogout(HttpServletRequest request) {
        //移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return 1;
    }

}




