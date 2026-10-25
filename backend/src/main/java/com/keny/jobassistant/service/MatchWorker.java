package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.exception.LlmRateLimitedException;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.model.ai.MatchAnalysisResult;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.model.entity.MatchRecord;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.model.task.MatchTaskClaim;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.MatchRecordRepository;
import com.keny.jobassistant.repository.MatchTaskRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.service.rag.ResumeIndexingService;
import com.keny.jobassistant.service.rag.ResumeRetriever;
import com.keny.jobassistant.service.support.TaskLifecycleGate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台处理 PENDING 的匹配任务。
 *
 * 投递语义是 at-least-once：任务持久化在数据库里，崩溃后由 MatchRecoveryCoordinator 重新入队；
 * 处理是幂等的：提交结果时核对 claim token，过期 worker 的结果会被丢弃，不会覆盖新 worker 的结果。
 */
@Slf4j
@Service
public class MatchWorker implements SmartLifecycle {

    public static final String LLM_RATE_LIMITED = "LLM_RATE_LIMITED";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final MatchTaskRepository matchTaskRepository;
    private final MatchRecordRepository matchRecordRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JobRepository jobRepository;
    private final MatchAnalyzer matchAnalyzer;
    private final ResumeIndexingService indexingService;
    private final ResumeRetriever retriever;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryBaseDelaySeconds;
    private final long retryMaxDelaySeconds;
    private final TaskLifecycleGate lifecycleGate = new TaskLifecycleGate("匹配 worker");

    public MatchWorker(MatchTaskRepository matchTaskRepository,
                       MatchRecordRepository matchRecordRepository,
                       ResumeVersionRepository resumeVersionRepository,
                       JobRepository jobRepository,
                       MatchAnalyzer matchAnalyzer,
                       ResumeIndexingService indexingService,
                       ResumeRetriever retriever,
                       ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.match.worker.batch-size:3}") int batchSize,
                       @Value("${app.match.worker.max-attempts:3}") int maxAttempts,
                       @Value("${app.match.worker.retry-base-delay-seconds:30}") long retryBaseDelaySeconds,
                       @Value("${app.match.worker.retry-max-delay-seconds:600}") long retryMaxDelaySeconds) {
        this.matchTaskRepository = matchTaskRepository;
        this.matchRecordRepository = matchRecordRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jobRepository = jobRepository;
        this.matchAnalyzer = matchAnalyzer;
        this.indexingService = indexingService;
        this.retriever = retriever;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseDelaySeconds = Math.max(1, retryBaseDelaySeconds);
        this.retryMaxDelaySeconds = Math.max(this.retryBaseDelaySeconds, retryMaxDelaySeconds);
    }

    /** 每轮领取少量任务顺序处理；多实例部署时 SKIP LOCKED 让不同实例领取不同记录。 */
    @Scheduled(fixedDelayString = "${app.match.worker.fixed-delay-ms:1000}")
    public void processPendingMatches() {
        for (int index = 0; index < batchSize; index++) {
            if (!lifecycleGate.tryBeginTask()) {
                return;
            }
            try {
                Optional<MatchTaskClaim> claim = claimNextTask();
                if (claim.isEmpty()) {
                    return;
                }
                processClaimedTask(claim.get());
            } finally {
                lifecycleGate.finishTask();
            }
        }
    }

    @Override
    public void start() {
        lifecycleGate.start();
    }

    @Override
    public void stop() {
        lifecycleGate.stop();
    }

    @Override
    public void stop(Runnable callback) {
        lifecycleGate.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return lifecycleGate.isRunning();
    }

    /** 领取只修改一行并立即提交；耗时的 LLM 调用在事务之外进行，不长时间占用连接和行锁。 */
    private Optional<MatchTaskClaim> claimNextTask() {
        return Optional.ofNullable(transactionTemplate.execute(status -> matchTaskRepository.claimNext(UUID.randomUUID()).orElse(null)));
    }

    void processClaimedTask(MatchTaskClaim claim) {
        try {
            AnalysisInput input = loadInput(claim);
            // RAG：首次匹配某个简历版本时切块并 embedding（之后复用），再为 JD 每条要求检索证据。
            indexingService.ensureIndexed(claim.resumeVersionId());
            List<RequirementEvidence> evidence = retriever.retrieve(claim.resumeVersionId(), input.jobDescription());
            MatchAnalysisResult result = matchAnalyzer.analyze(input.resumeContent(), input.jobTitle(), input.jobDescription(), evidence);
            if (saveCompleted(claim, result)) {
                log.info("匹配任务完成，matchId={}, score={}, promptTokens={}, completionTokens={}",
                        claim.matchId(), result.score(), result.promptTokens(), result.completionTokens());
            } else {
                log.info("忽略已经失效的 worker 结果，matchId={}, claimToken={}", claim.matchId(), claim.claimToken());
            }
        } catch (LlmRateLimitedException exception) {
            deferRateLimitedTask(claim, exception.getRetryAfterMillis());
        } catch (MatchAnalysisException exception) {
            handleFailure(claim, exception.getErrorCode(), exception.getMessage(), exception.isRetryable(), exception);
        } catch (BusinessException exception) {
            // 例如限流器、数据库暂时不可用：系统错误可以重试，其余业务错误直接失败。
            boolean retryable = exception.getCode() == ErrorCode.SYSTEM_ERROR.getCode();
            String message = StringUtils.isNotBlank(exception.getDescription()) ? exception.getDescription() : exception.getMessage();
            handleFailure(claim, Integer.toString(exception.getCode()), message, retryable, exception);
        } catch (RuntimeException exception) {
            handleFailure(claim, Integer.toString(ErrorCode.SYSTEM_ERROR.getCode()), "Unexpected match analysis failure", true, exception);
        }
    }

    private AnalysisInput loadInput(MatchTaskClaim claim) {
        AnalysisInput input = transactionTemplate.execute(status -> {
            JsonNode resumeContent = resumeVersionRepository.findById(claim.resumeVersionId())
                    .map(version -> version.getContentJson())
                    .orElse(null);
            return jobRepository.findById(claim.jobId())
                    .map(job -> new AnalysisInput(resumeContent, job.getJobTitle(), job.getDescription()))
                    .orElse(null);
        });
        if (input == null || input.resumeContent() == null || StringUtils.isBlank(input.jobDescription())) {
            throw new MatchAnalysisException("MATCH_INPUT_MISSING", "Resume content or job description is missing", false);
        }
        return input;
    }

    /** 最终提交时再次核对 claim token：超时的旧 worker 即使晚到也不能覆盖新 worker 的结果。 */
    private boolean saveCompleted(MatchTaskClaim claim, MatchAnalysisResult result) {
        Boolean saved = transactionTemplate.execute(status -> {
            MatchRecord record = lockOwnedRecord(claim);
            if (record == null) {
                return false;
            }
            record.setStatus(MatchStatus.COMPLETED);
            record.setScore(result.score());
            record.setAnalysisJson(objectMapper.valueToTree(result.analysis()));
            record.setPromptTokens(result.promptTokens());
            record.setCompletionTokens(result.completionTokens());
            record.setNextAttemptAt(null);
            record.setProcessingStartedAt(null);
            record.setClaimToken(null);
            record.setLastErrorCode(null);
            record.setLastErrorMessage(null);
            record.setUpdateTime(LocalDateTime.now());
            matchRecordRepository.save(record);
            return true;
        });
        return Boolean.TRUE.equals(saved);
    }

    /** 限流只是暂时没有额度：重新排队并退还本次计数，不算一次失败。 */
    private void deferRateLimitedTask(MatchTaskClaim claim, long retryAfterMillis) {
        long delayMillis = Math.max(1000, retryAfterMillis);
        Boolean deferred = transactionTemplate.execute(status -> {
            MatchRecord record = lockOwnedRecord(claim);
            if (record == null) {
                return false;
            }
            record.setStatus(MatchStatus.PENDING);
            record.setAttemptCount(Math.max(0, record.getAttemptCount() - 1));
            record.setNextAttemptAt(Instant.now().plusMillis(delayMillis));
            record.setProcessingStartedAt(null);
            record.setClaimToken(null);
            record.setLastErrorCode(LLM_RATE_LIMITED);
            record.setLastErrorMessage("Waiting for LLM request quota");
            record.setUpdateTime(LocalDateTime.now());
            matchRecordRepository.save(record);
            return true;
        });
        if (Boolean.TRUE.equals(deferred)) {
            log.info("匹配任务等待 LLM 额度，matchId={}, retryAfterMillis={}", claim.matchId(), delayMillis);
        }
    }

    /** 可重试的错误按指数退避重新入队；不可重试或次数用完时进入 DEAD。 */
    private void handleFailure(MatchTaskClaim claim, String errorCode, String errorMessage, boolean retryable, Exception exception) {
        boolean shouldRetry = retryable && claim.attemptCount() < maxAttempts;
        Boolean updated = transactionTemplate.execute(status -> {
            MatchRecord record = lockOwnedRecord(claim);
            if (record == null) {
                return false;
            }
            record.setStatus(shouldRetry ? MatchStatus.PENDING : MatchStatus.DEAD);
            record.setNextAttemptAt(shouldRetry ? Instant.now().plusSeconds(retryDelaySeconds(claim.attemptCount())) : null);
            record.setProcessingStartedAt(null);
            record.setClaimToken(null);
            record.setLastErrorCode(errorCode);
            record.setLastErrorMessage(truncate(errorMessage));
            record.setUpdateTime(LocalDateTime.now());
            matchRecordRepository.save(record);
            return true;
        });
        if (!Boolean.TRUE.equals(updated)) {
            log.info("忽略已经失效的 worker 失败结果，matchId={}, claimToken={}", claim.matchId(), claim.claimToken());
        } else if (shouldRetry) {
            log.warn("匹配任务失败，稍后重试，matchId={}, attempt={}, errorCode={}", claim.matchId(), claim.attemptCount(), errorCode, exception);
        } else {
            log.warn("匹配任务进入 DEAD，matchId={}, attempt={}, errorCode={}", claim.matchId(), claim.attemptCount(), errorCode, exception);
        }
    }

    /**
     * 加行锁读取任务，只有仍处于 PROCESSING 且 claim token 一致时才返回；否则说明任务已被恢复或其他 worker 接管。
     */
    private MatchRecord lockOwnedRecord(MatchTaskClaim claim) {
        return matchRecordRepository.findForUpdateById(claim.matchId())
                .filter(record -> record.getStatus() == MatchStatus.PROCESSING)
                .filter(record -> Objects.equals(record.getClaimToken(), claim.claimToken()))
                .orElse(null);
    }

    long retryDelaySeconds(int attemptCount) {
        long delay = retryBaseDelaySeconds;
        for (int index = 1; index < attemptCount && delay < retryMaxDelaySeconds; index++) {
            delay = Math.min(retryMaxDelaySeconds, delay * 2);
        }
        return delay;
    }

    private String truncate(String message) {
        if (StringUtils.isBlank(message)) {
            return "Match analysis failed";
        }
        String normalized = message.strip();
        return normalized.length() <= MAX_ERROR_MESSAGE_LENGTH ? normalized : normalized.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private record AnalysisInput(JsonNode resumeContent, String jobTitle, String jobDescription) {
    }
}
