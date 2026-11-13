# 完整容器部署

适用于 Docker Engine / Docker Desktop 已运行的环境。保留原来的 Windows Java + Ubuntu 依赖开发方式。五个后端服务通过 Compose 内网 DNS 调用，前端 Nginx 将 `/api/` 转发到网关。

## 首次部署

需要 JDK 17、Maven、Node 22、npm 和 MySQL JDBC 驱动。先在项目根目录生成独立 `.env`，已有文件不要覆盖：

```powershell
.\scripts\Initialize-LocalEnv.ps1
docker compose up -d --wait mysql redis
docker compose ps
```

等待 mysql、redis 均 healthy。停止项目 Java 服务后执行：

```powershell
.\scripts\Stop-Cloud.ps1
.\scripts\Initialize-DomainDatabases.ps1 -JdbcJar '实际的mysql-connector-j.jar绝对路径'
mvn verify
npm ci --prefix smarthotel-frontend
npm run build --prefix smarthotel-frontend
docker compose up -d --build --wait
docker compose ps
```

初始化入口按顺序完成备份恢复演练、用户分库、库存 TCC 初始化、酒店与库存分库和权限隔离，检测到已有 DB_LAYOUT 会拒绝重复初始化。中途失败保留 `.run/db-backups` 中的私有清单，按失败阶段处理，不能盲目重新执行。

打开 `http://127.0.0.1:8088`。刷新 `/home` 等 Vue 路由应继续返回前端页面。访问 `/api/` 使用同一个网页端口。容器内部服务及数据库不向外发布；宿主机数据库、Redis、网关和前端端口默认仅绑定回环地址。

在 `.env` 中可配置 `MYSQL_PUBLISH_PORT`（13306）、`REDIS_PUBLISH_PORT`（16379）、`GATEWAY_PUBLISH_PORT`（8080）、`FRONTEND_PUBLISH_PORT`（8088）。数据库初始化脚本的 `-Port` 要与 MySQL 发布端口一致。

## 后续启动与停止

```powershell
docker compose up -d --wait
docker compose stop
```

停止保留数据卷。不要通过删除数据卷修复启动问题。后续修改代码后重新构建 JAR、dist，再 `docker compose up -d --build --wait`。迁移数据库前停止应用容器，保留 mysql、redis 运行。生产部署仍须配置 TLS、访问入口、凭据保管和异机备份；本方案默认是本机验收入口。

## 独立验收

使用全新的 `smarthotel-cloud-verification*` Compose 项目、独立 `.env` 和端口运行，不能连接真实顾客数据库。`scripts/integration_smoke.py` 只接受该测试项目前缀及显式 `--allow-test-data`，通过容器操作员权限生成合成管理员并读取验证码。支付流程明确使用 MOCK，不代表支付宝等外部支付验收通过。


就绪检查可对当前部署只读运行，必须填写实际 Compose 项目名（可用 `docker compose ls` 查看）：

```powershell
python scripts/verify_deployment.py --compose-project <实际项目名> --url http://127.0.0.1:8088
```

业务及故障验收只对已经初始化的隔离测试目录运行，目录中必须有它自己的 compose.yml、私有 .env 和 scripts。此命令会创建合成账号、订单、付款记录，并依次停止/恢复用户、酒店、库存容器，不能用于真实数据库：

```powershell
python scripts/integration_smoke.py --compose-project smarthotel-cloud-verification-<独立编号> --allow-test-data --faults --url http://127.0.0.1:<隔离网页端口>
```

仅改 Compose 项目名不能隔离显式配置的外部地址，务必使用新目录、新凭据、新发布端口和新数据卷。初始化失败应按私有迁移清单恢复，不要清空已有库后重跑。日常故障不删除数据卷。Nginx 使用 Docker DNS 重新解析网关地址；直接连接断开或响应超时由网关返回 503，客户端先查询原业务结果再决定后续操作。
