package com.keny.jobassistant.model.entity.request;

import lombok.Data;

/**
 * 发起匹配请求。匹配对象是具体的简历版本，而不是简历本身。
 */
@Data
public class MatchCreateRequest {

    private Long resumeVersionId;

    private Long jobId;
}
