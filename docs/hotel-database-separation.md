# 酒店资料独立数据库（2026-11-12）

> 后续库存也已分库，当前库存边界见 [库存分库说明](inventory-database-separation.md)。

## 当前归属

酒店服务使用独立 schema `smart_hotel_hotel`，账号 `sh_hotel_domain` 只读 `hotel_info`、`room_type`、`room_type_image`、`room_price`，仅允许更新每日房价。原有酒店、房型、图片、房价主键完整保留。用户库仍为 `smart_hotel_user`，订单与实体房态仍在 `smart_hotel_business`。

三个 schema 部署在同一 Ubuntu MySQL 实例。这是数据库命名空间和权限隔离，不是三台数据库服务器；库存服务仍连接业务 schema，但使用专门库存账号。业务服务仍保留流程中必要的库存读取，库存写入由库存进程唯一负责。

业务库内原四张酒店表仅作为旧快照保留，应用账号不能读取。业务与库存账号同样不能查询新酒店库。酒店账号不能读取业务库存、实体客房、订单或用户。跨域房型引用保留 ID、移除数据库外键，改为迁移核对和服务边界保证；没有删除原业务数据。

## 调用关系

- 酒店公开可订房型查询通过库存服务批量取逐日数量，每批最多 100 房型、366 天；退房日期不包含，缺失日期不补造。任一批失败返回 503，不返回部分可订结果。
- 业务中的旧公开兼容查询也使用同一库存边界，不再绕过库存进程读取库存表。管理员库存查询直接调用库存服务；实体客房查询仍由业务服务负责。
- 库存服务修改每日总量时，通过酒店容量 API 读取房型实体容量，先完成网络请求，再锁定库存行，检查占用数量、容量和版本。酒店不可用时修改失败，库存数量与版本保持不变。
- 酒店容量查询只查自己的房型表，不回调库存；库存查询只读自己的库存表，不查询酒店容量。这两条内部调用不构成递归请求。
- 内部 API 验证随机内部密钥，网关不公开。Feign 读请求设有超时，不通过 SQL 降级绕过权限。

房价修改后只影响新的报价；既有订单金额保留。订单、入住、退房、清洁、审计和原 MOCK 续住支付行为保持原有归属，尚未进一步拆成独立订单或实体客房服务。

## 迁移及回退

当前环境已成功切换，普通重启无需再迁移。完整备份与私有报告在 `.run/db-backups/20261112-103948-7698c0/`，阶段 `HOTEL_SPLIT_READY`；包含业务库、用户库、酒店库 SQL，原配置、授权快照、逐表行数和内容指纹，不能上传 GitHub。

工具执行前要求 Java 停机。迁移先将完整备份恢复到隔离副本并比较全部数据，复制四张酒店表、核对所有跨域房型 ID，再修改权限并原子更新 `.env`。启动脚本要求 `HOTEL_DATA_LAYOUT=split` 或工具生成的 `rollback-shared`，防止误用旧数据源。

`DbSplit.mergeCurrent` 已识别独立酒店库。回退合并采用最新用户库、最新业务库和最新酒店库；不会拿业务库里的旧酒店快照覆盖现价。此次隔离演练专门修改了副本房价，并加入待决库存日志，验证新价格和待决日志都保留。没有在实际开发库激活回退；数据库布局回退仍需停机备份，且不等于撤销已有服务权限边界。

全新环境依次完成 `Migrate-Databases.ps1` 的 Rehearse/Migrate、`Initialize-HotelRead.ps1`、`Upgrade-Inventory.ps1`、`Upgrade-InventoryRemote.ps1`、`Upgrade-HotelWrite.ps1`、`Upgrade-HotelCatalog.ps1`，最后 `Split-HotelDatabase.ps1`。各迁移可用 `-DependencyHost` 和 `-JdbcJar` 指定依赖。中途失败保持停机检查 manifest，不能直接手改连接或盲目重复执行。

## 已有环境启动

Ubuntu 的 MySQL、Redis、Nacos 保持启动；Windows 项目根目录执行：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
```

前端终端在 `smarthotel-frontend` 执行 `npm run dev`。停止仍使用 `Stop-Cloud.ps1`，数据卷保留。Navicat 酒店连接使用 Ubuntu IPv4、端口 13306、库 `smart_hotel_hotel`、账号 `sh_hotel_domain`，密码取私有 `.env` 的 `HOTEL_MYSQL_PASSWORD`。

## 验证边界

最终自动化和实际接口结果见 [验证记录](verification.md)。本轮用户尚未人工回归，不记录为人工验收通过。完整应用容器部署、多数据库实例故障、生产压力和真实外部支付尚未验证。
