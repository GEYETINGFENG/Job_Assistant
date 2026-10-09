package com.keny.jobassistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 简历分页结果。
 * 只返回 hasNext 而不返回总数，避免每次翻页都额外执行 count(*)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeListDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ResumeSummaryDTO> items;

    /**
     * 当前页码，从 0 开始。
     */
    private int page;

    private int size;

    /**
     * 是否还有下一页。
     */
    private boolean hasNext;
}
