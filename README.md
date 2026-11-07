# Helpdesk

[![ci](https://github.com/WhereLee/gtry/actions/workflows/ci.yml/badge.svg)](https://github.com/WhereLee/gtry/actions/workflows/ci.yml)

多租户 AI 工单与客服平台。一套系统服务多个客户（租户），每个租户有自己的知识库、客服坐席、AI 配置、用量预算和数据边界。

当前阶段：**后端主链路已跑通**，知识库采用 wiki 模型（空间/页面/小节），
检索是“bigram 全文 + 向量”混合召回 + RRF + `bge-reranker-base` 重排，
LLM 使用 DeepSeek `deepseek-flash`。

## 架构

```text
客户端 / 客服工作台
        │  JWT（tenant_id + user_id + role）
        ▼
Spring Boot 3 (Java 17)
  ├─ TenantFilter        解析 JWT，写入 TenantContext
  ├─ TenantAwareDataSource  每次取连接设置 app.tenant_id / app.platform_admin
  ├─ Ticket / Wiki / AI / Quota
  ├─ Embedding: bge-base-zh-v1.5 (ONNX, 768d)
  ├─ Rerank:    bge-reranker-base (ONNX, cross-encoder)
  ├─ Hybrid retrieval: bigram tsvector + pgvector + RRF
  └─ PostgreSQL 18 + pgvector
        ├─ RLS 策略：tenant_id = current_setting('app.tenant_id')
        └─ kb_chunk.embedding vector(512) + HNSW
```

## 多租户是怎么保证的

三层，缺一不可：

1. **入口**：JWT 里的 `tid` 决定租户，不接受请求体传入的租户。
2. **连接**：`TenantAwareDataSource` 在每次取连接时执行
   `set_config('app.tenant_id', ?, false)`，归还连接前 `RESET`。
3. **数据库**：所有业务表启用 RLS，策略为
   `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`。
   应用连接使用非超级用户 `helpdesk_app`，所以策略真正生效（超级用户会绕过 RLS）。

`TenantIsolationIT` 用真实 PostgreSQL 验证：跨租户读返回空、跨租户写被拒、无租户上下文看不到任何数据。

## 快速启动

前置：JDK 17、Maven、PostgreSQL 18 + pgvector、Redis。

1. 创建数据库（角色和表由 Flyway 创建）：

```sql
CREATE DATABASE helpdesk;
```

2. 启动：

```powershell
mvn -q -DskipTests package
java -jar target\helpdesk-0.1.0.jar
```

或 `tools\start.ps1`。默认端口 `8091`，健康检查 `http://127.0.0.1:8091/actuator/health`。

打开 `http://127.0.0.1:8091/` 就是客服工作台：工单列表、新建工单、
AI 流式建议和引用来源；顶部可以切到“知识库”做 wiki 页面维护和检索测试。
前端是 Vue 3 + Vite + TypeScript + Element Plus；`mvn package` 会在
`prepare-package` 阶段自动执行 `npm ci && npm run build`，并把产物复制进 jar。

客户门户：`http://127.0.0.1:8091/portal.html?channel=acme-demo-key`
（cloudwise 用 `cloudwise-demo-key`）。客户提交后拿到工单 ID 和查询码，
可以查询进度、追加回复，工单关闭后提交 CSAT。

API 文档：`http://127.0.0.1:8091/swagger-ui.html`，OpenAPI JSON 在 `/v3/api-docs`。

## 前端开发

```text
web/
  index.html          坐席工作台入口
  portal.html         客户门户入口
  src/
    App.vue           工作台外壳、角色菜单、登录态
    PortalApp.vue     客户门户
    views/            工单、知识库、用量、报表、团队、AI 设置、平台
    api.ts            统一鉴权、错误码和下载
    types.ts          后端 DTO 的 TypeScript 类型
    format.ts         分类/优先级/状态/成本/时间格式化
```

本地开发时，先启动后端 `8091`，再：

```powershell
cd web
npm install
npm run dev        # http://127.0.0.1:5175，/api 代理到 8091
```

生产构建：

```powershell
cd web
npm run type-check
npm run build      # 输出 web/dist
```

`mvn package` 会自动完成上述构建；只有 `mvn test` 不会构建前端。

浏览器回归（Playwright）：

```powershell
cd web
npx playwright install chromium
npm run e2e
```

默认会在 `8092` 端口启动一个使用 `helpdesk_e2e` 数据库、mock embedding/LLM 的实例。
如果要跑已经启动的服务，设置 `E2E_BASE_URL`，例如
`$env:E2E_BASE_URL='http://127.0.0.1:8091'`。

## Docker 部署

`deploy/` 下是一套可直接上服务器的 Compose：

```text
deploy/
  Dockerfile            运行镜像（JRE 17 + jar）
  docker-compose.yml    postgres(pgvector) + redis + app
  .env.example          密码、JWT、DeepSeek Key
  helpdesk-nginx.conf   /helpdesk/ 反向代理（SSE 已关闭 buffering）
```

```bash
cd /opt/helpdesk
# 1) 放文件
#    app/helpdesk.jar
#    models/bge-base-zh-v1.5-onnx-int8/
#    models/bge-reranker-base-onnx-int8/
# 2) 配置
cp .env.example .env && chmod 600 .env
# 3) 启动
sudo docker compose up -d --build
curl -s http://127.0.0.1:8091/actuator/health
```

Postgres 和 Redis 不暴露公网端口，只通过 Compose 网络给 app 使用；
宿主 Nginx 把 `/helpdesk/` 反代到 `127.0.0.1:8091`。

默认连接：

| 用途 | 用户 | 密码 |
|---|---|---|
| Flyway 迁移 | `postgres` | `root` |
| 应用运行 | `helpdesk_app` | `helpdesk_app` |

## 演示账号

| 租户 | 角色 | 邮箱 | 密码 |
|---|---|---|---|
| acme（电商客服） | 租户管理员 | `admin@acme.test` | `admin123` |
| acme | 客服坐席 | `agent@acme.test` | `agent123` |
| cloudwise（SaaS 客服） | 客服坐席 | `agent@cloudwise.test` | `agent123` |
| platform | 平台管理员 | `platform@helpdesk.test` | `platform123` |

## 种子数据与评测

平台管理员可以生成一套可评测的 wiki（2 租户 / 10 空间 / 2,500 页面 / 10,000 小节 / 2,500 问题）：

```powershell
powershell -File tools\eval.ps1 -Reseed
```

或手动：

```powershell
$p = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/auth/login `
  -ContentType application/json `
  -Body '{"tenantSlug":"platform","email":"platform@helpdesk.test","password":"platform123"}'
$ph = @{ Authorization = "Bearer $($p.token)" }
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8091/api/admin/seed?force=true" -Headers $ph
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/admin/seed/status -Headers $ph
```

评测（recall@5 / MRR@10 / nDCG@10，按页面判定）：

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8091/api/admin/eval?sample=200" -Headers $ph
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/admin/eval/status -Headers $ph
```

盲测集（换一种问法，不复用原文）：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/admin/questions/blind -Headers $ph
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8091/api/admin/eval?sample=200&kind=BLIND" -Headers $ph
```

真实问题盲测：见 [真实盲测](docs/真实盲测.md)，用 `tools\import-eval.ps1` 导入 CSV 后以 `kind=REAL` 评测。
注入防护、引用裁剪、重试降级与评测口径见 [AI 可靠性](docs/AI可靠性.md)。
部署、排障、发布回滚见 [运行手册](docs/运行手册.md)，备份恢复见
[备份恢复演练](docs/备份恢复演练.md)。
压测基线和 Redis/LLM/PostgreSQL 故障注入见 [压测与故障注入](docs/压测与故障注入.md)。

索引状态与过期页面：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/admin/index/status -Headers $ph
Invoke-RestMethod -Uri "http://127.0.0.1:8091/api/admin/stale-pages?days=180" -Headers $ph
```

wiki 页面维护（租户管理员）：

```powershell
# 创建
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/wiki/pages -Headers $h `
  -ContentType application/json `
  -Body '{"spaceId":"...","title":"会员积分规则","visibility":"PUBLIC","sections":[{"heading":"适用条件","content":"金卡会员消费一元累积两积分。"}]}'

# 更新 / 删除 / 重建索引
Invoke-RestMethod -Method Put    -Uri http://127.0.0.1:8091/api/wiki/pages/{id} -Headers $h ...
Invoke-RestMethod -Method Delete -Uri http://127.0.0.1:8091/api/wiki/pages/{id} -Headers $h
Invoke-RestMethod -Method Post   -Uri http://127.0.0.1:8091/api/wiki/pages/{id}/reindex -Headers $h

# 审批与版本
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/wiki/pages/{id}/submit -Headers $h
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/wiki/pages/{id}/approve -Headers $h
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/wiki/pages/{id}/versions -Headers $h
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/wiki/pages/{id}/rollback/1 -Headers $h
```

创建、更新、删除都会写入 `audit_log`（租户、操作者、动作、目标、摘要）。

## API 走一遍

登录：

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/auth/login `
  -ContentType application/json `
  -Body '{"tenantSlug":"acme","email":"agent@acme.test","password":"agent123"}'
$token = $login.token
$h = @{ Authorization = "Bearer $token" }
```

知识库检索：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/kb/search -Headers $h `
  -ContentType application/json -Body '{"query":"拆封的耳机能退吗","topK":3}'
```

返回 `traceId`、命中的页面标题、标题路径、更新时间和三路分数
（`score` / `fusedScore` / `rerankScore`），便于定位是召回还是重排的问题。

建工单：

```powershell
$ticket = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/tickets -Headers $h `
  -ContentType application/json `
  -Body '{"subject":"拆封的耳机能退吗","description":"上周买的耳机拆封了，还能退吗？运费谁承担？"}'
```

AI 建议（SSE 流式）：

```powershell
curl.exe -s -N -X POST -H "Authorization: Bearer $token" `
  "http://127.0.0.1:8091/api/tickets/$($ticket.id)/ai-suggest"
```

返回 `event:token` 增量文本，最后 `event:done` 带 `messageId`、`tokens` 和引用文档。

用量：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/usage/summary -Headers $h
```

完整脚本见 `tools\demo.ps1`。

## AI 链路

```text
query → 全文召回 top 20 + 向量召回 top 20
      → RRF 融合 + 新鲜度加权
      → bge-reranker-base 重排 top 10
      → DeepSeek deepseek-flash（SSE 流式）
      → 引用 + token 计量 + 落库
```

DeepSeek 使用 `reasoning_effort=low`（客服场景不需要长推理），并开启
`stream_options.include_usage` 获取真实 token 用量。

## 切换模型

```powershell
$env:HELPDESK_LLM_PROVIDER='deepseek'
$env:HELPDESK_LLM_API_KEY='sk-...'
$env:HELPDESK_LLM_MODEL='deepseek-flash'
$env:HELPDESK_LLM_EFFORT='low'
```

DeepSeek 走 OpenAI 兼容的 `/chat/completions` + SSE。它没有 embeddings 接口，
所以向量化用本地模型：

```powershell
$env:HELPDESK_EMBEDDING_PROVIDER='onnx'
$env:HELPDESK_EMBEDDING_MODEL_PATH='D:/models/onnx-int8/bge-base-zh-v1.5-onnx-int8'
$env:HELPDESK_EMBEDDING_DIMENSION='768'
```

也支持 OpenAI 兼容的 embedding 服务（Ollama / vLLM）：
`HELPDESK_EMBEDDING_PROVIDER=openai-compatible` + `HELPDESK_EMBEDDING_BASE_URL`。
切换维度需要同步修改 `kb_chunk.embedding` 的列定义（pgvector 的列维度固定）。

## 测试

```powershell
mvn test                  # 单元 + Spring 上下文测试
$env:HELPDESK_IT='true'
mvn test                  # 再加真实 PostgreSQL 的隔离与 AI 全链路测试
```

当前覆盖：租户隔离（RLS）、AI 引用/降级、提示词注入、分诊规则、检索与切块。

## 可观测性

```text
helpdesk_embedding_duration        本地 BGE 向量化耗时
helpdesk_vector_search_duration    pgvector 检索耗时
helpdesk_rerank_duration           cross-encoder 重排耗时
helpdesk_llm_duration              DeepSeek 流式生成耗时
helpdesk_ai_stream_total{result}   done / partial / error
helpdesk_llm_fallback_total        LLM 失败后的降级次数
helpdesk_citation_missing_total    有检索结果但模型未标注引用
helpdesk_pii_redacted_total        发送 LLM 前发生 PII 脱敏的次数
helpdesk_sla_breaches_total        SLA 首次响应超时工单数
```

Prometheus/Grafana 使用 Compose 的 `observability` profile：

```bash
cd deploy
sudo docker compose --profile observability up -d
```

Prometheus 监听 `127.0.0.1:9090`，Grafana 监听 `127.0.0.1:3000`，默认不暴露公网。

wiki 语料实测：embedding 15–20ms、向量检索 2–4ms、FTS 80–90ms、重排 170–210ms、LLM 2.8s。

## 已实现

- 多租户：JWT 租户上下文、连接级 session 变量、PostgreSQL RLS、平台管理员跨租户视图
- 前端：Vue 3 + Vite + TypeScript + Element Plus，坐席工作台与客户门户双入口
- 工单：创建、列表、详情、回复、状态流转
- 分诊：规则分类、优先级、按坐席负载路由、人工调整与重新分诊
- 工作台：登录、工单列表、新建、AI 流式建议、引用展示
- 人工闭环：客服回复框，回复后工单进入处理中
- 客户闭环：公开工单入口、查询码、客户追加回复、CSAT、内部备注
- 附件：客户/坐席上传图片、PDF、文本；内部附件不进入客户门户；租户彻底删除时清理附件目录
- 团队管理：租户管理员创建坐席、调整角色、停用账号
- 知识库：wiki 空间 / 页面 / 小节，页面 CRUD、可见性、负责人、复核时间、审计日志
- 知识库治理：草稿/待审核/已发布状态、提交/通过/驳回、版本快照、版本 diff、版本回滚；只有已发布页面进入检索
- 通知：页面提交审核/通过/驳回时生成站内通知，顶栏铃铛显示未读数
- 索引：小节级 BGE 向量 + bigram tsvector（GIN），页面编辑后增量重建
- 索引可靠性：事务性 outbox + Redis Stream + PEL 重投 + DLQ，页面保存异步索引
- 检索：全文 + 向量混合召回 → RRF → 新鲜度加权 → BGE cross-encoder 重排
- AI：DeepSeek 流式回复、`[n]` 引用映射、token 计量、断连时保留部分回答
- AI 闭环：建议采纳/修改/驳回、反馈入库、PII 脱敏、租户级模型/topK/补充规则配置
- 工具调用：只读订单查询，结果注入 RAG prompt，调用记录写入 `tool_call_log`
- 安全：wiki 片段分隔/转义/可疑标记，降低提示词注入风险
- 可用性：DeepSeek 失败前重试 1 次，仍失败时返回明确降级话术
- 配额：租户级每分钟限流（Redis + Lua）、月度 token 预算
- 用量：按租户统计 token 与成本
- 用量面板：本月 tokens、预算使用率、成本、限流、近 14 天趋势
- SLA：按优先级计算首次响应截止时间，定时扫描超时工单并记录指标
- 报表：工单状态、SLA、首次响应/解决时长、CSAT、AI 采纳率、分类/优先级分布、成本
- 平台管理：租户开通、停用/启用、JSON 导出、回收站、恢复、30 天后自动彻底删除、平台审计查询页
- 安全基线：登录限流、JWT `jti` 撤销、登出、安全响应头
- 可观测：Actuator、Micrometer、Prometheus、Grafana 面板与告警规则
- 自动化回归：Playwright 覆盖登录、工单、知识库、审批/版本、报表、团队、AI 设置、平台和客户门户
- 压测：`tools/LoadTest.java`，128 并发 / 2000 请求下 0 错误，约 1.1k RPS（本机基线）

## 已知限制

- HNSW + 租户过滤在小规模下正常，租户很多时需要使用分区索引或迭代扫描
- 页面保存走异步索引；种子数据仍同步批量索引，首次 seed 会较慢
- 工单分诊是规则引擎，不是 LLM 分类；需要模型分类时应在规则前加一层可降级的模型判断
- 没有接真实计费支付，只有用量计量
