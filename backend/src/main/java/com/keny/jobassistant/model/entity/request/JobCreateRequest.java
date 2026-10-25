package com.keny.jobassistant.model.entity.request;

import lombok.Data;

/**
 * 提交 JD 请求。
 */
@Data
public class JobCreateRequest {

    private String companyName;

    private String jobTitle;

    /**
     * JD 原文。匹配时作为不可信数据发送给 LLM。
     */
    private String description;

    private String location;

    private String salary;
}
