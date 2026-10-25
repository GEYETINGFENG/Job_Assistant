package com.keny.jobassistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 匹配记录游标分页结果，最新的在前。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<MatchDTO> items;

    private Long nextCursor;

    private boolean hasNext;
}
