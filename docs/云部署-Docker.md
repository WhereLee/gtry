# 云部署（Docker Compose）

> 服务器：`ubuntu@124.223.36.154`，4 vCPU / 3.7GB / 3Mbps  
> 入口：`https://whereri.site/helpdesk/`  
> 日期：2026-11-07

## 1. 架构

```text
Nginx (宿主)
  /helpdesk/ -> 127.0.0.1:8091
                  │
          helpdesk-app (JRE 17 + jar)
            ├─ helpdesk-postgres (pgvector/pgvector:pg17)
            └─ helpdesk-redis  (redis:7)
```

Postgres 和 Redis 不暴露公网端口，只通过 Compose 网络给 app 使用。

## 2. 目录

```text
/opt/helpdesk/
  Dockerfile
  docker-compose.yml
  .env                  # 600，包含数据库密码、JWT、DeepSeek Key
  app/helpdesk.jar
  models/bge-base-zh-v1.5-onnx-int8/
  models/bge-reranker-base-onnx-int8/
  logs/
```

数据卷：`helpdesk_pgdata`、`helpdesk_redisdata`。

## 3. 实测资源

```text
helpdesk-app       1.13GB   (Java 768m + ONNX 模型)
helpdesk-postgres  190MB
helpdesk-redis     5MB
宿主可用内存       约 1.0GB
```

## 4. 更新

```powershell
mvn -q -DskipTests package
scp target\helpdesk-0.1.0.jar ubuntu@124.223.36.154:/opt/helpdesk/app/helpdesk.jar
```

```bash
cd /opt/helpdesk
sudo docker compose up -d --build
curl -s http://127.0.0.1:8091/actuator/health
```

注意：`ubuntu` 用户当前不在 `docker` 组，Compose 命令需要 `sudo`。

## 5. 种子与评测

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8091/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"platform","email":"platform@helpdesk.test","password":"platform123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s -X POST "http://127.0.0.1:8091/api/admin/seed?force=true" -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://127.0.0.1:8091/api/admin/questions/blind -H "Authorization: Bearer $TOKEN"
curl -s "http://127.0.0.1:8091/api/admin/eval?sample=200&kind=BLIND" -H "Authorization: Bearer $TOKEN"
```

云端 1 万小节种子约 7.5 分钟（4 核 CPU、ONNX int8）。

## 6. Nginx

`/etc/nginx/snippets/helpdesk.conf`：

```nginx
location = /helpdesk { return 301 /helpdesk/; }
location /helpdesk/ {
    proxy_pass http://127.0.0.1:8091/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 10s;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;   # SSE
}
```

## 7. 边界

- 3M 带宽只影响公网体验，不影响服务端容量结论；
- 云端未跑 10,000 连接压测，容量数字以本机实测为准；
- 模型以只读卷挂载，更新模型只需替换 `/opt/helpdesk/models` 后重启 app；
- 首次启动会加载 ONNX 模型，约 10–15 秒。
