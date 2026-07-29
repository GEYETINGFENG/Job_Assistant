package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    /**
     * 根据 Refresh Token 的哈希值查询 Token 记录。一次关联查询，把两边的数据一起拿出来
     * 使用 PESSIMISTIC_WRITE（悲观写锁）保证同一个 Refresh Token在并发刷新时只能被一个请求处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT refreshToken
            FROM RefreshToken refreshToken
            JOIN FETCH refreshToken.user
            WHERE refreshToken.tokenHash = :tokenHash
            """)
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 当发现某个 Refresh Token 已经被使用过但再次出现,需要撤销同一个 family 下的所有 Token。
     * revokedAt IS NULL:只更新当前仍然有效的 Token，避免重复修改已经撤销的记录。
     * @return 被撤销的 Refresh Token 数量
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken refreshToken
            SET refreshToken.revokedAt = :revokedAt
            WHERE refreshToken.familyId = :familyId
              AND refreshToken.revokedAt IS NULL
            """)
    int revokeByFamilyId(@Param("familyId") UUID familyId, @Param("revokedAt") Instant revokedAt);

    /**
     * 根据用户 ID 撤销该用户的全部 Refresh Token。
     * @return 被撤销的 Refresh Token 数量
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken refreshToken
            SET refreshToken.revokedAt = :revokedAt
            WHERE refreshToken.user.id = :userId
              AND refreshToken.revokedAt IS NULL
            """)
    int revokeByUserId(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);
}