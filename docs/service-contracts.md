# 当前服务契约

本轮新增契约：`OrderSnapshot` 是无数据库注解的订单快照，`MarketingContract` 定义预留/决议/报价；integration 的 `WorkflowContract` 定义 Claim/Claimed/Complete/Abort，`DomainEvent` 定义带聚合版本和删除标识的事件信封。字段与实际 Java record 对应，控制器内部接口均校验内部凭据。

- `/internal/v1/orders/{id}` 与 customer-view：订单所有者读取，顾客视图再次核对用户名归属。
- `/internal/v1/order-workflows/claim|complete|abort`：订单占用和状态协调，校验操作 ID、摘要、领域和动作；重复完成幂等。
- `/internal/v1/marketing/reservations/prepare|decide`：营销持久预留，重复标识必须携带相同内容；提前撤销防悬挂。
- `/internal/v1/domain-events`：至少一次事件接收，Inbox 去重和内容冲突检测、版本单调与墓碑。
- `/internal/v1/domain-events/health|replay`：仅内部运维的积压查询及 DEAD 重排，网关不发布。

浏览器既有 `/api/v1` 路径保留，由网关转发实际领域；用户目录、酒店与库存已有契约继续使用。订单、支付等数据库实体不作为跨服务参数，共享模块不含持久化 Mapper。读模型异步收敛，付款和办理以权威工作流状态为准。

以下为此前阶段的契约演进记录，涉及当时业务模块布局的叙述以 README 和 full-domain-architecture.md 的当前架构为准。

# 服务契约整理：库存读取（2026-11-12）

库存内部读取接口 /internal/v1/inventory/query 与 /internal/v1/inventory/day 使用 InventoryReadContract.Snapshot，不再将 RoomInventory 持久化实体作为 HTTP 返回类型。Snapshot 是没有 MyBatis 注解、没有实体依赖的独立 DTO，目前仍放在 common 的 contract 包；common 的其余共享模型尚未全部拆除。

版本一响应字段保持 inventoryId、roomTypeId、inventoryDate、totalQty、lockedQty、soldQty、version、createTime、updateTime。路径、内部密钥校验、日期区间、批量上限、排序、空列表及故障响应保持原语义。业务与酒店适配器在各自进程将 DTO 转为本地使用的模型；库存控制器直接把查询行映射为 DTO，避免未来数据库实体增加字段时自动对外暴露。

这是保持 JSON 格式兼容的内部契约调整，不改变数据库表和用户数据。新增字段需显式修改契约并补兼容性检查；删除/改名字段属于破坏性变更，应另设版本路径或协商迁移。当前没有宣称完成生产滚动升级测试。

本轮新增 JSON 字段集合、序列化往返及持久化依赖隔离测试，原库存 HTTP 日期/认证测试与客户端分批、上游故障/空响应测试继续保留。最终结果见 verification.md。本轮不改变支付退款、原 MOCK 续住或入住退房事务，应用启动方式不变。

后续继续处理酒店资料契约和 common 共享模型，营销、评价、统计模块及完整容器部署仍有整理工作。没有把本轮库存契约调整当成全部微服务改造完成。

## 酒店资料查询契约

酒店资料内部接口 /internal/v1/hotel-catalog/** 改用 HotelSnapshot、TypeSnapshot、ImageSnapshot、PriceSnapshot 四种独立 DTO，HotelReply 使用 HotelSnapshot。保留原字段名称、类型和 hotel 包装层；服务端仍用自己的实体和 Mapper 查询后显式映射 DTO，业务适配器再映射为本地模型。

停售房型按编号仍可查询、缺失房型仍省略、空编号不会返回全部资料；房价继续按房型和日期排序并排除退房日。认证、批量上限及故障返回保持现有契约，没有新增缓存或本地 SQL 降级。新增四种 DTO 字段集合/JSON 往返及持久化依赖隔离测试，原客户端批次、失效和缺失资料测试保留。

此次仅覆盖酒店资料查询契约。酒店管理员查询契约在后续小节整理，common 中其他共享实体仍待收拢；业务与入住还在原进程中。当前没有数据库迁移、公开接口重命名或前端修改，启动命令保持不变。实际验证结果见 verification.md。

## 酒店管理契约

/internal/v1/hotel-admin/room-types、prices 返回独立 TypeSnapshot、PriceSnapshot；保留的内部 inventories 查询返回库存 Snapshot。业务入口在本地转换为原响应模型，前端和公开路径保持现状。管理员鉴权与审计仍由业务入口负责，酒店内部接口继续校验内部密钥。

改价请求沿用独立的 UpdatePriceRequest DTO，仍验证必填、正数价格，并保留原错误语义。库存写入仍属于库存进程；实体房态仍在原业务事务中。管理查询上游空响应或空行明确返回 503，不作为成功的空列表吞掉。

增加业务 Feign 契约约束：递归检查请求、响应及嵌套 DTO 字段，禁止依赖 com.smarthotel.entity 包。覆盖用户会话、用户目录、酒店资料、酒店管理和库存五个业务客户端。共享实体仍存在于进程内部，这不等于 common 已经全部清理完成。


## 服务内部模型归属（2026-11-12）

common 原有 25 个实体中，本轮将 6 个用户域实体（Customer、CustomerSavedGuest、Employee、MemberLevel、SysRole、SysUser）迁到用户服务，将 13 个订单、支付退款、入住房态、营销统计实体迁到业务服务。AdminOrderDetailVO 只用于业务管理响应，也移到业务服务，避免 common 反向依赖 BookingGuest。保留原包名、字段、MyBatis 映射和公开 JSON；每个迁移后的类只有一个生产源码归属，不在其他模块复制同名实体。

common 暂留 HotelInfo、RoomType、RoomTypeImage、RoomPrice、RoomInventory、OperationLogRecord 六类。前五类仍用于酒店与业务进程内适配，最后一类用于两域各自审计表；它们尚未全部本地化，所以当前仍保留 common 的 MyBatis 注解依赖。后续应区分酒店持久化实体、业务本地读取模型和各域审计实体，不能以删除依赖或简单复制同名类冒充清理完成。

新增 common 模块类路径隔离测试，防止独属实体和订单详情 VO 回流共享包；原五种 Feign 客户端递归 DTO 边界检查继续执行。本轮不修改数据库、迁移 ID、账号或权限，不更改前端和支付/续住语义。通过 clean verify 和实际 JAR 检查确认迁移生效，避免旧 target/classes 掩盖依赖。


## 共享持久化模型清理完成（2026-11-12）

common 不再包含持久化实体，也不再直接依赖 MyBatis 注解。四个酒店实体位于 hotel.entity，业务端使用 model.hotel 中无持久化注解的本地读取模型；库存读取模型分别归业务与酒店进程。用户与业务审计实体分别位于 user.audit、business.audit，各自写所属库的 operation_log。旧本地库存历史测试使用 fixture.entity 中仅测试可见的模型，不进入生产 JAR。

字段兼容测试验证独立 DTO 与本地读取模型的所有属性类型和值，包括价格精度、库存版本与时间；Feign 契约约束同时拒绝 entity、audit 和 model 包，避免改包名绕过检查。数据库结构、已有 ID、公开 JSON、权限和 MOCK 续住语义保持现状。
