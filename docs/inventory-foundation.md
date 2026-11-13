# 库存指令幂等与持久补偿（本地事务阶段）

> 历史阶段记录：当前已继续完成独立库存进程，最新边界见 [库存服务说明](inventory-remote.md)。

2026-11-11 已接入下单预占、模拟支付与支付宝回调确认、用户取消、超时关单、退款释放、续住和管理员房量调整。库存写操作仍在 business 服务和本地数据库事务中；尚未迁成远程库存写服务，没有宣称分布式事务已实现。

## 数据与一致性边界

MySQL room_inventory 的 total_qty / locked_qty / sold_qty 是可售库存依据。InventoryCommands 按订单、操作键、日期顺序加锁，检查整个区间，再统一修改库存。库存不足或任一天缺失时整个事务回滚；操作表、订单和待同步队列也同时回滚。

- booking_request：客户 ID + requestId 唯一，同一次预订传输重试返回原订单。相同编号换预订内容返回 409；失败事务不保留占位，可以重试。同一客户的请求编号不能被他人复用来取得订单。
- inventory_operation：RESERVE、CONFIRM、RELEASE_LOCKED、RELEASE_SOLD 与带目标日期的 EXTEND 操作，按订单生成稳定键并记录参数。已完成指令再次提交不重复改变库存；参数冲突拒绝执行。旧订单无需重建，之后的确认或释放会留下新操作记录。
- inventory_cache_sync：按房型和日期合并待同步任务，记录状态、版本、尝试次数、下次时间和错误类别。与库存变化一起提交，不依赖进程内回调存活。

前端为一次预订意图保留请求编号；相同表单发生网络重试时沿用编号，修改内容或重新打开预订弹窗时生成新编号，并阻止进行中的重复点击。旧客户端未传 requestId 时保持原语义：每次创建新订单，不承诺这类请求具有重复下单去重能力。取消、重复支付或重复续住等业务接口可能返回状态冲突；库存副作用不会重复执行。

## Redis 同步与故障

事务提交后唤醒独立执行器；执行器队列满或进程退出不丢任务，定时器每 5 秒扫描数据库待办。Redis 使用数据库当前的绝对可售数量 SET，不重放增减指令。

同步期间锁住对应数据库库存行并读取任务版本，Lua 原子比较 Redis 版本后设置最新可售值；低版本迟到指令不能覆盖高版本。在 Redis 应用成功后提交同步状态。若 SET 成功但数据库确认失败，重试重新读取当前库存再 SET，不能重复释放或覆盖成旧版本。失败保留 PENDING，记录错误类别并退避 10 秒；Redis 连接和命令超时为 2 秒。进程停机后由下一次启动继续扫描。

查询房型价格与可售数量使用数据库；Redis 投影允许短暂延迟，不能据此独立承诺可售。Redis 作为登录验证码等其他功能的依赖仍可能影响那些功能，不宣称整个应用不受 Redis 故障影响。

旧退款同步台账继续保留，改成同步绝对库存后标记完成；不会再按 refundId 对 Redis 加一遍数量。管理员库存重建也走持久队列，旧调试接口不能直接扣减 Redis 库存。

## 已有环境升级与回退

当前开发环境已升级，不要重复导入初始化 SQL。新装空数据库会按 database/17_inventory_operations.sql 创建三张表。

已有环境在备份并停止 Java 后运行：

```powershell
.\scripts\Stop-Cloud.ps1
.\scripts\Upgrade-Inventory.ps1 -DependencyHost 192.168.229.131
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
```

升级工具完整备份业务库，实际恢复并比较全部原表，再新增三张表、授予业务账号相应 DML 权限，预置库存缓存待办。未重置原房量、订单、用户或密码。升级前备份及演练清单在私有 `.run/db-backups/20261111-195639-581944`，阶段为 INVENTORY_FOUNDATION_READY。

回退合并工具已改为保留当前库新增表，演练在隔离库写入操作记录、预订请求映射和待同步记录后合并，逐表比较通过。只在隔离副本演练过，实际开发库没有执行回退。回退必须使用能保留这些新表的当前工具；直接导入分库前旧 SQL 会丢失新数据。

## 验证证据

事务测试使用真实 H2 事务和竞争连接，Redis 边界注入故障：重复预占/确认/退款、取消与超时共用释放键、库存不足的多日原子性、后续写失败整体回滚、Redis 故障保留待办、SET 后确认丢失重试、6 个请求抢最后一间房只有一个成功、预订请求冲突和作用域隔离。

实际 MySQL/Redis/Nacos 四服务环境的 8 项新接口检查通过：并发重复下单同一订单、单次扣减、相同请求号参数冲突、重复取消、预占后入住人保存失败回滚、失败请求重试、定时关单单次释放、四服务健康。另有 18 项业务回归通过，覆盖退款、入住退房、续住与重复续住、评价、签到、密码与权限失效。合成账号已停用，不修改真实账号角色。

最终全工程 verify 共 80 项测试通过。实际 Redis 上的隔离合成键测试也验证了迟到旧版本被拒绝、当前版本重放安全；测试键已清理。报告另见 `.run/inventory-redis-fence-report.json`。

报告：`.run/inventory-foundation-report.json`、`.run/inventory-business-regression-report.json`。前端生产构建通过。支付宝真实回调与外部退款未联网验证，生产负载和多副本调度未压测。

启动检查修复了一处执行器配置类与 Bean 同名冲突，已加入 Spring 上下文回归用例。

## 手动检查

刷新前端后创建并取消订单，检查房型可售数恢复。Navicat 业务库可只读查看：

```sql
SELECT operation_key, order_id, operation_type, state, created_at
FROM inventory_operation ORDER BY created_at DESC LIMIT 20;

SELECT state, COUNT(*) FROM inventory_cache_sync GROUP BY state;

SELECT room_type_id, inventory_date, attempts, last_error, next_attempt_at
FROM inventory_cache_sync WHERE state = 'PENDING' LIMIT 20;
```

正常时待办会收敛为 DONE。保留日志和待办再排查故障，不全量清空 Redis、不删表、不删除数据卷。

下一阶段才将库存写入抽成远程服务，并设计跨服务的预占查询、确认/释放、租约回收及订单提交与补偿协议。当前实现没有把本地事务假定成跨 HTTP 原子提交。
