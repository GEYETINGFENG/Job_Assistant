# LLM 全局令牌桶与任务重试

## 配置和调用流程

```powershell
$env:LLM_RATE_LIMIT_CAPACITY = '3'
$env:LLM_RATE_LIMIT_REFILL_PER_SECOND = '0.1'
```

默认允许突发 3 次，之后平均每 10 秒补充一个请求额度。这里的令牌代表一次模型请求，不是模型输入输出的 token 数；这也不是最大并发数限制。所有实例必须配置相同的容量、补充速率，并连接同一个 Redis 数据库。模型和 prompt 版本变化不会获得独立额度。

顺序为：Tika 校验与提取 → 文件 SHA-256 → 缓存查询 → 未命中时申请令牌 → LLM 请求 → 写缓存。缓存命中不申请令牌；LLM 返回错误、超时或非法 JSON 都不退还已消耗的令牌。

全局 key 固定为 `jobassistant:llm:bucket:global`，Hash 存放 `tokens`（含小数）和 `last_refill_ms`。单个 Lua 脚本使用 Redis `TIME` 原子完成补充、判断、扣减及保存，返回放行标记与等待毫秒数；应用内存不维护计数。

闲置 TTL 自动计算为 `2 × ceil(容量 / 每秒补充速度 × 1000) + 1000` 毫秒，默认 61 秒，严格大于从空桶补满所需的 30 秒。每次申请刷新 TTL。时间回拨时不重复补充时间段，并延长 TTL。状态损坏或 Redis 不可用会阻止模型请求，不会重建满桶放行。

算法参考 [Redis 官方令牌桶方案](https://redis.io/docs/latest/develop/use-cases/rate-limiter/redis-py/)，本项目增加了 Redis 服务端时间、等待时长和安全的闲置 TTL。

不要在运行期间主动删除桶 key 或给不同实例配置不同速率。Redis 数据丢失或 key 被淘汰后，下次请求会按新桶初始化为满容量；生产环境应避免令牌桶被缓存淘汰策略清理，并按需要配置持久化。

## Worker 行为

令牌不足时，解析器抛出 `LlmRateLimitedException`，携带 Redis 算出的等待时间。Worker 在普通失败处理前捕获它：

- 在短事务内锁定上传会话，核对仍为 `PROCESSING` 且 `claimToken` 一致。
- 改回 `PENDING`，`nextAttemptAt` 设置为当前时间加等待时长，至少 1 秒。
- 撤销领取时增加的那一次 `attemptCount`，已有失败次数保留；清除 `claimToken` 和 `processingStartedAt`。
- `lastErrorCode` 为 `LLM_RATE_LIMITED`；保留 S3 staging/processing 对象，只按原逻辑删除本轮本地临时文件。
- 立即结束本轮处理，不在线程内 sleep。到期只是重新竞争额度，不保证到期必定获准，也不保证任务间严格公平。

Redis 缓存读取失败、令牌桶故障或 Lua 返回非法结果属于系统错误，仍按现有指数退避处理，默认最多 3 次后进入 `DEAD`。LLM 成功后缓存或统计写入失败仍只记录日志，不推翻成功结果。

## Apifox 手工测试

上传和鉴权步骤沿用 [缓存验收指南](resume-cache-apifox.md)。限流在后台发生，上传 complete 通常返回 202，不通过该 HTTP 请求直接返回 429。

为了便于观察，可在独立开发环境临时设容量为 `1`、补充速率为 `0.01`（每 100 秒一个令牌），重启后端；确保没有其他实例使用不同参数。等待旧桶自然过期后开始测试，避免手动清除生产或共享桶。

1. 上传未缓存的简历 A，等待 `COMPLETED`。
2. 立即上传另一份内容不同、未缓存的简历 B，使用新的 `Idempotency-Key`。
3. 轮询 `GET /api/resumes/uploads/{uploadId}`。在额度不足时，应看到 `status=PENDING`、未来的 `nextAttemptAt`、`lastErrorCode=LLM_RATE_LIMITED`。若此前没有失败，`attemptCount` 应回到 0。
4. 后端日志应出现 `简历任务等待 LLM 额度`；B 在等待期间不应出现实际 LLM 调用日志。
5. 等待额度补充且任务再次领取后，B 应完成；成功时 `attemptCount` 增加到 1。
6. 在桶无额度期间重新上传已经成功缓存的 A，应直接通过缓存完成，无需等待令牌。

如果 A 的模型调用时间本身已超过补充周期，B 可能直接获准；可在独立测试环境进一步降低速率。测试后恢复默认容量 3、速率 0.1 并统一重启相关实例。

通过 Redis 客户端只读查看 `HGETALL jobassistant:llm:bucket:global` 和 `PTTL jobassistant:llm:bucket:global` 可确认共享状态。令牌按请求到来时惰性补充，所以两次请求之间 Hash 中的数字不会自动变化。

## 自动测试

Docker 可用时，在 `backend` 执行：

```powershell
mvn '-Dtest=LlmRateLimiterIntegrationTest,LlmRateLimiterFailureTest,ResumeUploadTaskStateMachineTest,ResumeParseCacheIntegrationTest,ResumeParseCacheFailureTest' test
mvn test
```

测试使用独享 Redis/PostgreSQL 容器和模拟模型 HTTP，不访问付费接口。覆盖独立 Redis 客户端并发共享额度、小数令牌补充、TTL、闲置回满上限、失败请求不退还、空桶时缓存命中、故障时不请求模型、重复限流不耗尽任务重试，以及失效 claim token 不得修改任务。
