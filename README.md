# SmartHotel Cloud 智慧酒店

保留原 SmartHotel 顾客、员工和管理员功能的微服务项目。五个独立 Java 应用、四个独立业务数据库账号和 schema、Vue 3 前端。支持 Windows 开发配合 Ubuntu 依赖，也支持整套 Docker Compose 部署。

## 服务与数据归属

| 服务 | 职责 | 内部端口 | 数据归属 |
| --- | --- | --- | --- |
| gateway | 统一 API 入口、身份头清理、内部接口隔离、上游超时 | 8080 | 无数据库 |
| user | 注册登录、会话、账号角色、顾客员工、常用入住人 | 8081 | smart_hotel_user |
| business | 订单、支付退款、入住续住退房、实体房态、促销评价统计 | 8082 | smart_hotel_business |
| hotel | 酒店、房型、图片、房价 | 8083 | smart_hotel_hotel |
| inventory | 按日库存、TCC 预留确认撤销、Redis 投影及恢复 | 8084 | smart_hotel_inventory |

common 只提供接口契约和基础组件，不共享持久化实体或 Mapper。服务之间通过内部鉴权 API 交互，数据库账号不能跨域访问。四库目前在同一 MySQL 实例，支持按连接配置分别部署。订单、支付退款、入住及实体房态保留在一个业务事务边界；这是当前服务划分，不要求每张表都有独立进程。

库存交接采用持久化 Try/Confirm/Cancel 日志和恢复任务；未知结果保守保留占用。用户服务不可达时拒绝认证，不信任客户端伪造身份；网关不自动重试下单和支付请求。具体约束见 [当前架构与交付范围](docs/delivery-status.md)。

## 启动

**继续使用现有 Ubuntu 数据的 Windows 开发环境**：先启动虚拟机上的 MySQL、Redis（需要 Nacos 模式时也启动 Nacos），在项目根目录 PowerShell 执行：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
npm run dev --prefix smarthotel-frontend
```

IP 按当前虚拟机地址调整。前端以 Vite 打印的地址为准，通常为 http://localhost:5173。已有分库数据不要再次初始化；修改 Java 代码前停止服务并 `mvn verify` 后再启动。详细说明见 [Windows + Ubuntu](docs/ubuntu-development.md)、[IDEA 与账号配置](docs/setup.md)。

**全新整套容器部署**：按 [容器部署指南](docs/container-deployment.md) 生成独立凭据，初始化四域数据库、构建 JAR 和前端，再启动八个容器。默认网页入口 http://127.0.0.1:8088。不要将已有虚拟机 `.env` 直接用于一个全新的本地数据库容器。

## 停止

开发前端终端按 Ctrl+C，然后执行：

```powershell
.\scripts\Stop-Cloud.ps1
```

完整容器部署使用 `docker compose stop`。停止保留数据卷；不要使用删除数据卷作为普通关机方式。依赖在 Ubuntu 时，在对应目录执行停止命令。

## 技术与验证

JDK 17、Spring Boot 3.5.9、Spring Cloud 2025.0.1、Spring Cloud Alibaba 2025.0.0.0、MyBatis-Plus 3.5.17、MySQL 8、Redis、Vue 3、Node 22。Nacos 为可选 profile，整套 Compose 默认通过内网 DNS 直连。RabbitMQ、真实支付宝和外部 AI 默认关闭。

```powershell
mvn verify
npm ci --prefix smarthotel-frontend
npm run build --prefix smarthotel-frontend
python scripts/verify_deployment.py --compose-project <实际Compose项目名> --url http://127.0.0.1:8088
```

真实业务和故障注入脚本仅允许 `smarthotel-cloud-verification*` 隔离测试环境，见 [容器验收](docs/container-deployment.md)。结果范围、证据与限制见 [交付状态](docs/delivery-status.md) 和 [逐阶段验证记录](docs/verification.md)。支付测试中的 MOCK 是模拟付款，不代表外部银行或支付宝交易成功。

## 维护资料

- [服务契约](docs/service-contracts.md)
- [用户分库与备份回退](docs/database-separation.md)
- [酒店分库](docs/hotel-database-separation.md)
- [库存分库](docs/inventory-database-separation.md)
- [订单事务边界](docs/order-service-boundary.md)
- [迁移阶段归档](docs/migration-stage-notes.md)

`.env`、私钥、备份、运行日志和测试报告不上传 GitHub。首次没有默认管理员，请按运行指南创建；不要将普通顾客账号提升为管理员。
