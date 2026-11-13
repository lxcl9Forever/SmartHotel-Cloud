# 酒店查询服务：迁移第一步

> 本文记录当时阶段。当前已完成酒店资料分库，最新归属与限制见 [酒店分库说明](hotel-database-separation.md)。

新增 smarthotel-hotel-service（8083），负责 GET /api/v1/room-types、其子路径、GET /api/v1/hotel 和 /api/v1/hotel/room-types/**。网关优先路由至该服务，Nacos 模式通过服务名发现。公告仍归原业务服务，避免覆盖已配置的动态公告。

该服务独立进程、只读连接池、独立数据库账号 sh_hotel_read。仅可读取 hotel_info、room_type、room_type_image、room_price、room_inventory；拒绝库存更新、订单与用户查询。现阶段这些表仍在 smart_hotel_business，同步看到原管理接口更新的数据；尚未迁移酒店数据库，也没有将库存写操作拆为远程调用。

订单、支付、取消、退款、入住退房的写事务保持原归属。原业务中的查询代码仍被内部业务/推荐使用，后续应随内部查询契约替换后删除；不能将本步称为完整酒店与库存领域拆分。

下一步先设计 orderId/requestId 幂等预占、确认/释放、超时回收、事务提交后可靠投递和对账，再迁出库存写入。网络超时不能简单认定远端扣减失败。

当前环境账号已配置，日常仍运行 scripts/Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos。现在依次启动用户、业务、酒店、网关四个进程，Stop-Cloud.ps1 按反序停止。

首次安装在用户分库完成后执行 scripts/Initialize-HotelRead.ps1。需要 JDK17、MySQL JDBC 驱动与私有 .env 管理凭据；默认驱动 .run/tools/mysql-connector-j.jar，可传 -JdbcJar。脚本创建只读账号、验证权限、备份并原子更新私有 .env；检测已有配置时拒绝重建账号。不要用业务写账号代替只读账号。

数据库回退工具在生成回退库时也给现有酒店只读账号授予目标五表 SELECT，并更新 HOTEL_MYSQL_DATABASE，避免仍读取切换前的库。实际开发库未执行回退。

手动验证：刷新房型价格库存、查看房型详情；创建订单并取消后同日期可售数量恢复；管理员调整房价库存后新查询立即体现。Nacos 列表应有四个健康服务。停酒店服务后酒店查询应失败，不自动回退到业务服务继续提供旧结果。


库存幂等与持久补偿的本地事务基础已完成，当前进展见 [库存基础](inventory-foundation.md)。酒店查询继续只读；库存写服务尚未迁出。
