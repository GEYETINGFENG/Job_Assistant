package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.entity.RefreshToken;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.vo.TokenRefreshVO;
import com.keny.jobassistant.repository.RefreshTokenRepository;
import com.keny.jobassistant.service.JwtTokenService;
import com.keny.jobassistant.service.RefreshTokenService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh Token 服务实现类。
 * 主要负责：
 * 1. 生成高强度随机 Refresh Token
 * 2. 计算 Refresh Token 的 SHA-256 哈希
 * 3. 将 Token 哈希保存到数据库
 * 4. 执行 Refresh Token 轮换
 * 5. 检测旧 Token 是否被重复使用
 * 6. 撤销指定 Token Family
 * 7. 撤销用户的全部 Refresh Token
 */
@Service
@Slf4j
public class RefreshTokenServiceImpl implements RefreshTokenService {
    // 正常用户状态
    private static final int NORMAL_USER_STATUS = 0;

    //生成 Refresh Token 时使用的随机字节数量, 32 字节等于 256 bit
    private static final int REFRESH_TOKEN_BYTES = 32;

    // Token 类型，固定为 Bearer
    private static final String TOKEN_TYPE = "Bearer";

    // SecureRandom 生成密码学安全的随机值
    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private RefreshTokenRepository refreshTokenRepository;

    // JWT Access Token 生成服务
    @Resource
    private JwtTokenService jwtTokenService;

    // 从 application.yml中读取Refresh Token 有效时间，单位为秒。
    @Value("${jwt.refresh-expiration-seconds}")
    private long refreshExpirationSeconds;

    /**
     * 用户登录成功后创建第一个 Refresh Token。
     * 每次重新登录都会生成一个新的 familyId，
     * @param user 当前登录用户
     * @return 返回给客户端的原始 Refresh Token
     */
    @Override
    @Transactional
    public String createRefreshToken(User user) {
        // 用户已删除或被禁用时，不能生成 Refresh Token。
        if (!isUserAvailable(user)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        // 每次登录都创建一个新的 Token Family。
        UUID familyId = UUID.randomUUID();

        return createAndStoreToken(user, familyId);
    }

    /**
     * 使用旧 Refresh Token 换取新的 Token 组合。
     * 刷新成功后：
     * 1. 旧 Refresh Token 被标记为已撤销
     * 2. 生成同一 familyId 下的新 Refresh Token
     * 3. 生成新的 Access Token
     * 4. 将两个新 Token 返回给客户端
     *
     * noRollbackFor 的作用：
     * 检测到 Token 复用时，需要先撤销整个 Token Family，
     * 然后抛出 BusinessException 返回 HTTP 401。
     *
     * 如果不设置 noRollbackFor，抛出运行时异常后，前面执行的整族撤销操作也会被事务回滚。
     *
     * @param rawRefreshToken 客户端提交的原始 Refresh Token
     * @return 新的 Access Token 和 Refresh Token
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenRefreshVO refreshToken(String rawRefreshToken) {
        // 防止传入 null、空字符串或纯空格。
        validateRawToken(rawRefreshToken);
        // 数据库没有保存原始 Token，因此先计算 SHA-256 哈希。
        String tokenHash = hashToken(rawRefreshToken);

        /*
         * 根据 Token 哈希查询数据库。
         * Repository 中使用了悲观写锁，可以防止两个并发请求同时使用同一个 Refresh Token
         */
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));
        Instant now = Instant.now();

        /*
         * revokedAt 不为空，说明这个 Token 已经使用过或已经被撤销。
         * 一个已经作废的 Token 再次出现，可能意味着：
         * 1. 客户端错误地重复提交了旧 Token
         * 2. Refresh Token 被攻击者复制或盗取
         * 此时无法判断正常用户和攻击者谁持有最新 Token，直接撤销同一个 Token Family 下的全部有效 Token。
         */
        if (storedToken.getRevokedAt() != null) {
            refreshTokenRepository.revokeByFamilyId(storedToken.getFamilyId(), now);

            log.warn(
                    "检测到 Refresh Token 复用，familyId={}，userId={}",
                    storedToken.getFamilyId(),
                    storedToken.getUser().getId()
            );
            //当前请求携带的登录凭证已经失效，用户需要重新登录
            //所以说如果使用一个失效的refresh token 去申请refresh会直接抛用户未登录的异常
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }

        /*
         * expiresAt 为空或不晚于当前时间，说明 Refresh Token 已经过期。
         * 过期 Token 不能再用于生成新的 Access Token。
         */
        if (storedToken.getExpiresAt() == null || !storedToken.getExpiresAt().isAfter(now)) {
            refreshTokenRepository.revokeByFamilyId(storedToken.getFamilyId(), now);

            log.info(
                    "拒绝使用已过期的 Refresh Token，familyId={}，userId={}",
                    storedToken.getFamilyId(),
                    storedToken.getUser().getId()
            );
            // 当前请求携带的登录凭证已经过期失效，用户需要重新登录
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        // 通过 JOIN FETCH 查询出来的 Token 所属用户。
        User user = storedToken.getUser();

        /*
         * 用户被逻辑删除或被禁用后
         * 即使 Refresh Token 本身还没有过期，也不能继续获得新的Access Token。
         */
        if (!isUserAvailable(user)) {
            if (user != null && user.getId() != null) {
                // 能确定用户 ID 时，撤销该用户的全部 Refresh Token。
                refreshTokenRepository.revokeByUserId(user.getId(), now);
            } else {
                // 无法确定用户 ID 时，至少撤销当前 Token Family。
                refreshTokenRepository.revokeByFamilyId(storedToken.getFamilyId(), now);
            }
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        // Refresh Token Rotation 要求每个 Refresh Token 只能使用一次
        // 将当前 Refresh Token 标记为已撤销。
        storedToken.setRevokedAt(now);
        refreshTokenRepository.save(storedToken);

        /*
         * 创建新的 Refresh Token
         * 新 Token 继续使用旧 Token 的 familyId，表示它们属于同一次登录会话。
         */
        String newRefreshToken = createAndStoreToken(user, storedToken.getFamilyId());

        // 为当前用户生成新的短期 Access Token。
        String newAccessToken = jwtTokenService.generateAccessToken(user);

        // 将新的 Token 组合返回给客户端。
        return TokenRefreshVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType(TOKEN_TYPE)
                .expiresIn(jwtTokenService.getExpirationSeconds())
                .refreshExpiresIn(refreshExpirationSeconds)
                .build();
    }

    /**
     * 退出登录时撤销当前 Refresh Token 所属的整个 Token Family
     * 1. Token 存在时，撤销对应 Family
     * 2. Token 不存在时，直接返回成功
     * 3. Token 已撤销时，仍然可以继续撤销对应 Family
     * 这样不会通过退出接口向外部暴露某个 Token 是否真实存在。
     *
     * @param rawRefreshToken 客户端提交的原始 Refresh Token
     */
    @Override
    @Transactional
    public void revokeTokenFamily(String rawRefreshToken) {
        validateRawToken(rawRefreshToken);
        String tokenHash = hashToken(rawRefreshToken);
        Optional<RefreshToken> optionalToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash);
        // Token 不存在时，视为已经退出登录，啥也不用操作了
        if (optionalToken.isEmpty()) {
            return;
        }
        RefreshToken storedToken = optionalToken.get();
        // 撤销同一登录会话中的全部有效 Refresh Token。
        refreshTokenRepository.revokeByFamilyId(storedToken.getFamilyId(), Instant.now());
    }

    // 根据用户id撤销指定用户的全部有效 Refresh Token
    @Override
    @Transactional
    public void revokeAllByUserId(Long userId) {
        // 非法用户 ID 不执行数据库操作。
        if (userId == null || userId <= 0) {
            return;
        }
        refreshTokenRepository.revokeByUserId(userId, Instant.now());
    }

    // 获取 Refresh Token 的有效时间
    @Override
    public long getRefreshExpirationSeconds() {
        return refreshExpirationSeconds;
    }

    /**
     * 创建并保存一个 Refresh Token。
     * 1. 生成高强度随机原始 Token
     * 2. 计算原始 Token 的 SHA-256 哈希
     * 3. 将哈希保存到数据库
     * 4. 将原始 Token 返回给客户端
     * @return 返回给客户端的原始 Refresh Token
     */
    private String createAndStoreToken(User user, UUID familyId) {
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setFamilyId(familyId);
        refreshToken.setExpiresAt(now.plusSeconds(refreshExpirationSeconds));
        refreshToken.setRevokedAt(null);
        refreshToken.setCreatedAt(now);

        refreshTokenRepository.save(refreshToken);

        return rawToken;//这个返回给用户了
    }

    // 生成密码学安全的随机 Refresh Token
    private String generateRawToken() {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];//创建空字节数组
        secureRandom.nextBytes(randomBytes);// 用安全随机数填充数组
        // 把随机字节编码成可传输的字符串
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    // 计算 Refresh Token 的 SHA-256 哈希
    private String hashToken(String rawToken) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    // 校验客户端提交的 Refresh Token 是否为空
    private void validateRawToken(String rawRefreshToken) {
        if (StringUtils.isBlank(rawRefreshToken)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Refresh token cannot be blank");
        }
    }

    /**
     * 判断用户当前是否可以获得 Token。
     * 必须同时满足：用户对象存在，用户没有被逻辑删除，用户状态正常
     */
    private boolean isUserAvailable(User user) {
        return user != null
                && Integer.valueOf(User.NOT_DELETED).equals(user.getIsDelete())
                && Integer.valueOf(NORMAL_USER_STATUS).equals(user.getUserStatus());
    }
}