package com.keny.jobassistant.service;

import com.keny.jobassistant.model.dto.MatchDTO;
import com.keny.jobassistant.model.dto.MatchPageDTO;
import com.keny.jobassistant.model.entity.request.MatchCreateRequest;

public interface MatchService {

    /**
     * 发起匹配（异步）。
     * 同一简历版本 + 同一 JD 已有结果时直接返回；进行中返回同一条任务；DEAD 时重新入队。
     * 返回的 status 为 COMPLETED 时表示结果已经可用，否则需要轮询 getMatch。
     */
    MatchDTO createMatch(MatchCreateRequest request);

    MatchDTO getMatch(Long matchId);

    /**
     * 游标分页查询某个 JD 下当前用户的匹配记录，最新的在前。
     */
    MatchPageDTO listMatches(Long jobId, Long cursor, int size);
}
