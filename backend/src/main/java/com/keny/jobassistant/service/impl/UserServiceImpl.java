package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.UserDTO;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.List;
import java.util.Optional;
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
public class UserServiceImpl implements UserService{

    @Resource
    private UserRepository userRepository;
    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "keny";
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        //1.校验是否非空,这里要调用一个库
        if(StringUtils.isAnyBlank(userAccount,userPassword,checkPassword)){
            //return -1;
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"Parameters cannot be blank");
        }
        if ((userAccount.length()<4)){
            //return -1;
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"User account is too short");
        }
        if ((userPassword.length()<8 || checkPassword.length()<8)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"User password is too short");
        }
        //账户不能包含特殊字符，这个去网上搜索正则表达式
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);//如果账号里找到了特殊字符，就返回 -1
        if (matcher.find()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account contains invalid characters");
        }
        //密码和校验密码相同
        if (!checkPassword.equals(userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"Passwords do not match");
        }
        // 账户不能重复,要放在校验之后，如果校验都没通过，那么更不需要查询数据库
        Optional<User> existUser = userRepository.findByUserAccount(userAccount);
        if(existUser.isPresent()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account already exists");
        }
        // 密码加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT+userPassword).getBytes());
        //插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);

        // 保存
        User saveUser = userRepository.save(user);
        return saveUser.getId();
    }

    @Override
    public UserDTO userLogin(String userAccount, String userPassword, HttpServletRequest request, HttpServletResponse response) {
        //1.校验是否非空,这里要调用一个库
        if(StringUtils.isAnyBlank(userAccount,userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"Parameters cannot be blank");
        }
        if ((userAccount.length()<4)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"User account is too short");
        }
        if ((userPassword.length()<8)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"User password is too short");
        }
        //账户不能包含特殊字符，这个去网上搜索正则表达式
        String validPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);//如果账号里找到了特殊字符，就返回 -1
        if (matcher.find()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account contains invalid characters");
        }
        //加密以及去数据库进行匹配
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT+userPassword).getBytes());
        //查询用户是否存在
        // 查询用户
        Optional<User> optionalUser = userRepository.findByUserAccountAndUserPassword(userAccount, encryptPassword);
        //用户不存在
        if (optionalUser.isEmpty()){
            log.info("User login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"User account or password is incorrect");
        }
        User user = optionalUser.get();
        //用户信息脱敏
        UserDTO userDTO = getUserDTO(user);
        //记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE,userDTO);

        /*
         * hasRole("ADMIN") requires the authority ROLE_ADMIN.
         * For example:
         * userRole = ADMIN -> authority = ROLE_ADMIN
         */
        Integer userRole = userDTO.getUserRole(); //获取数据库中的角色

        // Convert the database role value to a Spring Security authority
        String authorityName; //将数据库角色转换为 Spring Security 权限
        if (userRole != null && userRole == 1) {
            authorityName = "ROLE_ADMIN";
        } else {
            authorityName = "ROLE_USER";
        }
        //SimpleGrantedAuthority 是 Spring Security 提供的权限实现类,将普通字符串ROLE_ADMIN包装成Spring Security可以识别的权限对象
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(authorityName);
        //创建 Authentication 认证对象,保存当前用户是谁，是否已经认证，拥有哪些权限
        Authentication authentication = new UsernamePasswordAuthenticationToken(userDTO, null, List.of(authority));
        //创建一个空的安全上下文对象，并将它赋值给变量 securityContext
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        //将认证信息放进上下文
        securityContext.setAuthentication(authentication);
        //告诉 Spring Security，在当前请求中，使用这个 securityContext 作为当前用户的认证信息
        SecurityContextHolder.setContext(securityContext);

        // 将 SecurityContext 持久化到 Session
        securityContextRepository.saveContext(securityContext, request, response);
        return userDTO ;
    }

    @Override
    public UserDTO getUserDTO(User user){
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setUserAccount(user.getUserAccount());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setGender(user.getGender());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setUserStatus(user.getUserStatus());
        dto.setUserRole(user.getUserRole());
        dto.setCreateTime(user.getCreateTime());
        return dto;
    }

    @Override
    public int userLogout(HttpServletRequest request, HttpServletResponse response) {
        //request,表示当前 HTTP 请求，主要用来获取 Session
        //response 表示当前 HTTP 响应
        //移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);

        SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.clearContext();
        securityContextRepository.saveContext(emptyContext, request, response);
        request.getSession().invalidate();//销毁整个 Session
        return 1;
    }

    @Override
    public List<UserDTO> searchUser(String username){
        List<User> users;
        if(StringUtils.isBlank(username)){
            users = userRepository.findAll();
        }else{
            users = userRepository.findByUsernameContaining(username);
        }
        return users.stream().map(this::getUserDTO).toList();

    }

    @Override
    public boolean deleteUser(Long id){
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid user ID");
        }
        if(!userRepository.existsById(id)){
            return false;
        }
        userRepository.deleteById(id);
        return true;
    }
}




