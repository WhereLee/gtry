# Helpdesk

[![ci](https://github.com/WhereLee/gtry/actions/workflows/ci.yml/badge.svg)](https://github.com/WhereLee/gtry/actions/workflows/ci.yml)

多租户 AI 工单与客服平台。一套系统服务多个租户，每个租户有自己的客服坐席、知识库、AI 配置、用量预算和数据边界。

## 状态

- 本地入口：`http://127.0.0.1:8091/`
- 公网演示：`https://whereri.site/helpdesk/`
- CI：[`.github/workflows/ci.yml`](.github/workflows/ci.yml)
- 详细验证、评测和运维记录：见 [验证与运维](docs/验证与运维.md)

## 功能

- 客服坐席：工单队列、分页搜索、会话回复、内部备注、AI 建议和引用来源。
- 租户管理员：团队账号、知识库、AI 配置、用量和报表。
- 平台管理员：租户开通/停用、回收站、恢复、导出和平台审计。
- 客户门户：提交工单、查询码查询、追加回复和 CSAT。
- 知识库：空间/页面/小节、草稿/审核/发布、版本快照、版本 diff 和回滚。
- AI：本地向量化、混合检索、重排、DeepSeek 流式建议、引用、PII 脱敏和配额。

## 架构

```text
Vue 3 工作台 / 客户门户
        │ JWT（tenant_id + user_id + role）
        ▼
Spring Boot 3（Java 17）
  ├─ Ticket / Wiki / AI / Quota / Notification
  ├─ PostgreSQL + pgvector
  │    └─ RLS：tenant_id = current_setting('app.tenant_id')
  ├─ Redis：配额、限流、异步索引 Stream
  ├─ ONNX：bge-base-zh-v1.5（768d）+ bge-reranker-base
  └─ DeepSeek：流式建议回复
```

## 多租户

租户由 JWT 决定，请求体不能指定租户；每次取数据库连接时设置 `app.tenant_id`，归还前 `RESET`；所有业务表启用 PostgreSQL RLS，应用连接使用非超级用户 `helpdesk_app`。详细设计和验证见 [多租户设计](docs/多租户设计.md)。

## 快速开始

### 前置

- JDK 17
- Maven 3.9+
- PostgreSQL + pgvector
- Redis

### 启动

先创建数据库，角色和表由 Flyway 创建：

```sql
CREATE DATABASE helpdesk;
```

```powershell
mvn -q -DskipTests package
java -jar target\helpdesk-0.1.0.jar
```

或使用 `tools\start.ps1`。默认端口 `8091`，健康检查 `http://127.0.0.1:8091/actuator/health`。

打开 `http://127.0.0.1:8091/` 进入客服工作台。客户门户：

```text
http://127.0.0.1:8091/portal.html?channel=acme-demo-key
http://127.0.0.1:8091/portal.html?channel=cloudwise-demo-key
```

API 文档：`http://127.0.0.1:8091/swagger-ui.html`，OpenAPI JSON 在 `/v3/api-docs`。

### 前端开发

```powershell
cd web
npm install
npm run dev
```

`npm run dev` 默认使用 `5175`，`/api` 代理到 `8091`。生产构建和浏览器回归见 [验证与运维](docs/验证与运维.md)。

## 演示账号

演示账号、密码和门户渠道见 [演示账号](docs/演示账号.md)。

## 配置

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL 连接 |
| `REDIS_HOST` / `REDIS_PORT` | Redis 连接 |
| `JWT_SECRET` | JWT 签名密钥 |
| `HELPDESK_LLM_PROVIDER` | `mock` 或 `deepseek` |
| `HELPDESK_LLM_API_KEY` | DeepSeek API Key |
| `HELPDESK_LLM_MODEL` | 默认 `deepseek-flash` |
| `HELPDESK_EMBEDDING_PROVIDER` | `mock` / `onnx` / `openai-compatible` |
| `HELPDESK_EMBEDDING_MODEL_PATH` | 本地 ONNX embedding 模型目录 |
| `HELPDESK_ATTACHMENT_DIR` | 附件存储目录 |

完整配置见 `src/main/resources/application.yml` 和 [运行手册](docs/运行手册.md)。

## 主要流程

1. 客户在门户提交工单，客服在工作台分诊、查看 AI 建议、回复并关闭，客户收到 CSAT 评价入口。
2. 租户管理员维护 Wiki 页面，草稿提交审核后发布；只有已发布页面进入检索。
3. 平台管理员开通或停用租户，处理回收站、数据导出和平台审计。

## 目录

```text
src/     Spring Boot 后端
web/     Vue 3 工作台和客户门户
docs/    架构、检索、AI、运维和验证文档
deploy/  Docker、Compose 和 Nginx 配置
tools/   本地脚本和评测工具
```

## 文档

- [多租户设计](docs/多租户设计.md)
- [架构决策](docs/架构决策.md)
- [Wiki 检索设计](docs/Wiki检索设计.md)
- [AI 可靠性](docs/AI可靠性.md)
- [运行手册](docs/运行手册.md)
- [云部署 - Docker](docs/云部署-Docker.md)
- [验证与运维](docs/验证与运维.md)
- [演示账号](docs/演示账号.md)

## 限制

- 没有接真实计费支付，只有用量计量。
- 工单分诊是规则引擎，不是 LLM 分类。
- HNSW + 租户过滤在当前规模下正常，租户很多时需要分区索引或迭代扫描。
- 页面保存走异步索引；种子数据首次批量索引会较慢。

## License

个人项目，暂未指定开源许可证；如需复用请联系作者。
