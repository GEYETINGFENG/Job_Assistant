# 压测（k6）

阶梯加压找拐点：每个脚本先预热 20 秒（不计入统计），再按 5 → 10 → 20 → 40 → 80 → 120 VU 逐级加压，每级 30 秒。
每一级单独统计 QPS、p50/p95/p99 和错误率，用 `report.py` 整理成拐点表。SLO 写在各脚本的 thresholds 里，不达标时 k6 以非 0 退出码结束。

| 脚本 | 接口 | SLO |
|---|---|---|
| `k6/resume-list.js` | `GET /resumes?page=0&size=20` | p95 < 200ms，错误率 < 1% |
| `k6/upload-complete.js` | `POST /resumes/uploads/{id}/complete`（每次迭代先 presign + PUT 到 S3，只统计 complete） | p95 < 800ms，错误率 < 1% |
| `k6/login.js` | `POST /user/login` | p95 < 300ms，错误率 < 1% |

## 步骤（在项目根目录执行）

1. `.env` 中设置 `RESUME_AI_ENABLED=false`，然后 `docker compose up -d`。
   上传压测会产生上千个任务，开着 AI 会调用上千次模型。
2. 准备数据：20 个压测账号（`loadtest01`~`loadtest20`），每人 5000 份简历。

   ```bash
   python loadtest/seed.py
   ```

3. 运行压测。k6 容器加入 compose 网络，直接访问 `app:8080`。
   不要走 `host.docker.internal`：Docker Desktop 的端口转发在高并发下会出现 `dial: i/o timeout`，测到的是转发层而不是应用。

   ```bash
   MSYS_NO_PATHCONV=1 docker run --rm --network job_assistant_default -v "$PWD/loadtest/k6:/scripts" -v "$PWD/loadtest/results:/results" -e BASE_URL=http://app:8080/api -e RUN_LABEL=before grafana/k6 run --quiet /scripts/resume-list.js
   ```

   - `RUN_LABEL` 决定结果文件名：`loadtest/results/<接口>-<label>.json`。
   - `STEPS=2,5,10`、`STEP_SECONDS=20`、`WARMUP_SECONDS=10` 可以调整阶梯。
   - PowerShell 中把 `$PWD` 换成 `${PWD}`，去掉 `MSYS_NO_PATHCONV=1`。

4. 生成拐点表，或对比修复前后：

   ```bash
   python loadtest/report.py loadtest/results/list-before.json loadtest/results/list-after.json
   ```

5. 结束后清理压测账号、简历、上传任务，以及 S3 中这些账号的对象：

   ```bash
   python loadtest/cleanup.py
   ```

   然后把 `.env` 中的 `RESUME_AI_ENABLED` 改回原值。

## 压测时同时观察

- `curl -s localhost:8080/api/actuator/prometheus | grep hikaricp_connections`：`pending > 0` 说明请求在等数据库连接。
- `hikaricp_connections_usage_seconds_sum / _count`：每次借出连接的平均占用时长，明显大于 SQL 耗时说明连接在等别的东西。
- `docker stats`：app / postgres / k6 各自的 CPU。k6 和应用在同一台机器上运行，会互相抢 CPU。
