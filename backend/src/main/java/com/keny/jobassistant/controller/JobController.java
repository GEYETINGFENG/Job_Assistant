package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.JobDTO;
import com.keny.jobassistant.model.dto.JobPageDTO;
import com.keny.jobassistant.model.entity.request.JobCreateRequest;
import com.keny.jobassistant.service.JobService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * JD 接口。只能访问当前用户自己的 JD。
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    @Resource
    private JobService jobService;

    /**
     * 提交 JD，返回 201。
     */
    @PostMapping
    public ResponseEntity<BaseResponse<JobDTO>> createJob(@RequestBody JobCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ResultUtils.success(jobService.createJob(request)));
    }

    @GetMapping("/{id}")
    public BaseResponse<JobDTO> getJob(@PathVariable Long id) {
        return ResultUtils.success(jobService.getJob(id));
    }

    /**
     * 游标分页查询 JD，最新的在前。第一页不传 cursor，之后传上一页返回的 nextCursor。
     */
    @GetMapping
    public BaseResponse<JobPageDTO> listJobs(@RequestParam(required = false) Long cursor,
                                             @RequestParam(defaultValue = "20") int size) {
        return ResultUtils.success(jobService.listJobs(cursor, size));
    }
}
