# 库存独立数据库（2026-11-12）

库存服务使用 `smart_hotel_inventory` / `sh_inventory_domain`，独占 room_inventory、inventory_operation、inventory_cache_sync、inventory_reservation、inventory_tcc_attempt 五张表。订单、预订请求和 inventory_tcc_journal 仍在 smart_hotel_business，由业务进程管理。用户、酒店、业务、库存四个 schema 共用同一 MySQL 实例，不是四台数据库服务器。

业务库原库存五表保留为只允许运维读取的旧快照。业务账号不能直接读取新库存或旧快照；库存账号不能读取订单、业务交接日志、酒店资料。房型和订单的关联编号保留，跨域数据库外键解除，由迁移校验、持久预占台账及服务协议保证。

## 本轮代码边界

下单、模拟支付、取消、退款、续住、秒杀、智能选房与统计所需的库存读取改为库存 API。管理房量查询和修改不再直接查询库存表。每日统计使用单日全房型库存接口，包含已停售房型，保持原统计口径；批量房型查询仍使用最多 100 ID、366 天、退房日期不包含的契约。内部路径校验随机密钥，网关不公开。

库存读取只用于检查和展示，实际预留/确认/撤销仍由库存服务在自己的事务中验证和锁行；业务读取与库存写入不宣称跨服务数据库快照。订单侧持久交接、提交决定、失败撤销和恢复重试保持原有 TCC 机制，不能通过跨库 SQL 降级。原 MOCK 支付及续住流程未调整。后续订单边界整理已把旧 local 库存实现移入测试目录，生产包不再包含旧实现；当前只支持 remote 模式和已生成的库存布局，应用账号无权访问旧快照。

## 备份与切换

Java 停机后，先核对待决日志、未完成库存尝试与库存一致性，再完整备份业务、用户和酒店库，保存私有配置和授权快照。正式备份位于 `.run/db-backups/20261112-111948-f60834/`，状态 INVENTORY_SPLIT_READY，另含新库存库 SQL。备份与 .env 不可提交 GitHub。

备份实际恢复至隔离业务副本，再复制五表到隔离库存库并逐表比较。演练在隔离库存副本增加库存版本号，同时在隔离业务副本插入待决日志，证明回退合并采用最新库存库而非旧快照，且保留待决日志。当前实际开发环境未执行回退。

首次预检拒绝了历史失败预订留下的无订单 NONE 预占记录；这类记录不占库存，是原幂等/撤销协议允许的终态。预检修正为只拒绝无订单但仍有 RESERVED/CONFIRMED 占用或 pending_attempt 的记录，并继续核对所有有源订单的房型、数量和日期。首次拒绝发生在备份和任何数据改动前，没有覆盖数据。

DbSplit 的合并及回退工具已加入库存数据源选择；正式回退会备份四个当前库，合并最新数据，再配置 rollback-shared 并维持服务账号权限边界。回退须停机并检查私有报告，不可仅手动切回旧库。

## 启停和连接

已有环境无需重跑迁移。Ubuntu 上 MySQL、Redis、Nacos 启动后，Windows 根目录执行：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
```

前端在 smarthotel-frontend 中执行 npm run dev。停止使用 Stop-Cloud.ps1，数据卷保留。Navicat 新建库存连接：虚拟机 IPv4、13306、smart_hotel_inventory、sh_inventory_domain，密码取私有 .env 中 INVENTORY_MYSQL_PASSWORD。

全新环境在完成 README 中酒店与库存前置迁移后，停机执行 Split-InventoryDatabase.ps1，可通过 -DependencyHost 和 -JdbcJar 指定依赖。现有用户只需正常启动，不重复迁移。

## 验证限制

本阶段人工验收尚未进行；用户已确认前一阶段酒店分库测试正常。最新自动化、实际接口及故障检查结果见 verification.md。完整应用容器部署、独立数据库实例故障、真实外部支付和生产压力尚未验证。
