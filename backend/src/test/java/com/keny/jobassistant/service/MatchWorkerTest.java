package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.exception.LlmRateLimitedException;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.model.ai.MatchAnalysis;
import com.keny.jobassistant.model.ai.MatchAnalysisResult;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.MatchRecord;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.model.task.MatchTaskClaim;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.MatchRecordRepository;
import com.keny.jobassistant.repository.MatchTaskRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.model.ai.EvidenceSnippet;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.service.rag.ResumeIndexingService;
import com.keny.jobassistant.service.rag.ResumeRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** worker 的状态流转：成功、可重试、不可重试、限流延后、过期 claim。 */
@ExtendWith(MockitoExtension.class)
class MatchWorkerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MATCH_ID = 42L;

    @Mock
    private MatchTaskRepository matchTaskRepository;
    @Mock
    private MatchRecordRepository matchRecordRepository;
    @Mock
    private ResumeVersionRepository resumeVersionRepository;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private MatchAnalyzer matchAnalyzer;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private ResumeIndexingService indexingService;
    @Mock
    private ResumeRetriever retriever;

    private MatchWorker worker;
    private UUID claimToken;
    private MatchRecord record;

    @BeforeEach
    void setUp() throws Exception {
        worker = new MatchWorker(matchTaskRepository, matchRecordRepository, resumeVersionRepository, jobRepository,
                matchAnalyzer, indexingService, retriever, MAPPER, transactionManager, 3, 3, 30, 600);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());

        claimToken = UUID.randomUUID();
        record = new MatchRecord();
        record.setId(MATCH_ID);
        record.setStatus(MatchStatus.PROCESSING);
        record.setClaimToken(claimToken);
        record.setAttemptCount(1);
        record.setProcessingStartedAt(Instant.now());
        lenient().when(matchRecordRepository.findForUpdateById(MATCH_ID)).thenReturn(Optional.of(record));

        ResumeVersion version = new ResumeVersion();
        version.setContentJson(MAPPER.readTree("{\"skills\": [\"Java\"]}"));
        lenient().when(resumeVersionRepository.findById(11L)).thenReturn(Optional.of(version));
        Job job = new Job();
        job.setJobTitle("Backend");
        job.setDescription("Java and Kafka");
        lenient().when(jobRepository.findById(3L)).thenReturn(Optional.of(job));
    }

    private MatchTaskClaim claim(int attempt) {
        return new MatchTaskClaim(MATCH_ID, claimToken, attempt, 7L, 11L, 3L);
    }

    @Test
    void successfulAnalysisShouldCompleteTaskWithScoreAndUsage() {
        MatchAnalysis analysis = new MatchAnalysis("Good", List.of("Java"), List.of("Kafka"), List.of(), List.of(), List.of());
        when(matchAnalyzer.analyze(any(), any(), any(), any())).thenReturn(new MatchAnalysisResult(70, analysis, 900, 200));

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(record.getScore()).isEqualTo(70);
        assertThat(record.getAnalysisJson().path("matchedSkills").get(0).asText()).isEqualTo("Java");
        assertThat(record.getPromptTokens()).isEqualTo(900);
        assertThat(record.getCompletionTokens()).isEqualTo(200);
        assertThat(record.getClaimToken()).isNull();
    }

    /** RAG：先索引简历版本，再按 JD 检索证据，证据原样交给分析器。 */
    @Test
    void workerShouldIndexRetrieveAndPassEvidenceToAnalyzer() {
        List<RequirementEvidence> evidence = List.of(new RequirementEvidence("Java experience", true,
                List.of(new EvidenceSnippet("EXPERIENCE", "Built Java services", 0.4))));
        when(retriever.retrieve(11L, "Java and Kafka")).thenReturn(evidence);
        MatchAnalysis analysis = new MatchAnalysis("Good", List.of("Java"), List.of(), List.of(), List.of(), evidence);
        when(matchAnalyzer.analyze(any(), eq("Backend"), eq("Java and Kafka"), eq(evidence)))
                .thenReturn(new MatchAnalysisResult(70, analysis, null, null));

        worker.processClaimedTask(claim(1));

        var inOrder = inOrder(indexingService, retriever, matchAnalyzer);
        inOrder.verify(indexingService).ensureIndexed(11L);
        inOrder.verify(retriever).retrieve(11L, "Java and Kafka");
        inOrder.verify(matchAnalyzer).analyze(any(), any(), any(), eq(evidence));
        assertThat(record.getAnalysisJson().path("requirements").get(0).path("requirement").asText()).isEqualTo("Java experience");
    }

    /** 语义 embedding 服务拒绝请求（例如 Key 无效）时，和 LLM 一样直接 DEAD。 */
    @Test
    void embeddingRejectionShouldGoDead() {
        when(indexingService.ensureIndexed(11L))
                .thenThrow(new MatchAnalysisException("LLM_REJECTED", "AI service rejected the request with HTTP 403", false));

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.DEAD);
        verifyNoInteractions(matchAnalyzer);
    }

    @Test
    void retryableFailureShouldRequeueWithBackoff() {
        when(matchAnalyzer.analyze(any(), any(), any(), any()))
                .thenThrow(new MatchAnalysisException("LLM_UNAVAILABLE", "AI service returned HTTP 503", true));

        Instant before = Instant.now();
        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(record.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(30));
        assertThat(record.getLastErrorCode()).isEqualTo("LLM_UNAVAILABLE");
    }

    /** API Key 无效（403）重试也不会成功：直接 DEAD，不浪费剩余的重试次数和额度。 */
    @Test
    void nonRetryableFailureShouldGoDeadImmediately() {
        when(matchAnalyzer.analyze(any(), any(), any(), any()))
                .thenThrow(new MatchAnalysisException("LLM_REJECTED", "AI service rejected the request with HTTP 403", false));

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.DEAD);
        assertThat(record.getNextAttemptAt()).isNull();
        assertThat(record.getLastErrorMessage()).contains("403");
    }

    @Test
    void retryableFailureOnLastAttemptShouldGoDead() {
        when(matchAnalyzer.analyze(any(), any(), any(), any()))
                .thenThrow(new MatchAnalysisException("LLM_INVALID_OUTPUT", "bad output", true));

        worker.processClaimedTask(claim(3));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.DEAD);
    }

    @Test
    void rateLimitShouldDeferWithoutCountingAnAttempt() {
        when(matchAnalyzer.analyze(any(), any(), any(), any())).thenThrow(new LlmRateLimitedException(5000));

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(record.getAttemptCount()).isZero();
        assertThat(record.getLastErrorCode()).isEqualTo(MatchWorker.LLM_RATE_LIMITED);
    }

    /** 超时后任务已被恢复并交给新 worker：旧 worker 晚到的结果不能覆盖。 */
    @Test
    void staleClaimShouldNotOverwriteNewerWorker() {
        record.setClaimToken(UUID.randomUUID());
        MatchAnalysis analysis = new MatchAnalysis("Good", List.of(), List.of(), List.of(), List.of(), List.of());
        when(matchAnalyzer.analyze(any(), any(), any(), any())).thenReturn(new MatchAnalysisResult(70, analysis, null, null));

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.PROCESSING);
        assertThat(record.getScore()).isNull();
        verify(matchRecordRepository, never()).save(any());
    }

    @Test
    void missingInputShouldGoDeadWithoutCallingAnalyzer() {
        when(jobRepository.findById(3L)).thenReturn(Optional.empty());

        worker.processClaimedTask(claim(1));

        assertThat(record.getStatus()).isEqualTo(MatchStatus.DEAD);
        assertThat(record.getLastErrorCode()).isEqualTo("MATCH_INPUT_MISSING");
        verifyNoInteractions(matchAnalyzer);
    }

    @Test
    void retryDelayShouldGrowExponentiallyAndCap() {
        assertThat(worker.retryDelaySeconds(1)).isEqualTo(30);
        assertThat(worker.retryDelaySeconds(2)).isEqualTo(60);
        assertThat(worker.retryDelaySeconds(3)).isEqualTo(120);
        assertThat(worker.retryDelaySeconds(10)).isEqualTo(600);
    }

    @Test
    void stoppedWorkerShouldNotClaimTasks() {
        worker.start();
        worker.stop(() -> { });

        worker.processPendingMatches();

        verifyNoInteractions(matchTaskRepository);
    }
}
