# 支付宝沙箱与 API 智能选房

本版本移除续住按钮、预览与提交接口；顾客及员工不能发起新续住。数据库历史费用不删除，历史 EXTEND 工作流的完成处理仅用于恢复既有记录。下单、取消、支付退款、入住退房继续保留。

## 升级已有数据

先停 Windows Java 服务，保留 Ubuntu MySQL、Redis。项目根目录执行：

```powershell
.\scripts\Upgrade-PaymentAi.ps1 -DependencyHost 192.168.229.131 -JdbcJar '<本机 mysql-connector-j.jar 绝对路径>'
```

脚本备份付款库并在临时库恢复校验后，新增 `payment_channel_audit`，核对原支付数量和付款账号访问边界，最后原子写入 `PAYMENT_AI_LAYOUT=enabled`。私有备份保存在 `.run/db-backups/`。正常运行不要反复初始化数据库。2026-11-16 本机 Ubuntu 数据已执行该升级。

该表不保存私钥、API Key、住客证件或渠道响应原文。恢复旧版本时先停服务、核对当前新产生的数据；不要用旧备份直接覆盖升级后新订单。尚有异常收款时，旧单体回退导出会拒绝执行，先完成核对与退回。

## Windows 私有配置

真实值只写入根目录已忽略的 `.env`，不要放入 README、Git 或浏览器变量。

```dotenv
ALIPAY_ENABLED=true
ALIPAY_GATEWAY=https://openapi-sandbox.dl.alipaydev.com/gateway.do
ALIPAY_APP_ID=<沙箱应用ID>
ALIPAY_PRIVATE_KEY_FILE=<应用私钥文件绝对路径>
ALIPAY_PUBLIC_KEY_FILE=<支付宝公钥文件绝对路径>
PAYMENT_MOCK_ENABLED=false
AI_LLM_ENABLED=true
AI_LLM_URL=https://api.deepseek.com/chat/completions
AI_LLM_MODEL=deepseek-flash
AI_LLM_API_KEY=<模型API密钥>
```

支付宝仅接受 HTTPS 官方沙箱域名；这是渠道真实协议联调，使用沙箱买家虚拟资金，不支持生产收款。应用私钥与支付宝公钥不可互换。启动脚本仅给付款服务注入支付凭据，仅给订单服务注入模型密钥。

本机无法接收支付宝服务器的回调时可留空 `ALIPAY_NOTIFY_URL`：付款后在订单详情点击查询结果，后台也会周期主动查单。若配置回调，地址必须为支付宝可访问的网关通知地址，验签与金额核对通过才确认收款；浏览器跳回页面本身不代表支付成功。

重新启动：

```powershell
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
npm run dev --prefix smarthotel-frontend
```

## 容器配置

除上述环境变量外，设置 `ALIPAY_KEY_DIRECTORY` 为宿主机私有目录，里面仅放 `app-private-key.txt` 和 `alipay-public-key.txt`，限制文件访问权限。Compose 只读挂载到付款容器 `/run/secrets/alipay/`，不把密钥打入镜像。模型密钥只注入订单容器。不要把 Windows 密钥路径用作 Linux 容器路径。外部服务默认不开启。

## 沙箱付款与退款验收

1. 顾客登录，查指定日期价格和库存，下单看到待支付。
2. 订单详情应显示“支付宝沙箱”；配置不完整显示不可用，不能悄悄改走模拟付款。
3. 点击付款，使用支付宝沙箱买家登录收银台，核对金额后付款。
4. 返回订单详情点击查询支付结果。只有签名验证后的渠道查询/通知与本地金额一致，才转为已确认；重复查询不能重复扣库存或记账。
5. 申请退款，管理员审核，等待支付宝退款或退款查询确认后再显示已退款；网络异常不伪造成功，后台按同一退款号继续恢复。
6. 管理员“支付记录”可查询渠道交易、查看核对信息。已取消或关闭订单的迟到收款进入异常收款，确认退回使用固定 `EXRF+支付流水号`，禁止恢复订单占库存。
7. 仅在独立合成测试环境打开 `PAYMENT_MOCK_ENABLED=true`，验收 MOCK 不代表渠道交易成功。真实项目保持 false。

后台每分钟处理一批最近七天待支付记录和未决异常退款。异常收款不会自动退款，管理员先核对；已发起但响应未知的异常退款会按固定退款号自动恢复。普通业务退款继续走原退款审核。渠道异常交易必须通过管理员对账处理，不能直接改数据库状态。

## 智能选房验收

首页智能选房填写入住日期、房间数和自然语言需求，例如“两位成人，住两晚，整体预算一千五，希望安静”。模型只负责理解人数、晚数、预算和偏好；最终房型、报价、促销及库存来自本系统，按房间数核对容量与总价。推荐后仍需正常预订并提交库存预留，推荐不是锁房。

未配置、凭据无效、额度不足、限流、响应格式错误均显示失败原因，不编造推荐或把本地解析当作 API 成功。并发模型请求有限制，调用设有超时。API 调用可能计费，测试使用合成需求，不发送真实住客信息。

## 自动测试的边界

Maven 回归使用本地 HTTP 模型替身和 SDK 替身验证协议、错误、金额、重复收款与退款恢复，不把它们当成真实外部成功。独立 Compose 回归验证九库真实 SQL、权限、工作流和事件最终一致性。是否完成官方 API 调用、买家付款、回调和渠道退款，必须分别记录。

参考：[DeepSeek JSON 模式](https://api-docs.deepseek.com/guides/json_mode/)；[支付宝官方接入指南](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/ai-web-app-payment-integration-guide.html)。

## 2026-11-16 官方接口联调记录

用户明确授权后，DeepSeek `/models` 返回200，配置模型存在；通过实际网关、登录鉴权和订单服务调用模型处理合成需求成功，约3.07秒。推荐房型来自实际酒店库，预订金额与推荐报价一致。

支付宝 SDK 向官方沙箱查询随机不存在的合成交易，返回 `ACQ.TRADE_NOT_EXIST`，响应验签通过。项目实际付款服务生成签名收银台成功，并通过官方查单确认未付款状态，订单保持 `WAIT_PAY/UNPAID`，不伪造支付成功。

本轮实际调用链9项通过，只用隔离合成账号和订单；没有发起买家扣款。买家付款、异步回调、渠道退款仍需分别验收。SDK 原始请求日志关闭，应用只保留业务流水号、简要状态和错误类型，不输出完整签名表单或渠道响应。私有报告保存在本机测试目录，不提交凭据和签名页面。
