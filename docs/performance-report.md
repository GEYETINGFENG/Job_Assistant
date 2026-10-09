# 性能压测报告

> 测试日期：2026-10-08 ｜ 工具：k6（阶梯加压）+ Micrometer / Prometheus 指标 + EXPLAIN ANALYZE
> 复现方法见 [loadtest/README.md](../loadtest/README.md)

## 结论

| | 修复前 | 修复后 |
|---|---|---|
| 简历列表峰值 QPS | ~3,500 | **~8,300（2.4×）** |
| 拐点并发 | ~20 VU | **~40 VU** |
| 20 VU 时 p95 | 8.2 ms | **3.8 ms（−54%）** |
| 列表 SQL 执行时间 | 1.64 ms（读 4,766 行再排序） | **0.19 ms（只读 21 行）** |
| 满载时 Postgres CPU | 775% | **342%**（吞吐翻倍以上，CPU 反而减半） |

> 简历 bullet：简历列表接口用复合部分索引 `(user_id, update_time DESC, id DESC) WHERE is_delete = 0` 消除排序，
> p95 从 8.2ms 降到 3.8ms，峰值吞吐从 3.5k 提升到 8.3k QPS，拐点并发从 20 提高到 40。

## 1. 测试环境与数据

- 单机：Docker Desktop（20 vCPU / 8GB），app + Postgres 18 + Redis 7 + k6 在同一台机器上运行，互相抢 CPU。
  绝对数值只代表这台机器，**修复前后的相对变化才是结论**。
- k6 容器加入 compose 网络，直接访问 `app:8080`。最初走 `host.docker.internal`，高并发下 Docker Desktop 端口转发出现 `dial: i/o timeout`，各级 QPS 忽高忽低，测到的是转发层，不是应用。
- 数据：20 个压测账号，每人 5,000 份简历（约 5% 已软删除），`resume` 表共约 10 万行。
- 上传压测期间关闭 AI 解析（`RESUME_AI_ENABLED=false`）。
- 每次压测前预热 20 秒（不计入统计）。未预热时第一级 QPS 偏低一半以上，原因是 JIT 尚未完成。

## 2. SLI / SLO

只看 p95 / p99 和错误率，不看平均值：尾部慢请求会被平均值掩盖。SLO 写进 k6 thresholds，不达标时 k6 以非 0 退出码结束。

| 接口 | SLO |
|---|---|
| `GET /resumes`（第一页 20 条） | p95 < 200 ms，错误率 < 1% |
| `POST /resumes/uploads/{id}/complete` | p95 < 800 ms，错误率 < 1%（同步调用东京 S3，网络往返本身约 600 ms） |
| `POST /user/login` | p95 < 300 ms，错误率 < 1% |

## 3. 可观测性

- `/actuator/prometheus` 暴露 Micrometer 指标。`http.server.requests` 开启直方图，可计算 p95 / p99；SLO 分界线为 100 / 200 / 500ms / 1s。
- 容器内（`json-log` profile）日志为 JSON，每条带 `requestId`（MDC，同时通过 `X-Request-Id` 响应头返回），一次请求的日志可以串起来。
- 定位瓶颈主要靠两个指标：
  - `hikaricp_connections_pending`：大于 0 说明请求在等数据库连接；
  - `hikaricp_connections_usage_seconds_sum / _count`：每次借出连接的平均占用时长。

## 4. 瓶颈一：简历列表排序没走索引（已修复）

### 定位

1. **接口指标**：20 VU 后 QPS 停在约 3,500，p95 从 8ms 涨到 66ms，延迟随并发线性增长，说明请求在排队。
2. **资源指标**：Postgres CPU 775%；Hikari 连接 10/10 全部占用，27 个请求在等待。数据库是最紧的资源。
3. **EXPLAIN ANALYZE**：

```
Limit (actual time=1.575..1.578 rows=21)
  ->  Sort  (Sort Key: update_time DESC, id DESC; top-N heapsort)
        ->  Index Scan using idx_resume_user_active  (rows=4766)   ← 为了 20 条读出全部 4766 行
              Index Cond: (user_id = 6)
Execution Time: 1.643 ms
```

`idx_resume_user_active` 只覆盖 `user_id`，`ORDER BY update_time` 只能把该用户的全部有效简历读出来再排序。

**额外发现：通用计划用不上部分索引。** JDBC 预编译执行 5 次后，PostgreSQL 可能改用通用计划。原查询把 `is_delete` 作为参数 `$2` 传入，部分索引的条件 `WHERE is_delete = 0` 无法匹配，计划退化为 `idx_resume_user_id` 加逐行 Filter（2.19ms）。

### 修复

- `V16__index_resume_user_update_time.sql`：新建 `(user_id, update_time DESC, id DESC) WHERE is_delete = 0`，同时删除被它完全覆盖的旧索引 `idx_resume_user_active`，减少写入时的索引维护。
- JPQL 中 `is_delete = 0` 改为字面量，保证通用计划也能使用部分索引。

```
Limit (actual time=0.085..0.139 rows=21)
  ->  Index Scan using idx_resume_user_active_update_time  (rows=21)   ← Sort 消失，读到 21 行就停
        Index Cond: (user_id = 6)
Execution Time: 0.193 ms        （通用计划：0.097 ms）
```

### 压测对比（GET /resumes，修复前 → 修复后）

| 并发 VU | QPS | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |
|---:|---:|---:|---:|---:|---:|
| 5 | 2,003 → 3,660 | 2.3 → 1.2 | 3.0 → 1.8 | 3.9 → 2.4 | 0% → 0% |
| 10 | 3,236 → 5,602 | 2.9 → 1.6 | 3.8 → 2.2 | 4.7 → 3.2 | 0% → 0% |
| 20 | 3,480 → 7,810 | 5.4 → 2.2 | 8.2 → 3.8 | 10.4 → 5.2 | 0% → 0% |
| 40 | 3,528 → 8,250 | 10.9 → 3.7 | 19.8 → 11.1 | 27.4 → 16.0 | 0% → 0% |
| 80 | 3,485 → 8,306 | 22.2 → 5.3 | 43.9 → 29.3 | 63.4 → 44.2 | 0% → 0% |
| 120 | 3,519 → 7,935 | 33.2 → 5.6 | 66.2 → 49.8 | 96.5 → 76.2 | 0% → 0% |

修复前后都满足 SLO（p95 < 200ms）。但修复前拐点在 20 VU，之后 QPS 不再增长，只有延迟在涨。

### 修复后瓶颈转移

满载（40 VU，约 8,300 QPS）时：app CPU 900%，Postgres CPU 342%，k6 CPU 336%，连接池 10/10、17 个排队。
瓶颈从数据库排序转移到**应用 CPU**（JWT 校验、Security 过滤链、JSON 序列化）和**只有 10 个连接的连接池**。整机 CPU 已接近用满，单机继续压测意义不大。

## 5. 瓶颈二：慢操作期间一直占着数据库连接（已定位，未修复）

另外两个接口的拐点都由同一个原因决定：**在做与数据库无关的慢操作时，一直占着数据库连接。**

### `complete`（基线）

| 并发 VU | QPS | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |
|---:|---:|---:|---:|---:|---:|
| 2 | 2 | 641 | 664 | 977 | 0% |
| 5 | 6 | 630 | 658 | 1,588 | 0% |
| 10 | 11 | 628 | 1,288 | 1,583 | 0% |
| 20 | 14 | 847 | 1,431 | 1,621 | 0% |
| 40 | 16 | 1,529 | 2,196 | 2,593 | 0% |

- 低并发时 p50 约 630ms，基本就是到东京 S3 的 2~3 次网络往返。
- 吞吐上限约 16 QPS ≈ 10 个连接 ÷ 0.63 秒。20 VU 时连接池 10/10，9 个排队，平均每次占用连接 315ms。
- 原因：Spring Boot 默认开启 `spring.jpa.open-in-view`（启动日志有 WARN），Hibernate Session 跟着整个请求，第一次拿到的连接一直持有到请求结束。代码里特意把 S3 调用放在两段短事务之外，但 OSIV 让这层设计失效了。

### `login`（基线）

| 并发 VU | QPS | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |
|---:|---:|---:|---:|---:|---:|
| 5 | 65 | 77 | 82 | 89 | 0% |
| 10 | 118 | 81 | 112 | 134 | 0% |
| 20 | 122 | 161 | 189 | 212 | 0% |
| 40 | 128 | 312 | 330 | 350 | 0% |
| 80 | 126 | 639 | 669 | 701 | 0% |
| 120 | 125 | 967 | 1,023 | 1,365 | 0% |

- 10 VU 之后吞吐停在约 125 QPS，40 VU 起 p95 超过 SLO（300ms）。
- 40 VU 时：连接池 10/10、32 个排队，平均每次占用连接 **77ms，正好是一次 BCrypt 的耗时**；app CPU 957%，只用了 20 核的一半。
- 原因：`userLogin` 整个方法是 `@Transactional`，做 BCrypt 时一直持有连接。125 QPS ≈ 10 个连接 ÷ 80ms。

### 修复方向（下一轮）

1. 关闭 OSIV：`spring.jpa.open-in-view: false`。需要先确认没有在事务外访问懒加载字段的代码。
2. 登录时先在事务外查用户、做 BCrypt，只把写 Refresh Token 的操作放进短事务。
3. 再看是否需要调大连接池（`maximum-pool-size`）。在连接被占着做慢操作的情况下，调大连接池只是掩盖问题。

## 6. 异步处理的吞吐上限

- 上传压测期间，`complete` 峰值约 16 个任务/秒进入队列，而 worker 每分钟只消化约 50 个（0.83 个/秒，AI 关闭，主要是 S3 往返）。
- 3 分钟压测积压约 1,500 个任务，全部消化需要约 30 分钟，最后一份要等半小时。开着 AI 会更慢，还会被 LLM 令牌桶限流。
- 「上传秒回」只是把等待从前端挪到后台队列。整体处理能力由 worker 并发数 × 单任务耗时决定，这个上限在下一课（异步管线吞吐）压测。
