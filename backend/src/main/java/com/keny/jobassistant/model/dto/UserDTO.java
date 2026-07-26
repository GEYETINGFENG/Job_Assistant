package com.keny.jobassistant.model.dto;

import lombok.Data;

import java.time.LocalDateTime;


/**
 * User response DTO
 * 不返回密码和标记删除字段
 */
@Data
public class UserDTO {

    private Long id;
    /**
     * User nickname
     */
    private String username;

    /**
     * User account
     */
    private String userAccount;

    /**
     * Avatar URL
     */
    private String avatarUrl;

    /**
     * Gender
     */
    private Integer gender;

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
    private Integer userStatus;

    /**
     * User role
     */
    private Integer userRole;

    private LocalDateTime createTime;

}