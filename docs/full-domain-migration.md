# 六领域拆分迁移、备份与回退

本步骤以已经完成 user/hotel/inventory 分库的四域版本为基线，保留用户 ID、订单 ID、客房和历史支付退款记录。原 business 拆为 order、review、marketing、payment、stay、statistics 六库，用户/酒店/库存三库沿用。

## 迁移前

停止所有 Windows Java 服务及应用容器，MySQL/Redis 保持运行。其他机器的写入者也必须停机；本机端口检查无法代替对其他机器的停机确认。保存原四域代码和运行环境，准备有足够空间存储源库、新库及恢复校验临时库。不要把 `.env`、SQL 备份或私有清单上传仓库。

```powershell
.\scripts\Stop-Cloud.ps1
.\scripts\Split-FullDomains.ps1 -Action Rehearse -DependencyHost <MySQL主机> -Port 13306 -JdbcJar '<JDBC驱动绝对路径>'
```

Rehearse 先备份原四库并逐库恢复比对，再在临时六库执行迁移和新库恢复校验，通过后仅删除本次临时校验库，原库和连接不变。备份位于 `.run/db-backups/<时间-编号>/`，私有 manifest 记录阶段、数量、校验和和创建对象。中途失败不要清空已有库盲目重跑。

```powershell
.\scripts\Split-FullDomains.ps1 -Action Migrate -DependencyHost <MySQL主机> -Port 13306 -JdbcJar '<JDBC驱动绝对路径>'
```

Migrate 再次备份验证，复制各域主数据、处理跨域外键，初始化版本/Outbox/Inbox/协调表和读模型，校验 ID 与行内容。六个独立账号权限检查通过后原子更新本地 `.env` 的 FULL_DOMAIN_LAYOUT=split 和领域凭据，旧业务账号仅保留归档读取。原源表不会删除。历史业务日志保留在订单域，新日志在各服务本地；避免将同一历史日志复制成六条记录。

完成后 `mvn verify`，按 README 启动十服务，验证旧账号、旧订单、下单取消、支付退款、入住续住退房和权限。先用合成数据验证，再核对原数据数量。当前实现用同实例 schema；跨物理实例迁移需另行设计停机/增量复制流程。

## 回退

切换后尚未产生新写入时，可以按私有迁移清单恢复原环境和原代码；需同时恢复旧账号对应权限，不能只改库名。切换后已有写入时，不得直接指向旧归档，会丢失新增订单、退款和入住记录。

提供最新数据回退导出：

```powershell
.\scripts\Split-FullDomains.ps1 -Action ExportRollback -DependencyHost <MySQL主机> -Port 13306 -JdbcJar '<JDBC驱动绝对路径>'
```

停止所有写入，并先让待决工作流、库存/营销决议和事件积压处理完。导出拒绝未收敛状态，备份并恢复校验当前九库，再将各领域最新主数据按原四域列结构合并到一个**新建**业务库，保留原 ID，合并并重排跨域审计日志 ID，校验行校验和、外键及用户引用。生成仅供旧四域代码使用的 `four-domain-rollback.env.private` 与新业务库账号。

导出不会修改当前 `.env`，不会覆盖任何原库，也不会删除九域数据。回退激活必须使用已归档的四域代码/JAR 和导出的私有环境，用户/酒店/库存仍指向最新三库；不要让新旧版本同时写入。回退导出及其恢复校验已在隔离环境包含新增订单、退款、续住费用的数据上执行。未决状态无法安全收敛时应先修复决议或人工核对真实外部交易，不自动回退未确定的付款。
