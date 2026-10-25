package com.keny.jobassistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * JD 列表游标分页结果，最新的在前。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<JobDTO> items;

    /**
     * 下一页请求时传入的 cursor；没有下一页时为 null。
     */
    private Long nextCursor;

    private boolean hasNext;
}
