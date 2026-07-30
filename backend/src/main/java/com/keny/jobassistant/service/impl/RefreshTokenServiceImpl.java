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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // 生成 Refresh Token 时使用的随机字节数量, 32 字节等于 256 bit
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

    // 从 application.yml中读取 Refresh Token 有效时间，单位为秒。
    @Value("${jwt.refresh-expiration-seconds}")
    private long refreshExpirationSeconds;

    // 从 application.yml 中读取并发刷新幂等窗口，默认 5 秒。
    @Value("${jwt.refresh-concurrency-grace-seconds:5}")
    private long refreshConcurrencyGraceSeconds;

    /**
     * Key：旧 Refresh Token 的 SHA-256 哈希,
     * Value：这次轮换产生的新 Token 组合及缓存信息。
     * 同一个旧 Refresh Token在5秒内再次提交时，直接返回第一次刷新产生的相同结果，不触发整族撤销。
     */
    private final Map<String, CachedRefreshResult> recentRefreshResults = new ConcurrentHashMap<>();

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

        // 清理已经超过 5 秒幂等窗口的缓存结果。
        removeExpiredRefreshResults();

        //检查缓存，获取仍处于 5 秒幂等窗口内的刷新结果
        //如果第一次刷新已经完成，稍晚到达的重复请求可以直接获得第一次刷新产生的相同 Token 组合
        TokenRefreshVO cachedResult = getCachedRefreshResult(tokenHash);
        if (cachedResult != null) {
            log.debug("命中 Refresh Token 短暂幂等缓存");
            return cachedResult;
        }

        /*
         * 根据 Token 哈希查询数据库。
         * Repository 中使用了悲观写锁，可以防止两个并发请求同时使用同一个 Refresh Token
         */
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));

        /*
         * 第二次检查缓存。
         * 两个标签页可能同时通过第一次缓存检查：
         * 1. 第一个请求获得悲观锁并完成 Token 轮换
         * 2. 第二个请求一直等待数据库锁
         * 3. 第二个请求获得锁以后，必须再次检查缓存
         * 如果这里命中缓存，直接返回第一次请求产生的 Token，不再将旧 Token 判断为复用攻击。
         */
        cachedResult = getCachedRefreshResult(tokenHash);
        if (cachedResult != null) {
            log.debug("获取悲观锁后命中 Refresh Token 短暂幂等缓存");
            return cachedResult;
        }
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

            // 整个 Token Family 已经被撤销，不应该再返回该 Family 的缓存结果。
            removeFamilyRefreshResults(storedToken.getFamilyId());
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

            // 整个 Token Family 已经失效，清除对应的缓存结果。
            removeFamilyRefreshResults(storedToken.getFamilyId());
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
                // 用户的全部登录会话都已失效，清除该用户的缓存结果。
                removeUserRefreshResults(user.getId());
            } else {
                // 无法确定用户 ID 时，至少撤销当前 Token Family。
                refreshTokenRepository.revokeByFamilyId(storedToken.getFamilyId(), now);
                // 清除当前 Token Family 的缓存结果。
                removeFamilyRefreshResults(storedToken.getFamilyId());
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

        // 创建新的 Token 组合。
        TokenRefreshVO refreshResult = TokenRefreshVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType(TOKEN_TYPE)
                .expiresIn(jwtTokenService.getExpirationSeconds())
                .refreshExpiresIn(refreshExpirationSeconds)
                .build();
        /*
         * 将本次成功刷新的结果保存到短暂幂等缓存。
         * 缓存 Key 使用旧 Token 的哈希，
         * 因此 5 秒内再次提交同一个旧 Token 时，
         * 会返回完全相同的 Access Token 和 Refresh Token。
         */
        cacheRefreshResult(tokenHash, refreshResult, storedToken.getFamilyId(), user.getId());
        return refreshResult;
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
        // 退出登录后不能再通过短暂缓存获得新的 Token。
        removeFamilyRefreshResults(storedToken.getFamilyId());
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
        // 用户被删除、禁用或强制下线后，清除其全部刷新缓存。
        removeUserRefreshResults(userId);
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

    /**
     * 将成功的刷新结果保存到短暂幂等缓存。
     * 缓存在事务提交前可见，这样等待悲观锁的第二个请求获得数据库锁后可以立即读取相同结果。
     * 如果数据库事务最终回滚，会自动删除本次缓存。
     */
    private void cacheRefreshResult(String oldTokenHash, TokenRefreshVO refreshResult, UUID familyId, Long userId) {
        CachedRefreshResult cachedResult = new CachedRefreshResult(
                copyTokenRefreshVO(refreshResult),
                Instant.now().plusSeconds(refreshConcurrencyGraceSeconds),
                familyId,
                userId
        );
        recentRefreshResults.put(oldTokenHash, cachedResult);

        /*
         * 如果当前正在 Spring 事务中，注册事务完成回调。
         * 事务提交成功：保留缓存。
         * 如果事务失败回滚，删除刚才写入的缓存。
         */
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            //注册事务监听器，等事务结束的时候，调用这里的回调方法
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    //如果事务失败就执行删除
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        recentRefreshResults.remove(oldTokenHash, cachedResult);
                    }
                }
            });
        }
    }

    // 获取仍处于 5 秒幂等窗口内的刷新结果
    private TokenRefreshVO getCachedRefreshResult(String oldTokenHash) {
        CachedRefreshResult cachedResult = recentRefreshResults.get(oldTokenHash);
        if (cachedResult == null) {
            return null;
        }
        // 缓存已经过期时，删除后按正常复用检测流程处理。
        if (!cachedResult.expiresAt().isAfter(Instant.now())) {
            recentRefreshResults.remove(oldTokenHash, cachedResult);
            return null;
        }
        return copyTokenRefreshVO(cachedResult.refreshResult());
    }

    //清除已经超过幂等窗口的缓存结果
    private void removeExpiredRefreshResults() {
        Instant now = Instant.now();
        //这里是遍历所有缓存的结果
        recentRefreshResults.entrySet().removeIf(entry ->
                !entry.getValue().expiresAt().isAfter(now)
        );
    }

    //清除指定 Token Family 的缓存结果
    private void removeFamilyRefreshResults(UUID familyId) {
        if (familyId == null) {
            return;
        }
        recentRefreshResults.entrySet().removeIf(entry ->
                familyId.equals(entry.getValue().familyId())
        );
    }

    // 等事务结束的时候，请调用我这里的回调方法
    private void removeUserRefreshResults(Long userId) {
        if (userId == null) {
            return;
        }
        recentRefreshResults.entrySet().removeIf(entry ->
                userId.equals(entry.getValue().userId())
        );
    }

    /**
     * 复制 TokenRefreshVO,在读写缓存中使用
     * 避免外部代码修改缓存中保存的可变 VO 对象。
     */
    private TokenRefreshVO copyTokenRefreshVO(TokenRefreshVO source) {
        return TokenRefreshVO.builder()
                .accessToken(source.getAccessToken())
                .refreshToken(source.getRefreshToken())
                .tokenType(source.getTokenType())
                .expiresIn(source.getExpiresIn())
                .refreshExpiresIn(source.getRefreshExpiresIn())
                .build();
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
    /**
     * 短暂幂等窗口内保存的刷新结果。
     * record 是只保存数据，不强调业务逻辑的小对象。
     * refreshResult：第一次刷新产生的 Token 组合。
     * expiresAt：缓存过期时间。
     * familyId：退出登录或撤销 Family 时用于清除缓存。
     * userId：用户被删除或强制下线时用于清除缓存。
     */
    private record CachedRefreshResult(
            TokenRefreshVO refreshResult,
            Instant expiresAt,
            UUID familyId,
            Long userId
    ) {
    }
}