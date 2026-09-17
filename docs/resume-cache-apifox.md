# 简历解析缓存：启动与 Apifox 验收

本阶段只实现 AI 解析缓存和累计命中率，没有接入令牌桶。默认缓存 24 小时；同一文件的并发首次请求可能分别调用 LLM。

## 1. 启动环境

Docker Desktop 启动后，可以用下面的命令创建仅供本地使用的 Redis（只绑定本机）：

```powershell
docker run --name jobassistant-redis -p 127.0.0.1:6379:6379 -d redis:7.4-alpine
```

已创建过则使用 `docker start jobassistant-redis`，不要重复创建同名容器。此示例未配置持久化；删除容器会丢失缓存和统计。生产环境应根据需要配置 Redis 持久化和访问控制。

在启动后端的终端或 IDE 运行配置中设置：

```powershell
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''
$env:REDIS_DATABASE = '0'
$env:RESUME_AI_ENABLED = 'true'
$env:RESUME_AI_PROMPT_VERSION = 'v1'
$env:RESUME_AI_CACHE_TTL = '24h'
```

沿用已有的 `DATABASE_PASSWORD`、`JWT_SECRET`、`AWS_S3_BUCKET`、`AWS_REGION`、AWS 凭据和 `DASHSCOPE_API_KEY` 配置。`BAILIAN_MODEL` 默认 `qwen3.7-flash-2026-07-15`，也可以指定账号实际可用的固定版本。后端启动命令为在 `backend` 目录执行 `mvn spring-boot:run`。不要把真实凭据提交到仓库。

`RESUME_AI_ENABLED=false` 时只运行原有 Tika 解析，不读写解析缓存，也不累计 hit/miss；主动访问统计接口仍需 Redis。

## 2. Apifox 登录与初始统计

设置环境变量 `baseUrl = http://localhost:8080/api`。

`POST {{baseUrl}}/user/login`，Body 选 JSON：

```json
{
  "userAccount": "你的账号",
  "userPassword": "你的密码"
}
```

从响应 `data.accessToken` 保存 `accessToken`，后端接口的 Auth 选择 Bearer Token，值为 `{{accessToken}}`。统计查询必须使用已有管理员账号的 Token，可单独保存为 `adminAccessToken`。普通用户可以上传，但访问统计返回 403；未登录返回 401。不要把 Refresh Token 当成 Access Token。

`GET {{baseUrl}}/admin/resume-cache/stats`，Bearer Token 使用 `{{adminAccessToken}}`。记录初始 hit/miss；全新版本没有统计时返回：

```json
{
  "code": 0,
  "data": {
    "modelVersion": "qwen3.7-flash-2026-07-15",
    "promptVersion": "v1",
    "hit": 0,
    "miss": 0,
    "total": 0,
    "hitRate": 0.0
  },
  "message": "ok",
  "description": ""
}
```

## 3. 首次上传：预期 miss

选择未缓存、可提取正文的 PDF 或 DOCX。用文件属性或 PowerShell `(Get-Item '你的简历完整路径').Length` 获取真实字节数。

`POST {{baseUrl}}/resumes/uploads/presign`，Header 添加 `Idempotency-Key: cache-test-001`，Body 选 JSON：

```json
{
  "resumeName": "缓存验收第一次",
  "filename": "resume.pdf",
  "fileSize": 12345
}
```

将 `filename` 和 `fileSize` 替换为实际值。保存响应中的 `data.uploadId`、`data.uploadUrl` 和 `data.requiredHeaders`。

新建 PUT 请求，URL 完整粘贴 `uploadUrl`，不要添加 `baseUrl` 或修改 URL 查询参数。Auth 选择 **No Auth**，不能继承后端 Bearer Token；Body 选择 **binary** 并选择原文件，不能用 multipart/form-data。将 `requiredHeaders` 中每个键值原样加入请求头，然后发送。

上传成功后调用 `POST {{baseUrl}}/resumes/uploads/{{uploadId}}/complete`，使用后端 Bearer Token，无需请求体。202 表示后台处理尚未结束。

轮询 `GET {{baseUrl}}/resumes/uploads/{{uploadId}}`，直到 `data.status=COMPLETED`；若为 `DEAD`，检查 `lastErrorCode`、`lastErrorMessage` 和后端日志。

无其他解析和重试时，统计 miss 比初始增加 1，hit 不变，日志包含：

```text
简历解析缓存 miss，modelVersion=..., promptVersion=v1
开始调用简历解析 LLM，model=...
```

## 4. 第二次上传：预期 hit

必须等第一次 `COMPLETED` 后，使用新的 `Idempotency-Key: cache-test-002`，重新执行申请地址、PUT 同一文件、complete、轮询的完整流程。两次上传的文件字节必须完全一致；重新导出 PDF 可能改变字节，即使肉眼看起来相同。

**重复调用第一次的 complete 不会重新解析，不能用来证明缓存命中。**

第二个任务完成后，hit 应比初始增加 1，miss 应只比初始增加 1。若初始为零，结果应为：

```json
{
  "hit": 1,
  "miss": 1,
  "total": 2,
  "hitRate": 0.5
}
```

第二次日志出现 `简历解析缓存 hit`，不出现新的 `开始调用简历解析 LLM`。命中时仍执行 Tika 校验，也会按原流程创建上传任务对应的简历记录；缓存只复用解析结果。

## 5. 补充验证与统计口径

- 文件改名但扩展名仍正确：字节未变应命中；把 PDF 改成 `.docx` 应被安全检查拒绝。
- 修改文件内容：新摘要，应 miss。
- 将 `RESUME_AI_PROMPT_VERSION` 改为 `v2` 后重启：同文件应 miss，统计接口显示 v2 的独立计数；旧缓存等待 TTL 到期。
- 测试过期可临时设置 `RESUME_AI_CACHE_TTL=10s`，重启后使用新的 prompt 版本创建缓存，等待超过 10 秒再上传，预期 miss。新 TTL 不会修改已有缓存的到期时间。
- Redis 读取失败：不调用 LLM，不伪记 miss，由现有任务重试，默认最多 3 次后 DEAD。恢复 Redis 后，已 DEAD 的任务需要新建上传任务。
- LLM 失败或输出非法 JSON：计入这次查询的 miss，但不缓存结果。LLM 成功后缓存写入失败只记日志，不使上传失败。
- 统计写入失败会单独记录日志，此时累计值可能少计；查询统计失败返回错误，不返回伪造的零值。
- hitRate 为 `hit/(hit+miss)`，0.5 表示 50%。统计按有效缓存查询次数计算，包含后台重试；不是用户上传成功率或模型调用节省比例。
- 统计不设 TTL，跨应用重启保留，但 Redis 清空、淘汰或未持久化重启可能丢失。多人同时测试时，用增量核对并检查是否有其他任务。

修改 system/user prompt、结构化字段、文本截断长度、输出 token 上限、思考模式或其他影响解析结果的设置时，必须同步升级 prompt 版本。model 直接使用实际请求模型；若使用供应商会自动更新的模型别名，别名不变无法自动失效，应换固定版本或主动升级 prompt 版本。

## 6. 自动测试

Docker 可用时，在 `backend` 目录执行：

```powershell
mvn '-Dtest=ResumeParseCacheIntegrationTest,ResumeParseCacheFailureTest,ResumeCacheAdminControllerTest' test
mvn test
```

缓存集成测试创建独享的临时 Redis 容器，使用真实 Tika 读取生成的 PDF，使用模拟 HTTP 服务响应模型请求；不会清理开发 Redis 或调用付费百炼。测试严格验证顺序解析两次只发生一次模型 HTTP 请求，同时覆盖 TTL、版本隔离、缓存损坏和并发统计。故障与权限测试无需 Docker。
