package com.keny.jobassistant.model.entity;


import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User entity
 */
@Entity
@Table(name = "users")
@Data
public class User {


    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;


    /**
     * User nickname
     */
    private String username;


    /**
     * Login account
     */
    @Column(name = "user_account",
            unique = true,
            nullable = false)
    private String userAccount;


    /**
     * Avatar URL
     */
    @Column(name = "avatar_url")
    private String avatarUrl;


    /**
     * Gender
     */
    private Integer gender;


    /**
     * Encrypted password
     */
    @Column(name = "user_password",
            nullable = false)
    private String userPassword;


    /**
     * Email
     */
    private String email;


    /**
     * Phone
     */
    private String phone;


    /**
     * User status
     */
    @Column(name = "user_status")
    private Integer userStatus = 0;


    /**
     * User role
     */
    @Column(name = "user_role")
    private Integer userRole = 0;


    /**
     * Logical delete flag
     */
    @Column(name = "is_delete")
    private Integer isDelete = 0;


    /**
     * Created time
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;


    /**
     * Updated time
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

}