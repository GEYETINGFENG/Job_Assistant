package com.keny.jobassistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 事件历史游标分页结果。
 * 事件只追加、按时间倒序且可能很多，用游标而不是 offset：
 * 翻页期间新增的事件不会让已经翻过的页面重复或跳过。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationEventPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ApplicationEventDTO> items;

    /**
     * 下一页请求时传入的 cursor；没有下一页时为 null。
     */
    private Long nextCursor;

    private boolean hasNext;
}
