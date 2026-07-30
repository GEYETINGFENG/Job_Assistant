package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.UserDTO;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.vo.UserLoginVO;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.service.JwtTokenService;
import com.keny.jobassistant.service.RefreshTokenService;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    @Resource
    private UserRepository userRepository;

    /**
     * BCrypt 密码加密器，由 SecurityConfig 提供。
     */
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * JWT Access Token 生成服务。
     */
    @Resource
    private JwtTokenService jwtTokenService;

    /**
     * Refresh Token 服务。
     */
    @Resource
    private RefreshTokenService refreshTokenService;

    // 用于账号不存在时执行 Dummy BCrypt 校验的虚假密码哈希。
    private String dummyPasswordHash;

    /**
     * Spring 完成依赖注入后，生成一次虚假的 BCrypt 密码哈希。
     * 账号不存在时也执行一次 BCrypt matches，
     * 缩小账号不存在和密码错误两条登录路径的耗时差异。
     */
    @PostConstruct
    public void initializeDummyPasswordHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 用户注册。
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 校验注册参数。
        validateRegisterParameters(userAccount, userPassword, checkPassword);

        // 判断账号是否已经存在。用户删除后，原账号永久不可重新注册。
        boolean accountExists = userRepository.existsByUserAccount(userAccount);
        if (accountExists) {
            throw new BusinessException(ErrorCode.ACCOUNT_CONFLICT);
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
     *登录成功后：
     * 1. 生成短期 Access Token
     * 2. 生成长期 Refresh Token
     * 3. 将 Refresh Token 哈希保存到数据库
     * 4. 返回两个 Token 和用户信息
     * 使用事务可以保证 Refresh Token 保存失败时，整个登录操作不会返回不完整的结果。
     */
    @Override
    @Transactional
    public UserLoginVO userLogin(String userAccount, String userPassword) {
        // 校验登录参数。
        validateLoginParameters(userAccount, userPassword);
        // 先根据用户账号查询数据库。
        Optional<User> optionalUser = userRepository.findByUserAccountAndIsDelete(userAccount, User.NOT_DELETED);
        // 账号不存在时也执行一次 BCrypt 校验
        // 这里使用项目启动时生成的虚假 BCrypt哈希执行 matches，让两条登录失败路径都包含一次 BCrypt 计算。
        if (optionalUser.isEmpty()) {
            passwordEncoder.matches(userPassword, dummyPasswordHash);
            log.info("User login failed: invalid credentials");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = optionalUser.get();

        // 使用 BCrypt 校验原始密码和数据库密码
        if (!passwordEncoder.matches(userPassword, user.getUserPassword())) {
            log.info("User login failed: invalid credentials");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 被禁用的用户不允许登录。
        if (user.getUserStatus() != null && user.getUserStatus() != NORMAL_USER_STATUS) {
            throw new BusinessException(ErrorCode.NO_AUTH, "User account is disabled");
        }

        // 生成短期 JWT Access Token
        String accessToken = jwtTokenService.generateAccessToken(user);
        // 生成长期 Refresh Token，并将哈希保存到数据库。
        String refreshToken = refreshTokenService.createRefreshToken(user);

        // 返回 JWT 和脱敏后的用户信息。
        return UserLoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenService.getExpirationSeconds())
                .refreshExpiresIn(refreshTokenService.getRefreshExpirationSeconds())
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
            users = userRepository.findAllByIsDelete(User.NOT_DELETED);
        } else {
            users = userRepository.findByUsernameContainingAndIsDelete(username, User.NOT_DELETED);
        }

        return users.stream()
                .map(this::getUserDTO)
                .toList();
    }

    /**
     * 逻辑删除用户。
     */
    @Override
    @Transactional
    public boolean deleteUser(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid user ID");
        }

        int affectedRows = userRepository.softDeleteById(id);
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User does not exist");
        }
        // 撤销该用户全部有效的 Refresh Token。
        refreshTokenService.revokeAllByUserId(id);
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


