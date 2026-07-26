package com.keny.jobassistant.service;

import com.keny.jobassistant.model.dto.UserDTO;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.vo.UserLoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * 用户服务接口。
 */
public interface UserService {
    //注册用户
    long userRegister(String userAccount, String userPassword, String checkPassword);

    //用户登录,登录成功后返回 JWT 和用户信息
    UserLoginVO userLogin(String userAccount, String userPassword);

    //将用户实体转换为安全的用户 DTO
    UserDTO getUserDTO(User user);

    //查询用户
    List<UserDTO> searchUser(String username);

    //删除用户
    boolean deleteUser(Long id);
}