package com.keny.jobassistant.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.ai.MatchAnalysis;
import com.keny.jobassistant.model.dto.MatchDTO;
import com.keny.jobassistant.model.dto.MatchPageDTO;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.MatchRecord;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.entity.request.MatchCreateRequest;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.MatchRecordRepository;
import com.keny.jobassistant.repository.MatchTaskRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.MatchAnalyzer;
import com.keny.jobassistant.service.MatchQuotaService;
import com.keny.jobassistant.service.MatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class MatchServiceImpl implements MatchService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MatchRecordRepository matchRecordRepository;
    private final MatchTaskRepository matchTaskRepository;
    private final MatchQuotaService quotaService;
    private final JobRepository jobRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final MatchAnalyzer matchAnalyzer;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public MatchServiceImpl(MatchRecordRepository matchRecordRepository,
                            MatchTaskRepository matchTaskRepository,
                            MatchQuotaService quotaService,
                            JobRepository jobRepository,
                            ResumeVersionRepository resumeVersionRepository,
                            MatchAnalyzer matchAnalyzer,
                            CurrentUserProvider currentUserProvider,
                            ObjectMapper objectMapper) {
        this.matchRecordRepository = matchRecordRepository;
        this.matchTaskRepository = matchTaskRepository;
        this.quotaService = quotaService;
        this.jobRepository = jobRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.matchAnalyzer = matchAnalyzer;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 只在这里落一条 PENDING 记录，不调用 LLM；真正的分析由 MatchWorker 在后台完成。
     *
     * 幂等：INSERT ... ON CONFLICT DO NOTHING，两个相同请求并发时只有一个插入成功，另一个查出已有记录返回。
     * 额度：插入成功后才扣；额度不足抛异常使事务回滚，刚插入的记录一起撤销。
     */
    @Override
    @Transactional
    public MatchDTO createMatch(MatchCreateRequest request) {
        if (request == null || request.getJobId() == null || request.getJobId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Job id is invalid");
        }
        if (request.getResumeVersionId() == null || request.getResumeVersionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume version id is invalid");
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Job job = jobRepository.findByIdAndUser_Id(request.getJobId(), currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Job does not exist"));
        // 已软删除的简历不能再用于新的匹配。
        ResumeVersion resumeVersion = resumeVersionRepository
                .findByIdAndResume_User_IdAndResume_IsDelete(request.getResumeVersionId(), currentUserId, Resume.NOT_DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resume version does not exist"));

        var inserted = matchTaskRepository.insertIfAbsent(currentUserId, resumeVersion.getResume().getId(),
                resumeVersion.getId(), job.getId(), matchAnalyzer.engine(), matchAnalyzer.modelVersion(),
                matchAnalyzer.promptVersion());
        if (inserted.isPresent()) {
            quotaService.consume(currentUserId);
            return toMatchDTO(loadOwned(inserted.get(), currentUserId));
        }

        MatchRecord existing = matchRecordRepository
                .findByResumeVersion_IdAndJob_IdAndEngineAndPromptVersion(resumeVersion.getId(), job.getId(),
                        matchAnalyzer.engine(), matchAnalyzer.promptVersion())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "Match record disappeared"));
        if (existing.getStatus() == MatchStatus.DEAD) {
            return toMatchDTO(requeueDeadMatch(existing.getId(), currentUserId));
        }
        return toMatchDTO(existing);
    }

    @Override
    @Transactional(readOnly = true)
    public MatchDTO getMatch(Long matchId) {
        if (matchId == null || matchId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return toMatchDTO(loadOwned(matchId, currentUserProvider.getCurrentUserId()));
    }

    @Override
    @Transactional(readOnly = true)
    public MatchPageDTO listMatches(Long jobId, Long cursor, int size) {
        if (jobId == null || jobId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Job id is invalid");
        }
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Cursor must be positive");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!jobRepository.existsByIdAndUser_Id(jobId, currentUserId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Job does not exist");
        }
        PageRequest limit = PageRequest.of(0, size + 1);
        List<MatchRecord> records = cursor == null
                ? matchRecordRepository.findByUser_IdAndJob_IdOrderByIdDesc(currentUserId, jobId, limit)
                : matchRecordRepository.findByUser_IdAndJob_IdAndIdLessThanOrderByIdDesc(currentUserId, jobId, cursor, limit);

        boolean hasNext = records.size() > size;
        List<MatchDTO> items = records.stream().limit(size).map(this::toMatchDTO).toList();
        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new MatchPageDTO(items, nextCursor, hasNext);
    }

    /**
     * 用户重新发起已经 DEAD 的匹配：会再调用一次 LLM，所以同样消耗额度。
     * 加行锁后再检查状态，两个并发的重新发起只有一个会真正重置。
     */
    private MatchRecord requeueDeadMatch(Long matchId, Long currentUserId) {
        MatchRecord record = matchRecordRepository.findForUpdateById(matchId)
                .filter(match -> match.getUser().getId().equals(currentUserId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (record.getStatus() != MatchStatus.DEAD) {
            return record;
        }
        quotaService.consume(currentUserId);
        record.setStatus(MatchStatus.PENDING);
        record.setAttemptCount(0);
        record.setNextAttemptAt(Instant.now());
        record.setProcessingStartedAt(null);
        record.setClaimToken(null);
        record.setLastErrorCode(null);
        record.setLastErrorMessage(null);
        record.setUpdateTime(LocalDateTime.now());
        log.info("重新发起 DEAD 匹配任务，matchId={}", matchId);
        return matchRecordRepository.save(record);
    }

    private MatchRecord loadOwned(Long matchId, Long currentUserId) {
        return matchRecordRepository.findByIdAndUser_Id(matchId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private MatchDTO toMatchDTO(MatchRecord record) {
        MatchDTO dto = new MatchDTO();
        dto.setId(record.getId());
        dto.setStatus(record.getStatus());
        dto.setEngine(record.getEngine());
        dto.setJobId(record.getJob().getId());
        dto.setResumeId(record.getResumeId());
        dto.setResumeVersionId(record.getResumeVersion().getId());
        dto.setResumeVersionNumber(record.getResumeVersion().getVersionNumber());
        dto.setScore(record.getScore());
        dto.setAnalysis(readAnalysis(record));
        dto.setAttemptCount(record.getAttemptCount());
        dto.setLastErrorCode(record.getLastErrorCode());
        dto.setLastErrorMessage(record.getLastErrorMessage());
        dto.setCreateTime(record.getCreateTime());
        dto.setUpdateTime(record.getUpdateTime());
        return dto;
    }

    private MatchAnalysis readAnalysis(MatchRecord record) {
        if (record.getAnalysisJson() == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(record.getAnalysisJson(), MatchAnalysis.class);
        } catch (JsonProcessingException exception) {
            log.warn("匹配分析结果无法读取，matchId={}", record.getId());
            return null;
        }
    }
}
