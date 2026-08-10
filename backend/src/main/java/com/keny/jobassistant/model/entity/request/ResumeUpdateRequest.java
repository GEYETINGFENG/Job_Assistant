package com.keny.jobassistant.model.entity.request;

import lombok.Data;

/**
 * 修改已有简历信息请求。
 */
@Data
public class ResumeUpdateRequest {

    /**
     * 新的简历名称。
     */
    private String resumeName;

    /**
     * 客户端读取简历时得到的乐观锁版本号。
     * 更新时必须仍然和数据库一致，否则说明期间已经有人修改过这份简历。
     */
    private Long lockVersion;
}