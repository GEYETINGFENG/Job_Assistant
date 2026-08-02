package com.keny.jobassistant.model.entity.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建简历请求。
 * 不允许客户端提交 userId，
 * 简历所有者必须从当前JWT中获取。
 */
@Data
public class ResumeCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    //简历名称
    private String resumeName;
}