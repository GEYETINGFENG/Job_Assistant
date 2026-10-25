package com.keny.jobassistant.service;

import com.keny.jobassistant.model.dto.JobDTO;
import com.keny.jobassistant.model.dto.JobPageDTO;
import com.keny.jobassistant.model.entity.request.JobCreateRequest;

public interface JobService {

    /**
     * 当前用户提交一份 JD。
     */
    JobDTO createJob(JobCreateRequest request);

    /**
     * 查询当前用户自己的 JD。
     */
    JobDTO getJob(Long jobId);

    /**
     * 游标分页查询当前用户的 JD，最新的在前。
     *
     * @param cursor 上一页返回的 nextCursor；查询第一页时为 null
     */
    JobPageDTO listJobs(Long cursor, int size);
}
