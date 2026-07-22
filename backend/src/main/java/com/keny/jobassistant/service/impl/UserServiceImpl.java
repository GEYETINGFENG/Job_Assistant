package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.UserDTO;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.vo.UserLoginVO;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.service.JwtTokenService;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户服务实现类。
 */
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    //正常用户状态
    private static final int NORMAL_USER_STATUS = 0;
    //未删除状态
    private static final int NOT_DELETED = 0;
    @Resource
    private UserRepository userRepository;

    /**
     * BCrypt 密码加密器，由 SecurityConfig 提供。
     */
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * JWT Token 生成服务。
     */
    @Resource
    private JwtTokenService jwtTokenService;

    /**
     * 用户注册。
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 校验注册参数。
        validateRegisterParameters(userAccount, userPassword, checkPassword);

        // 判断账号是否已经存在。
        Optional<User> existUser = userRepository.findByUserAccount(userAccount);
        if (existUser.isPresent()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account already exists");
        }
        User user = new User();
        user.setUserAccount(userAccount);
        // 使用 BCrypt 加密密码，BCrypt 会自动生成随机盐值。
        user.setUserPassword(passwordEncoder.encode(userPassword));
        User savedUser = userRepository.save(user);
        return savedUser.getId();
    }

    /**
     * 用户登录。
     *
     * 账号密码验证成功后生成 JWT，
     * 不再创建或保存 HttpSession。
     */
    @Override
    public UserLoginVO userLogin(String userAccount, String userPassword) {
        // 校验登录参数。
        validateLoginParameters(userAccount, userPassword);
        // 先根据用户账号查询数据库。
        User user = userRepository.findByUserAccount(userAccount).orElseThrow(() -> {
            log.info("User login failed: account does not exist");
            return new BusinessException(ErrorCode.PARAMS_ERROR, "User account or password is incorrect");
        });
        // 已被逻辑删除的用户不允许登录。
        if (user.getIsDelete() != null && user.getIsDelete() != NOT_DELETED) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account or password is incorrect");
        }
        // 使用 BCrypt 校验原始密码和数据库密码。
        if (!passwordEncoder.matches(userPassword, user.getUserPassword())) {
            log.info("User login failed: password does not match");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account or password is incorrect");
        }
        // 被禁用的用户不允许登录。
        if (user.getUserStatus() != null && user.getUserStatus() != NORMAL_USER_STATUS) {
            throw new BusinessException(ErrorCode.NO_AUTH, "User account is disabled");
        }

        // 账号密码验证成功后，为当前用户生成 JWT。
        String accessToken = jwtTokenService.generateAccessToken(user);

        // 返回 JWT 和脱敏后的用户信息。
        return UserLoginVO.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenService.getExpirationSeconds())
                .user(getUserDTO(user))
                .build();
    }

    /**
     * 将 User 实体转换成 UserDTO，避免返回密码等敏感信息。
     */
    @Override
    public UserDTO getUserDTO(User user) {
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

    /**
     * 查询用户。
     */
    @Override
    public List<UserDTO> searchUser(String username) {
        List<User> users;

        if (StringUtils.isBlank(username)) {
            users = userRepository.findAll();
        } else {
            users = userRepository.findByUsernameContaining(username);
        }

        return users.stream()
                .filter(user -> user.getIsDelete() == null || user.getIsDelete() == NOT_DELETED)
                .map(this::getUserDTO)
                .toList();
    }

    /**
     * 删除用户。
     */
    @Override
    public boolean deleteUser(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid user ID");
        }

        if (!userRepository.existsById(id)) {
            return false;
        }

        userRepository.deleteById(id);

        return true;
    }

    /**
     * 校验注册参数。
     */
    private void validateRegisterParameters(String userAccount, String userPassword, String checkPassword) {
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Parameters cannot be blank");
        }
        validateAccount(userAccount);
        validatePassword(userPassword);

        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Passwords do not match");
        }
    }

    /**
     * 校验登录参数。
     */
    private void validateLoginParameters(String userAccount, String userPassword) {
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Parameters cannot be blank");
        }

        validateAccount(userAccount);
        validatePassword(userPassword);
    }

    /**
     * 校验用户账号格式。
     */
    private void validateAccount(String userAccount) {
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account is too short");
        }

        String invalidPattern = "\\pP|\\pS|\\s+";
        Matcher matcher = Pattern.compile(invalidPattern).matcher(userAccount);

        if (matcher.find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User account contains invalid characters");
        }
    }

    /**
     * 校验用户密码长度。
     */
    private void validatePassword(String userPassword) {
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User password is too short");
        }
    }
}


