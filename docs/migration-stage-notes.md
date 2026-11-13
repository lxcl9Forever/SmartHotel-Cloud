# 迁移阶段说明归档

以下为逐阶段记录，可能描述当时尚未完成的内容。当前运行方式以根 README 和 delivery-status.md 为准。

# SmartHotel Cloud

SmartHotel 智慧酒店的渐进式微服务改造版本，保留 Vue 3 前端、顾客/员工/管理员功能以及订单、入住、支付退款、促销、评价等原有代码。原单体项目位于另一个目录，本项目已将用户域、酒店资料域、业务域和库存域分为四个 MySQL schema（含独立库存库），部署在同一 MySQL 实例上。

## 当前迁移边界

当前完成**用户、酒店资料、库存分库阶段**：独立用户服务和网关，业务服务通过 OpenFeign 验证会话、查询账号和顾客 ID、批量获取展示姓名，不再直接查询用户域表。酒店查询、管理员管理查询及房价写入和库存写入已有独立进程；订单、支付退款与入住流程仍在业务服务。

| 模块 | 内容 | 默认端口 |
| --- | --- | --- |
| smarthotel-gateway | Spring Cloud Gateway，统一 API 路由、移除伪造身份头、隔离内部接口 | 8080 |
| smarthotel-user-service | 登录注册、验证码、个人资料、改密、常用入住人、账号及员工管理、内部会话与用户目录查询 | 8081 |
| smarthotel-business-service | 订单、入住退房、实体房态、支付退款、促销、统计；远程读取酒店资料并交接库存 | 8082 |
| smarthotel-inventory-service | 库存预留/确认/撤销、房量变更、归属台账、Redis 投影与恢复 | 8084 |
| smarthotel-hotel-service | 酒店、房型、图片与房价，独立酒店库；库存查询经库存 API，实体房态留在业务服务 | 8083 |
| smarthotel-common | 共享 API DTO/VO、统一响应异常、认证过滤器（无持久化实体）；没有业务 Service 或 Mapper | 库模块 |
| smarthotel-frontend | 原 Vue 3 前端，`/api` 代理到网关 | Vite 输出为准 |

用户服务连接 `smart_hotel_user`（`sh_user_app`），业务服务连接 `smart_hotel_business`（`sh_business_app`），酒店服务连接 `smart_hotel_hotel`（`sh_hotel_domain`），账号限制各自访问范围。原 `smart_hotel_cloud` 保留为只读迁移快照。用户、顾客、员工等已有 ID 保持不变；审计按服务分别存储。业务服务通过内部 API 获取用户信息，不跨库 JOIN。这里是同实例分库及权限隔离，尚未拆成独立数据库服务器。

库存独立服务使用 Try/Confirm/Cancel 与持久化日志交接，不与订单共享 Java 事务；当前使用独立 `smart_hotel_inventory`（`sh_inventory_domain`），业务账号不能读取库存库或旧库存快照；订单侧交接日志仍保留在业务库。订单服务与实体客房管理尚未完整拆出。酒店服务已移除库存和实体客房 Mapper，库存容量校验通过酒店内部 API 完成，详见 [酒店分库说明](docs/hotel-database-separation.md)。详见 [数据库迁移与回退](docs/database-separation.md) 和 [迁移边界](docs/migration.md)。

## 技术基线

- JDK 17、Spring Boot 3.5.9、Spring Cloud 2025.0.1、Spring Cloud Alibaba 2025.0.0.0。
- MyBatis-Plus 3.5.17、MySQL 8、Redis；RabbitMQ、支付宝沙箱、外部 AI 默认关闭。
- Nacos 注册和配置中心为可选 `nacos` profile；默认本地直连模式便于排查依赖问题。
- Vue 3 与现有 lockfile；使用 Node 22，`npm ci` 安装前端依赖。

版本关系参考 [Spring Cloud 官方兼容表](https://spring.io/projects/spring-cloud/) 和 [Alibaba 官方版本说明](https://sca.aliyun.com/docs/2025.x/overview/version-explain/)。此次从 Boot 3.0.2 升级到 3.5.9，实际通过的检查见 [验证记录](docs/verification.md)；不宣称这些版本永久是最新版。

## Windows 本地启动

如果 MySQL、Redis 放在 Ubuntu 虚拟机，使用 [Windows + Ubuntu 开发指南](docs/ubuntu-development.md)，无需 Windows Docker Desktop。下面的命令适用于依赖也运行在 Windows 的方案。

先启动 Docker Desktop，在项目根目录用 PowerShell 执行：

```powershell
.\scripts\Initialize-LocalEnv.ps1
docker compose up -d mysql redis
# 首次初始化后，等待容器 healthy，且不要启动 Java，再执行：
.\scripts\Migrate-Databases.ps1 -Action Rehearse -DependencyHost 127.0.0.1
.\scripts\Migrate-Databases.ps1 -Action Migrate -DependencyHost 127.0.0.1
.\scripts\Initialize-HotelRead.ps1 -DependencyHost 127.0.0.1
.\scripts\Upgrade-Inventory.ps1 -DependencyHost 127.0.0.1
.\scripts\Upgrade-InventoryRemote.ps1 -DependencyHost 127.0.0.1
.\scripts\Upgrade-HotelWrite.ps1 -DependencyHost 127.0.0.1
.\scripts\Upgrade-HotelCatalog.ps1 -DependencyHost 127.0.0.1
.\scripts\Split-HotelDatabase.ps1 -DependencyHost 127.0.0.1
.\scripts\Split-InventoryDatabase.ps1 -DependencyHost 127.0.0.1
mvn verify
.\scripts\Start-Cloud.ps1
```

迁移命令需要 MySQL JDBC JAR：准备 `.run/tools/mysql-connector-j.jar`，或给各命令传入 `-JdbcJar <实际路径>`。

首次初始化 MySQL 需要等待，`docker compose ps` 中 mysql 和 redis 均为 healthy 后启动 Java 服务。首次所有 SQL 自动导入迁移源库 `smart_hotel_cloud`，再由上述迁移工具分库。已分库的环境跳过 Rehearse/Migrate，不重复迁移。初始化过程不连接旧的 `smart_hotel`。镜像下载受阻时可通过电脑拉取/导入相同镜像，不要删除已有数据卷重试。

后续修改 Java 代码时，先执行 `Stop-Cloud.ps1`，再构建并重启，避免 Windows 进程占用正在运行的 JAR。

另开终端启动前端：

```powershell
cd smarthotel-frontend
npm ci
npm run dev
```

完整容器部署见 [容器部署指南](docs/container-deployment.md)。在首次四域分库完成、Java 服务停止后，构建后端 JAR 和前端 dist，再执行 `docker compose up -d --build`。前端入口默认 `http://127.0.0.1:8088`，API 默认 `http://127.0.0.1:8080`。

首次没有默认账号，需注册新账号。初始化管理员、库存缓存、IDEA 配置、Nacos 模式见 [运行指南](docs/setup.md)。`.env` 是脚本生成的随机本地密码，不能上传。

## 停止

```powershell
.\scripts\Stop-Cloud.ps1
docker compose stop
```

停止脚本仅处理它记录的本项目 Java 进程，顺序为网关→酒店→业务→库存→用户。数据卷保留；不要用 `docker compose down -v` 做普通关机操作。原来单体项目的进程、数据库、Git 历史不会被修改。

## 文档与验证

- [数据库分离、连接与回退](docs/database-separation.md)
- [启动指南](docs/setup.md)
- [迁移边界、接口归属与路线](docs/migration.md)
- [验证记录与限制](docs/verification.md)
- `database/`：新数据库完整初始化 SQL
- `docs/legacy/`：原项目专题资料；其中旧目录、旧端口和旧数据库名只作为历史参考

本次没有推送 GitHub。迁移使用原项目工作区源码，不复制本地密码、支付宝密钥、Git 历史、node_modules 或原构建产物。


以下为阶段历史，当前以本文顶部与 [酒店分库说明](docs/hotel-database-separation.md) 为准。

最初酒店查询服务迁出使用 8083 与只读账号；当时边界、首次配置和验证见 [酒店查询服务](docs/hotel-read-service.md)。


库存写入已集中为本地事务指令，新增预订请求去重、库存操作台账和提交后可重试缓存投影。详见 [库存幂等与补偿](docs/inventory-foundation.md)。库存写服务尚未远程拆分。

## 库存写入服务阶段

新增独立库存进程（8084）与可靠库存交接；切换步骤、权限边界和故障恢复见 [库存服务说明](docs/inventory-remote.md)。当前 MySQL 仍为同实例、同业务 schema，不宣称物理分库。

## 酒店管理阶段

管理员管理查询和房价修改已迁往酒店服务；管理员权限与审计仍由业务入口负责，入住/退房/清洁房态仍保留在原联合事务。首次权限配置、当前边界与验证见 [酒店管理说明](docs/hotel-management.md)。

## 酒店资料查询远程化

业务进程已移除酒店资料四张表的直接访问，通过酒店内部接口完成房型、房价及历史名称查询。该阶段之后已完成酒店资料分库；之前的资料查询边界见 [酒店资料查询说明](docs/hotel-catalog.md)。

## 库存资料独立数据库

库存五表已独立，业务读取经库存 API，原 TCC 交接保持。当前边界、备份回退与验收见 [库存分库说明](docs/inventory-database-separation.md)。

## 订单核心边界整理

业务运行包已清除旧本地库存实现，默认且仅支持远程库存；历史测试保留在测试目录。订单、支付退款和入住仍保持原事务，应用名及端口不变。详见 [订单边界说明](docs/order-service-boundary.md)。

库存内部查询已使用独立 Snapshot DTO，原 JSON 字段保持兼容；详见 [服务契约说明](docs/service-contracts.md)。
