# 启动与配置

当前已完成用户、酒店资料、库存与订单业务四域分库。最新数据库归属、首次迁移顺序和回退边界见 [酒店分库说明](hotel-database-separation.md)。已有环境直接启动，不重复迁移。

## IDEA

使用 IDEA 打开项目根目录的 `pom.xml`，导入 Maven 聚合工程，Project SDK 选 JDK 17。五个启动类：

- `smarthotel-user-service` → `com.smarthotel.UserServiceApplication`
- `smarthotel-business-service` → `com.smarthotel.BusinessServiceApplication`
- `smarthotel-hotel-service` → `com.smarthotel.hotel.HotelServiceApplication`
- `smarthotel-inventory-service` → `com.smarthotel.inventory.InventoryServiceApplication`
- `smarthotel-gateway` → `com.smarthotel.gateway.GatewayApplication`

先 `mvn verify`。优先按 README 的 PowerShell 脚本启动，它负责读取 `.env` 并指定开发数据库端口；IDEA 直接运行不会自动读取 Docker 的 `.env`，需要在运行配置中设置同样的环境变量。

下表列出基础服务变量。酒店进程使用自己的酒店数据库凭据及 `INVENTORY_SERVICE_URL`；库存进程使用库存凭据及 `HOTEL_SERVICE_URL`。建议使用启动脚本统一映射，完整归属见酒店分库说明。

| 环境变量 | user | business | gateway |
| --- | --- | --- | --- |
| MYSQL_URL、MYSQL_USER、MYSQL_PASSWORD | 用户库 URL 与用户库账号 | 业务库 URL 与业务库账号 | 不需要 |
| REDIS_HOST、REDIS_PORT、REDIS_PASSWORD | 需要 | 需要 | 不需要 |
| INTERNAL_TOKEN | 同一个随机值 | 同一个随机值 | 同一个随机值 |
| JWT_SECRET | 仅 user，随机且至少 32 字节 | 不需要 | 不需要 |
| USER_SERVICE_URL | 不需要 | 默认 http://127.0.0.1:8081 | 不需要 |
| USER_SERVICE_URI、BUSINESS_SERVICE_URI | 不需要 | 不需要 | 默认本机 8081、8082 |

Docker Compose 的开发端口为 MySQL **13306**、Redis **16379**；容器间使用 3306、6379。密码从本项目生成的 `.env` 读取。分库后，user 使用 `USER_MYSQL_DATABASE/USER/PASSWORD`，business 使用 `BUSINESS_MYSQL_DATABASE/USER/PASSWORD`；脚本会映射到各自的 MYSQL_* 环境变量。不要把整个 `.env` 注入每个 IDEA 进程；根账号密码和其他服务的数据库密码不应传给应用。切勿把原单体的数据库 URL 直接粘贴为新项目数据源。

## 数据库

`docker compose up -d mysql redis` 对首次创建的数据卷按文件名顺序执行 `database/*.sql`，包括角色、会员、演示房型、房间和未来 90 天房价/库存。无需把旧 SQL 重复导入。初始化脚本仅在空数据卷首次运行，后续更新需单独迁移。

如果使用自行安装的 MySQL 8，先备份旧库，在新的实例或新库中按文件名顺序执行本项目 SQL，并设置数据源。SQL 中写死 `USE smart_hotel_cloud`，不要擅自替换为旧 `smart_hotel`。

原单体 smart_hotel 不会自动导入本项目。此次迁移处理的是已有 smart_hotel_cloud 开发库；已备份并保留原始快照。带真实个人信息的其他旧库迁移需单独评估和验证。

首次数据初始化完成后、Java 启动前，按 [分库指南](database-separation.md) 做备份演练和迁移。现有环境已完成迁移，不重复执行。Navicat 用户与业务使用以下两条连接，地址 `192.168.229.131`，端口 `13306`：用户库 `smart_hotel_user` / `sh_user_app`；业务库 `smart_hotel_business` / `sh_business_app`。密码分别取私有 `.env` 中的 USER_MYSQL_PASSWORD、BUSINESS_MYSQL_PASSWORD。酒店库另建 `smart_hotel_hotel` / `sh_hotel_domain` 连接，密码取 `HOTEL_MYSQL_PASSWORD`。旧 smarthotel 账号只读原快照库，不能用于当前业务写入。

## 初始管理员和库存

前端注册一个账号，确认注册成功后，用 Navicat 连接 **本项目新库**，执行：

```sql
UPDATE smart_hotel_user.sys_user u
JOIN smart_hotel_user.sys_role r ON r.role_code = 'ADMIN'
SET u.role_id = r.role_id
WHERE u.username = '替换为刚注册的账号';
```

重新登录获得管理员令牌。库存缓存投影由独立库存服务负责，业务服务启动时不再运行本地库存初始化。有订单时也不会自动重置缓存；需核对订单与库存台账后，由管理员通过维护入口请求库存服务重建投影。管理员酒店配置页面可初始化/更新日期库存，可用接口路径以 `AdminHotelController` 为准。

## Nacos 模式

本阶段默认本地直连可运行。需要学习服务注册发现时，自行启动 Nacos **3.0.3**（Alibaba 对应组件版本），启用认证并自行设置安全的账号密码。Nacos 服务端还需正确配置认证 Token、server identity 等参数，按照 [Nacos 官方快速开始](https://nacos.io/docs/latest/quickstart/quick-start/) 操作，不使用生产环境默认密码。

五个后端应用都设置 `NACOS_ADDR`、`NACOS_USERNAME`、`NACOS_PASSWORD`，再运行：

```powershell
# 可把上述变量加入本项目 .env，由启动脚本读取
.\scripts\Start-Cloud.ps1 -Nacos
```

应用会注册到 `SMARTHOTEL_CLOUD` 组。网关使用 `lb://smarthotel-user-service` 和 `lb://smarthotel-business-service`，Feign 根据服务名发现用户服务。数据源、Redis 等启动配置也可发布为该组下的 `smarthotel-user-service.yml`、`smarthotel-business-service.yml`、`smarthotel-gateway.yml`；`spring.config.import` 是可选导入，未发布时保留本地值。不要把配置中心密码放进 Git。

跨主机 Nacos 环境需要额外暴露 gRPC 端口并设置可达的服务注册地址；本指南默认本机开发，不宣称跨主机/集群已验证。

## 停止

本地脚本方式：`scripts/Stop-Cloud.ps1` → `docker compose stop`。IDEA 方式按网关→酒店→业务→库存→用户停止，再停中间件。

容器方式：`docker compose stop`。普通停止不删除容器卷；`down -v` 会删除新项目数据库/Redis 数据，不能作为日常停止命令。

Windows `Stop-Cloud.ps1` 使用操作系统结束开发进程，不宣称是业务无损停机。生产环境需先摘流、等待处理中请求完成，再使用部署平台的优雅退出机制。

## 业务时间（Asia/Shanghai）

酒店日期与时间使用北京时间。两个服务的 Hikari 连接初始化显式执行 SET time_zone = '+08:00'，保证 MySQL 的 NOW()/CURDATE() 和 DATETIME 默认值采用同一时区。JDBC serverTimezone 仅声明驱动转换时区，不能单独保证 MySQL 会话时区。

2026-11-11 排查退款页面少 8 小时：MySQL SYSTEM 时区为 UTC，退款 create_time 使用数据库默认 CURRENT_TIMESTAMP；前端原样显示 LocalDateTime。修复会话配置后需要重启后端，新记录使用北京时间。已有 DATETIME 没有时区标记，可能混有 Java 本地时间和数据库 UTC 时间，不能全表统一加 8 小时；如需修复历史记录，应按字段来源和证据单独备份、核对后处理。
