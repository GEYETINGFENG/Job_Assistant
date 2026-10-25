package com.keny.jobassistant.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * JD 返回对象。
 */
@Data
public class JobDTO implements Serializable {

    private Long id;
    private String companyName;
    private String jobTitle;
    private String description;
    private String location;
    private String salary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
