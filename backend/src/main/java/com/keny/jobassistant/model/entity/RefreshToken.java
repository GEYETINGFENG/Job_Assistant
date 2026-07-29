package com.keny.jobassistant.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token entity.
 */
@Entity
@Table(name = "refresh_tokens")
@Data
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Token owner.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * SHA-256 hash of the original refresh token.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * Token family identifier.
     */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /**
     * Token expiration time.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Token revocation time.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Token creation time.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}