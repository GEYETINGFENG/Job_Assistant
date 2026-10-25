package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.MatchDTO;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.MatchRecord;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.MatchCreateRequest;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.MatchRecordRepository;
import com.keny.jobassistant.repository.MatchTaskRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.impl.MatchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 发起匹配的业务逻辑：幂等返回、DEAD 重新发起、额度只在会调用 LLM 时扣。 */
@ExtendWith(MockitoExtension.class)
class MatchServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long JOB_ID = 3L;
    private static final Long VERSION_ID = 11L;
    private static final String PROMPT_VERSION = "keyword-v1";

    @Mock
    private MatchRecordRepository matchRecordRepository;
    @Mock
    private MatchTaskRepository matchTaskRepository;
    @Mock
    private MatchQuotaService quotaService;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private ResumeVersionRepository resumeVersionRepository;
    @Mock
    private MatchAnalyzer matchAnalyzer;
    @Mock
    private CurrentUserProvider currentUserProvider;

    private MatchServiceImpl service;
    private Job job;
    private ResumeVersion version;

    @BeforeEach
    void setUp() {
        service = new MatchServiceImpl(matchRecordRepository, matchTaskRepository, quotaService, jobRepository,
                resumeVersionRepository, matchAnalyzer, currentUserProvider, new ObjectMapper());
        job = new Job();
        job.setId(JOB_ID);
        Resume resume = new Resume();
        resume.setId(5L);
        version = new ResumeVersion();
        version.setId(VERSION_ID);
        version.setVersionNumber(2);
        version.setResume(resume);
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        lenient().when(matchAnalyzer.engine()).thenReturn(MatchEngine.KEYWORD);
        lenient().when(matchAnalyzer.promptVersion()).thenReturn(PROMPT_VERSION);
    }

    private void ownedInputs() {
        when(jobRepository.findByIdAndUser_Id(JOB_ID, USER_ID)).thenReturn(Optional.of(job));
        when(resumeVersionRepository.findByIdAndResume_User_IdAndResume_IsDelete(VERSION_ID, USER_ID, Resume.NOT_DELETED))
                .thenReturn(Optional.of(version));
    }

    private MatchRecord record(MatchStatus status) {
        User user = new User();
        user.setId(USER_ID);
        MatchRecord record = new MatchRecord();
        record.setId(99L);
        record.setUser(user);
        record.setJob(job);
        record.setResumeVersion(version);
        record.setResumeId(5L);
        record.setStatus(status);
        record.setEngine(MatchEngine.KEYWORD);
        record.setAttemptCount(3);
        return record;
    }

    @Test
    void newMatchShouldInsertPendingTaskAndConsumeQuota() {
        ownedInputs();
        when(matchTaskRepository.insertIfAbsent(USER_ID, 5L, VERSION_ID, JOB_ID, MatchEngine.KEYWORD, null, PROMPT_VERSION))
                .thenReturn(Optional.of(99L));
        when(matchRecordRepository.findByIdAndUser_Id(99L, USER_ID)).thenReturn(Optional.of(record(MatchStatus.PENDING)));

        MatchDTO result = service.createMatch(request());

        assertThat(result.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(result.getResumeVersionNumber()).isEqualTo(2);
        verify(quotaService).consume(USER_ID);
    }

    @Test
    void completedMatchShouldBeReturnedWithoutConsumingQuota() {
        ownedInputs();
        when(matchTaskRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        MatchRecord completed = record(MatchStatus.COMPLETED);
        completed.setScore(80);
        when(matchRecordRepository.findByResumeVersion_IdAndJob_IdAndEngineAndPromptVersion(VERSION_ID, JOB_ID, MatchEngine.KEYWORD, PROMPT_VERSION))
                .thenReturn(Optional.of(completed));

        MatchDTO result = service.createMatch(request());

        assertThat(result.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(result.getScore()).isEqualTo(80);
        verifyNoInteractions(quotaService);
    }

    @Test
    void inProgressMatchShouldReturnSameTaskWithoutConsumingQuota() {
        ownedInputs();
        when(matchTaskRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(matchRecordRepository.findByResumeVersion_IdAndJob_IdAndEngineAndPromptVersion(any(), any(), any(), any()))
                .thenReturn(Optional.of(record(MatchStatus.PROCESSING)));

        assertThat(service.createMatch(request()).getId()).isEqualTo(99L);
        verifyNoInteractions(quotaService);
    }

    /** 重新发起 DEAD 任务会再调用一次 LLM，所以要扣额度并重置重试次数。 */
    @Test
    void deadMatchShouldBeRequeuedAndConsumeQuota() {
        ownedInputs();
        MatchRecord dead = record(MatchStatus.DEAD);
        dead.setLastErrorCode("LLM_REJECTED");
        when(matchTaskRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(matchRecordRepository.findByResumeVersion_IdAndJob_IdAndEngineAndPromptVersion(any(), any(), any(), any()))
                .thenReturn(Optional.of(dead));
        when(matchRecordRepository.findForUpdateById(99L)).thenReturn(Optional.of(dead));
        when(matchRecordRepository.save(dead)).thenReturn(dead);

        MatchDTO result = service.createMatch(request());

        assertThat(result.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(dead.getAttemptCount()).isZero();
        assertThat(dead.getLastErrorCode()).isNull();
        assertThat(dead.getNextAttemptAt()).isNotNull();
        verify(quotaService).consume(USER_ID);
    }

    @Test
    void quotaExceededShouldPropagateSoTransactionRollsBackTheInsert() {
        ownedInputs();
        when(matchTaskRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(99L));
        doThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED)).when(quotaService).consume(USER_ID);

        assertThatThrownBy(() -> service.createMatch(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED.getCode()));
    }

    @Test
    void otherUsersJobShouldReturnNotFound() {
        when(jobRepository.findByIdAndUser_Id(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMatch(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
        verifyNoInteractions(matchTaskRepository, quotaService);
    }

    @Test
    void deletedResumeVersionShouldReturnNotFound() {
        when(jobRepository.findByIdAndUser_Id(JOB_ID, USER_ID)).thenReturn(Optional.of(job));
        when(resumeVersionRepository.findByIdAndResume_User_IdAndResume_IsDelete(VERSION_ID, USER_ID, Resume.NOT_DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMatch(request())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(matchTaskRepository, quotaService);
    }

    @Test
    void invalidIdsShouldBeRejected() {
        MatchCreateRequest missingVersion = new MatchCreateRequest();
        missingVersion.setJobId(JOB_ID);

        assertThatThrownBy(() -> service.createMatch(missingVersion)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createMatch(null)).isInstanceOf(BusinessException.class);
    }

    private static MatchCreateRequest request() {
        MatchCreateRequest request = new MatchCreateRequest();
        request.setJobId(JOB_ID);
        request.setResumeVersionId(VERSION_ID);
        return request;
    }
}
