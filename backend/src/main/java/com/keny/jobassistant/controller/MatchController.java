package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.MatchDTO;
import com.keny.jobassistant.model.dto.MatchPageDTO;
import com.keny.jobassistant.model.entity.request.MatchCreateRequest;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.service.MatchService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 简历与 JD 匹配接口（异步：202 + 轮询）。
 */
@RestController
@RequestMapping("/matches")
public class MatchController {

    @Resource
    private MatchService matchService;

    /**
     * 发起匹配。结果已经存在时返回 200；否则返回 202，客户端轮询 GET /matches/{id}。
     */
    @PostMapping
    public ResponseEntity<BaseResponse<MatchDTO>> createMatch(@RequestBody MatchCreateRequest request) {
        MatchDTO match = matchService.createMatch(request);
        HttpStatus status = match.getStatus() == MatchStatus.COMPLETED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ResultUtils.success(match));
    }

    /**
     * 轮询匹配状态和结果，直到 COMPLETED 或 DEAD。
     */
    @GetMapping("/{id}")
    public BaseResponse<MatchDTO> getMatch(@PathVariable Long id) {
        return ResultUtils.success(matchService.getMatch(id));
    }

    /**
     * 某个 JD 下的匹配记录，游标分页，最新的在前。
     */
    @GetMapping
    public BaseResponse<MatchPageDTO> listMatches(@RequestParam Long jobId,
                                                  @RequestParam(required = false) Long cursor,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ResultUtils.success(matchService.listMatches(jobId, cursor, size));
    }
}
