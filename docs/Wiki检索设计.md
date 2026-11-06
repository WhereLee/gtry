# Wiki 检索设计

## 1. 为什么从“文档”改成 wiki

最初的 `kb_document` 是一篇几百字的手册，切块后每篇只有 1 个 chunk，
检索实际是在“3 篇文档里选 1 篇”。wiki 模型把知识拆成
**空间 → 页面 → 小节**，索引单元是小节，粒度更接近用户的问题。

## 2. 数据模型

```text
wiki_space     租户内的知识空间（退货售后、计费套餐……）
  └─ wiki_page     页面（标题、可见性、负责人、最后复核时间）
       └─ wiki_section  小节（标题路径 + 正文）
            └─ wiki_chunk  检索单元（向量 + bigram tsvector）
wiki_link      页面互链（预留一跳扩展）
eval_question  评测集（问题 → 应命中的小节）
```

索引单元是 **section**，不是 page。页面可以很长，但每个小节只讲一件事。

## 3. 中文全文检索

PostgreSQL 默认解析器不切中文，整句会变成一个 token。这里不引入
Jieba/Zhparser 扩展，而是在入库时生成 **ASCII 词 + 汉字 unigram + bigram**
（`ChineseText`），存到 `wiki_chunk.search_text`，再用生成列
`to_tsvector('simple', search_text)` + GIN 索引。

查询侧用 `to_tsquery('simple', '退货 | 货退 | 退款')`，OR 语义，按 `ts_rank` 排序。

## 4. 混合检索

```text
query
  ├─ 向量召回 top 20（bge-base-zh-v1.5 + HNSW）
  └─ 全文召回 top 20（bigram tsvector + GIN）
        ↓
     RRF 融合（k=60）
        ↓
  新鲜度加权（exp(-ageDays/365) * 0.02）
        ↓
  cross-encoder 重排 top 10（bge-reranker-base）
        ↓
       top 3–5
```

RRF 的好处是不用把向量分数和 `ts_rank` 归一化到同一量纲。

## 5. 可见性与新鲜度

- `wiki_page.visibility`：`PUBLIC` / `INTERNAL`。客服工作台默认包含
  INTERNAL；对外接口必须传 `includeInternal=false`，否则会把内部备注发给客户。
- `wiki_page.last_reviewed_at`：超过阈值（默认 180 天）进入
  `GET /api/admin/stale-pages` 报告。
- `updated_at` 参与 RRF 后的 freshness 加权，过期内容不会因为向量相似度高而霸榜。

## 6. 日志与定位

每个请求有 `traceId`（`X-Trace-Id` 或自动生成），响应头和 SSE `done` 事件都会返回。
一次检索输出一行结构化日志：

```text
helpdesk_search traceId=ab12cd34 queryHash=7f3a includeInternal=true
  vector=20 fts=14 fused=28 embedMs=18 vectorMs=4 ftsMs=2 rerankMs=150
  totalMs=180 top=[3f2a1b7c:0.956, 91c0d2e1:0.001]
```

失败点单独记录：

- `helpdesk_search_fts_failed`：全文检索失败，已降级为纯向量；
- `helpdesk_search_rerank_failed`：重排失败，已降级为 RRF 顺序；
- `helpdesk_index_section_failed`：单节索引失败，不影响其他小节。

## 7. 运维

| 端点 | 用途 |
|---|---|
| `POST /api/admin/seed?force=` | 生成演示 wiki（2 租户 / 10 空间 / 1 万小节） |
| `GET /api/admin/seed/status` | 种子进度 |
| `GET /api/admin/index/status` | pages / sections / chunks / questions 数量 |
| `POST /api/admin/eval?sample=` | 跑 recall@5 / MRR@10 / nDCG@10 |
| `GET /api/admin/eval/status` | 评测结果与失败样例 |
| `GET /api/admin/stale-pages?days=` | 过期页面报告 |

索引失败不会阻塞请求；种子和评测都是异步任务，带状态查询。

## 8. 已知边界

- bigram 全文检索是“无扩展”的折中，召回不如专业分词，但零依赖、可解释；
- HNSW 是全局索引，租户过滤在 ANN 之后；租户很多时需要分区索引或
  `hnsw.iterative_scan`；
- 种子数据是模板生成的，评测集与语料同分布，指标会偏乐观；上线前需要用真实
  客服问题做一次盲测。

## 9. 评测结果（2026-11-06）

语料：2 租户 / 10 空间 / 2,500 页面 / 10,000 小节 / 2,500 评测问题。
每个租户随机抽 200 条，按**页面**判定（命中页面内任一小节即算命中）。

| 范围 | recall@5 | MRR@10 | nDCG@10 | 平均耗时 |
|---|---:|---:|---:|---:|
| 总体 | 0.9975 | 0.9932 | 0.9943 | 375ms |
| acme | 1.0000 | 1.0000 | 1.0000 | 368ms |
| cloudwise | 0.9950 | 0.9863 | 0.9885 | 382ms |

400 条里只有 1 条未命中：`移动端 25 人` 被 `移动端 23 人` 挤到第 6 位，
属于相邻档位的边界情况。

一次检索的分段耗时：

```text
embed   15–20ms   bge-base-zh ONNX
vector   2–4ms    pgvector HNSW
fts     80–90ms   bigram tsvector + ts_rank
rerank 170–210ms  bge-reranker-base（10 个候选）
total  300–375ms
```

FTS 慢的原因是 OR 查询命中了全部 1 万行，PostgreSQL 选择 Seq Scan 并逐行
计算 `ts_rank`。当前规模下可接受；QPS 上来后的优化顺序是：
先减少重排候选，再考虑只对稀有 token 做 AND，最后才是换 `pg_trgm` 或分词扩展。

**注意**：这组指标是模板语料上的同分布评测，只能证明链路正确和可回归，
不能当作真实客服问题的准确率。上线前需要另建一批人工标注的真实问题做盲测。

### 9.1 盲测（换一种问法）

为了验证不是“原文匹配”，又生成了一批不复用小节措辞的问题，例如：

```text
原文：订单金额超过 110 元
盲测：我买的耳机，订单满 110 元，退货售后需要注意什么？
     耳机订单达到 110 元时，退货售后怎么处理？
```

共 5,000 条（2,500 页面 × 2 种问法），每租户抽 200 条：

| 范围 | recall@5 | MRR@10 | nDCG@10 | 平均耗时 |
|---|---:|---:|---:|---:|
| 总体 | 0.9975 | 0.9842 | 0.9876 | 559ms |
| acme | 1.0000 | 1.0000 | 1.0000 | 574ms |
| cloudwise | 0.9950 | 0.9683 | 0.9752 | 544ms |

400 条里只有 1 条未命中：问的是“数据导出”产品 + “配额限制”空间，
命中了“团队版 + 数据导出”页面，属于产品名与空间名重叠造成的歧义。

盲测的 MRR 比同分布低（0.9932 → 0.9842），说明换问法后正确页面偶尔会
从第 1 位掉到第 2–5 位，但仍在 top5 内，符合预期。
