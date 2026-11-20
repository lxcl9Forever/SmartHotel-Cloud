# SmartHotel Cloud 智慧酒店

由原 SmartHotel 渐进拆分的微服务项目，保留顾客、员工和管理员功能。十个独立后端应用、九个领域数据库及独立账号、Vue 3 前端。支持 Windows + Ubuntu 开发和完整 Docker Compose 部署。

## 服务与数据归属

| 服务模块（统一前缀 smarthotel-） | 职责 | 端口 | 数据库 |
| --- | --- | --- | --- |
| gateway | API 路由、内部接口隔离、清理伪造身份头 | 8080 | 无 |
| user-service | 登录会话、账号角色、顾客员工、常用入住人 | 8081 | smart_hotel_user |
| order-service | 下单取消、超时关闭、订单权威状态、跨领域协调 | 8082 | smart_hotel_order |
| hotel-service | 酒店、房型、图片、按日房价 | 8083 | smart_hotel_hotel |
| inventory-service | 按日库存、TCC、Redis 投影与恢复 | 8084 | smart_hotel_inventory |
| review-service | 评价与审核、房型评分 | 8085 | smart_hotel_review |
| marketing-service | 促销、签到券、每日优惠、额度预留 | 8086 | smart_hotel_marketing |
| payment-service | MOCK/支付宝适配、支付记录、退款审核与流水 | 8087 | smart_hotel_payment |
| stay-service | 实体客房、房态、入住退房、清洁 | 8089 | smart_hotel_stay |
| statistics-service | 运营读模型和日报 | 8090 | smart_hotel_statistics |

8088 留给可选 Nacos 控制台。common 只提供接口模型和基础组件；integration 提供内部调用、事务事件与协调基础设施，不包含业务 Mapper。不同领域账号不能查询彼此的主表；需要列表关联的服务维护自己的只读投影。当前九个 schema 位于同一 MySQL 实例，各服务可配置独立连接。

## 分布式设计

- 库存和营销预留采用持久 Try / Confirm / Cancel；异常结果不猜测回滚，恢复任务依据订单事实决议。
- 支付、退款、入住和退房在所属服务提交，通过订单所有者的持久工作流更新权威订单状态。操作未决时订单被占用，禁止并发取消覆盖付款结果；重试使用已记录结果。
- 本地业务写入与 Outbox 在同一事务提交。HTTP 投递、Inbox 去重、聚合版本、删除墓碑和重试/重放使读模型最终一致。这里使用事务 HTTP Outbox，未将 RabbitMQ 当作必需事件总线。
- 续住功能已下线，历史费用和已存在的持久工作流保留兼容处理。支付宝沙箱通过签名、主动查单及异常收款对账确认结果；模拟支付仅在显式测试开关下可用。
- 智能选房调用模型 API 理解需求，由酒店、库存和营销服务核实报价、余量及多间房预算；外部服务不可用时返回具体失败原因。

配置、数据库升级和完整测试步骤见 [支付宝沙箱与智能选房](docs/payment-ai-guide.md)。

实现和故障示例见 [领域拆分与一致性](docs/full-domain-architecture.md)、[订单协调](docs/order-service-boundary.md)、[验收范围](docs/delivery-status.md)。

## 日常运行（已有 Ubuntu 数据）

先启动 Ubuntu 上的 MySQL、Redis，以及需要的 Nacos；项目根目录执行：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
npm run dev --prefix smarthotel-frontend
```

IP 以虚拟机实际地址为准，网页地址以 Vite 输出为准（通常 `http://localhost:5173`）。已有数据不要重新初始化。本次六领域迁移需先按 [备份与迁移](docs/full-domain-migration.md) 完成，启动脚本会校验布局。

```powershell
.\scripts\Get-CloudStatus.ps1
# 手动启动的前端终端按 Ctrl+C，再停止后端：
.\scripts\Stop-Cloud.ps1
```

停止保留数据库和数据卷。Ubuntu 的依赖在各自目录执行 `sudo docker compose stop`。

## 全新容器部署

JDK 17、Maven、Node 22、Docker；按 [完整部署指南](docs/container-deployment.md) 生成独立凭据并初始化九领域数据库。构建后十三个容器通过内网 DNS 调用；默认网页入口 `http://127.0.0.1:8088`。

```powershell
mvn verify
npm ci --prefix smarthotel-frontend
npm run build --prefix smarthotel-frontend
docker compose up -d --build --wait
```

首次初始化不包含在以上简写中，必须先按部署指南完成。不要将原服务器 `.env` 复制到全新数据库容器。

## 验证与维护

JDK 17、Spring Boot 3.5.9、Spring Cloud 2025.0.1、Spring Cloud Alibaba 2025.0.0.0、MyBatis-Plus 3.5.17、MySQL 8、Redis、Vue 3。Nacos 可选，Compose 默认使用内网 DNS。

隔离测试脚本创建合成账号和 MOCK 订单，故障模式会停止/恢复指定测试容器，仅允许专门的 `smarthotel-cloud-verification*` 项目：

```powershell
python scripts/verify_deployment.py --compose-project <实际项目名> --url http://127.0.0.1:8088
python scripts/distributed_smoke.py --compose-project smarthotel-cloud-verification-<编号> --allow-test-data --faults --url http://127.0.0.1:<隔离端口>
```

[服务契约](docs/service-contracts.md) · [迁移与回退](docs/full-domain-migration.md) · [验收记录](docs/verification.md) · [Windows 开发](docs/ubuntu-development.md)

`.env`、密钥、备份、日志和测试报告不提交 GitHub。没有公开默认管理员密码；不要把普通顾客账号提升为管理员。生产高可用、真实支付、容量压测和异机备份需在实际环境另行验证。
