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
无需前端构建步骤。

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
mvn test                  # 8 个单元测试，不需要外部依赖
$env:HELPDESK_IT='true'
mvn test                  # 再加 4 个集成测试（真实 PostgreSQL：隔离 3 + AI 全链路 1）
```

## 可观测性

```text
helpdesk_embedding_duration        本地 BGE 向量化耗时
helpdesk_vector_search_duration    pgvector 检索耗时
helpdesk_rerank_duration           cross-encoder 重排耗时
helpdesk_llm_duration              DeepSeek 流式生成耗时
helpdesk_ai_stream_total{result}   done / partial / error
```

本机实测（6 篇文档、topK=3）：embedding 34ms、检索 4ms、重排 142ms、LLM 2.8s。

## 已实现

- 多租户：JWT 租户上下文、连接级 session 变量、PostgreSQL RLS、平台管理员跨租户视图
- 工单：创建、列表、详情、回复、状态流转
- 工作台：登录、工单列表、新建、AI 流式建议、引用展示
- 知识库：wiki 空间 / 页面 / 小节，页面 CRUD、可见性、负责人、复核时间、审计日志
- 索引：小节级 BGE 向量 + bigram tsvector（GIN），页面编辑后增量重建
- 检索：全文 + 向量混合召回 → RRF → 新鲜度加权 → BGE cross-encoder 重排
- AI：DeepSeek 流式回复、引用来源、token 计量、断连时保留部分回答
- 配额：租户级每分钟限流（Redis + Lua）、月度 token 预算
- 用量：按租户统计 token 与成本
- 可观测：Actuator、Micrometer、Prometheus

## 已知限制

- HNSW + 租户过滤在小规模下正常，租户很多时需要使用分区索引或迭代扫描
- Redis Stream 索引队列暂未实现 PEL 重投（进程崩溃时可能丢索引任务）
- 没有前端工作台；当前通过 API 和 SSE 验证
- 没有接真实计费支付，只有用量计量
