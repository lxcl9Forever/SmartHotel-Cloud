> 此文保留此前单领域分库过程；当前九领域布局和最新回退入口见 [六领域迁移](full-domain-migration.md) 与 [交付状态](delivery-status.md)。

# 用户数据库独立与回退

## 当前环境

2026-11-11 已完成迁移。Windows 运行 Java/Vue，Ubuntu `192.168.229.131:13306` 运行同一个 MySQL 实例。

| 用途 | 数据库 | 应用账号 | 密码所在的私有 .env 键 |
| --- | --- | --- | --- |
| 用户服务 | smart_hotel_user | sh_user_app | USER_MYSQL_PASSWORD |
| 业务服务 | smart_hotel_business | sh_business_app | BUSINESS_MYSQL_PASSWORD |
| 原始只读快照 | smart_hotel_cloud | smarthotel | MYSQL_PASSWORD |

Navicat 新建两条 MySQL 连接即可。账号仅有所属表的增删改查权限，不具备 DDL 权限；已有网页登录账号和密码不变。数据库仍部署在同一实例，不能称为两个独立数据库服务器。

用户域：sys_role、sys_user、employee、member_level、customer、customer_saved_guest。历史 operation_log 中 AUTH/USER/EMPLOYEE 分到用户库，其余到业务库。之后各服务写自己的日志，不共享自增日志 ID。账号、顾客、员工和业务主键保持原值。

业务库副本解除指向用户域 customer 的跨域外键，域内外键保留。订单、优惠券、评价、退款、办理员工等通过逻辑引用核验及应用 API 协作；原快照的表与外键不变。

## 日常启动与检查

Ubuntu 分别在 ~/smarthotel-cloud 和 ~/smarthotel-cloud-nacos 执行 sudo docker compose up -d。Windows 项目根目录：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
# 只检查连接、引用和权限，不重新迁移
.\scripts\Migrate-Databases.ps1 -Action Verify -DependencyHost 192.168.229.131
```

前端在 smarthotel-frontend 执行 npm run dev。原 smarthotel 数据库连接现在只读，不能用来查看迁移后的新增订单或用户。

## 仅限首次部署的迁移

当前环境已迁完，不重复执行下面的首次迁移。工具要求 JDK 17 与 MySQL JDBC 驱动，默认驱动路径 .run/tools/mysql-connector-j.jar；可用 -JdbcJar 指向 Maven 缓存中的 mysql-connector-j JAR。

1. 启动 MySQL/Redis，但停止全部 Java 与其他写入源。工具检查本机端口和脚本进程记录；不能代替检查其他电脑上的写入客户端。
2. 保留私有 .env 中正确的源库管理凭据。
3. 执行 Inspect 查看源表，再执行 Rehearse：完整 SQL 备份、实际恢复、内容校验、分库权限负向测试、迁移后新增数据的回退合并。演练只操作自身随机命名的临时库。
4. 演练成功后 Migrate。目标库必须不存在，工具不覆盖已有库。成功时原账号改只读，原子更新 .env 中 DB_LAYOUT 与各服务配置，再启动 Java。

```powershell
.\scripts\Stop-Cloud.ps1
.\scripts\Migrate-Databases.ps1 -Action Inspect
.\scripts\Migrate-Databases.ps1 -Action Rehearse
.\scripts\Migrate-Databases.ps1 -Action Migrate
```

任一步失败先查看对应 manifest.properties 的 phase，不要删除库后重试。Resume 只支持工具记录的唯一一次失败正式迁移：校验原 SQL 文件哈希、源/目标数据完全一致，才重新配置已记录的两个账号并完成切换；不是通用的跳过检查开关。数据库已有新写入时不能用 Resume 重做源复制。

## 备份与回退

私有 `.run/db-backups/` 保存 SQL、逐表摘要、阶段清单和切换前配置。备份包含密码哈希、个人信息；配置副本包含密码。不要上传 GitHub或公开分享，另存一份到私有备份位置。

实际原始备份位于 20261111-103335-49357f，成功切换清单位于 20261111-103655-443718。旧库只代表切换前时刻，不能反映之后的新增订单。

**回退不能直接复制旧 .env 或恢复旧 SQL，否则会丢失切换后数据。** 工具先备份两个当前数据库，再把最新数据合并到新的 smart_hotel_rollback_* 库，核对 ID、数据摘要和引用，处理双域日志自增 ID 冲突并记录映射，生成两个限制到所属表的回退账号，最后切换 .env。原库与分库均保留。回退后应重新跑业务回归，不能继续复用分库前缓存状态的假设。

```powershell
.\scripts\Stop-Cloud.ps1
.\scripts\Migrate-Databases.ps1 -Action Rollback -DependencyHost 192.168.229.131
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
```

实际开发库尚未执行回退；隔离环境已经验证迁移后新增账号及双域审计不会在合并时丢失。Rollback 适用于当前表结构与当前迁移边界，不是未来所有数据库版本的通用降级方案。

不得删除数据卷、重导初始化 SQL 或全量清空 Redis 来处理迁移问题。普通结束开发仍使用 Stop-Cloud.ps1，再停止 Ubuntu 中间件容器。
