# Nacos 第一阶段：Ubuntu 单机部署

现有 Java 应用继续按固定地址运行。本阶段先部署并确认 Nacos 控制台，随后配置应用凭据、注册地址和配置中心，再验证发现调用；仅启动 Nacos 不代表项目已接入。

使用 `nacos/nacos-server:v3.0.3`，与本项目 Alibaba 2025.0.0.0 的组件基线一致。此单机 Docker + Derby 部署用于开发学习，数据和日志分别持久化到独立卷，不使用业务库。

## 准备

Windows 项目根目录：

```powershell
.\scripts\Prepare-Nacos.ps1 -VmAddress 192.168.229.131
```

生成 `.run/smarthotel-cloud-nacos.zip`，包含 Compose 和随机服务器认证密钥，不包含业务库密码。服务器密钥保存在 `.run/nacos-server.env`，重复打包会复用。这里的 Token 是签名密钥，不是控制台登录密码。ZIP、密钥文件都不要提交 GitHub。

## 部署

FinalShell 上传 ZIP 至 `/home/chenglei`，执行：

```bash
mkdir -p ~/smarthotel-cloud-nacos
unzip -n ~/smarthotel-cloud-nacos.zip -d ~/smarthotel-cloud-nacos
cd ~/smarthotel-cloud-nacos
chmod 600 .env
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs --tail=60 nacos
```

`unzip -n` 保留已有密钥，适用于首次安装。已有数据时不要删除卷重新初始化，也不要改用默认密钥排错。

首次拉取可能需要等待。日志出现 Nacos 启动成功，再从 Windows 打开 `http://192.168.229.131:8088/index.html`，按页面初始化管理员 `nacos` 的密码并保存。不要在聊天里发送密码。Nacos 3 不应假设存在默认登录密码。

端口：8088 是映射后的控制台端口；8848 是应用配置的 Server 地址；9848 是客户端 gRPC 端口。三个端口都绑定指定虚拟机 IPv4。当前开发环境使用 VMware 内网，不配置公网转发。

## 后续接入检查

- Windows 能访问 8848 和 9848，控制台能登录。
- 应用 `.env` 添加 NACOS_ADDR、NACOS_USERNAME、NACOS_PASSWORD；Server 地址不是控制台 8088。
- 本机三个 Java 服务绑定 127.0.0.1，注册发现必须发布本机其他应用可达的地址；不能把 Ubuntu IP 当成 Windows 应用 IP。后续接入需明确处理，不能直接使用不可达的自动注册地址。
- 切换应用后，在 SMARTHOTEL_CLOUD 分组看到三个健康实例，并验证网关和 Feign 确实通过服务名调用。
- 发布一个无密码的演示配置验证读取和更新；不要直接迁移全部数据库密码。

## 第二阶段：Windows 应用注册

控制台初始化密码并登录成功后，在 Windows 项目根目录 `.env` 末尾增加（不覆盖已有数据库和 JWT 配置）：

```dotenv
NACOS_ADDR=192.168.229.131:8848
NACOS_USERNAME=nacos
NACOS_PASSWORD=替换为你刚设置的Nacos登录密码
```

值不要加引号。当前解析器按整行第一个等号分隔，密码中的 `#`、`=` 不会被当成注释或命令；不要把实际密码发到聊天里。

在 Windows 项目根目录 PowerShell 依次执行：

```powershell
.\scripts\Stop-Cloud.ps1
mvn package -DskipTests
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131 -Nacos
```

需要重新打包三个 JAR，以包含更新的 Nacos profile。若找不到 mvn，用本机 Maven 的绝对路径执行同样参数。前端和 Ubuntu 的三个中间件不需要停止。

启动脚本会检查 Nacos 8848、9848 端口可达且密码已填写。三个 Java 应用都在 Windows 同一主机运行，并绑定回环地址，因此此开发 profile 将实例注册为 `127.0.0.1`。Nacos 只是保存地址并推送给 Windows 客户端，业务 HTTP 调用在 Windows 进程之间进行。该设置不能直接用于多台服务器部署；多主机时需修改监听地址和 NACOS_INSTANCE_IP 为彼此可达的地址。

在控制台「服务管理 → 服务列表」中选择 public 命名空间，Group 查询 `SMARTHOTEL_CLOUD`，应看到：

- smarthotel-user-service：8081
- smarthotel-business-service：8082
- smarthotel-gateway：8080

先验证三项服务都存在健康实例，再从前端验证查询和登录后的订单列表。Nacos 配置列表暂时为空是正常的；可选 import 不要求预先发布配置。完成注册验证后再进入配置中心实验，避免同时改动全部配置。

要回到固定地址模式，执行停止脚本后，用不带 `-Nacos` 的原启动命令启动。数据库、账号和订单不受 profile 切换影响。

## 第三阶段：配置中心公告实验

2026-11-11 已实际验证注册模式和中文公告动态下发，过程见 `verification.md`。Java 启动脚本统一使用 UTF-8；IDEA 直接运行时也应增加 VM option `-Dfile.encoding=UTF-8`。

控制台「配置管理 → 配置列表」选择 public，查找 Data ID `smarthotel-business-service.yml`、Group `SMARTHOTEL_CLOUD`。本轮已创建初始配置，副本位于 `deploy/nacos-config/smarthotel-business-service.yml`：

```yaml
smarthotel:
  notice:
    message: "欢迎入住智慧酒店，请提前准备好入住证件。"
```

点击编辑，只修改 message 的文字，点击发布，等待几秒后刷新前端首页，酒店介绍前应显示新公告。修改时不要删除其他已有配置。也可用浏览器访问 `http://127.0.0.1:8080/api/v1/hotel/notice` 核对返回值。此实验不需要重启 Java 进程。

配置不存在时不显示公告。接口每次请求读取 Environment 当前值，仅暴露公告字段，不提供任意环境变量查询接口。Nacos 的配置刷新不意味着所有数据库连接池、定时任务参数都适合直接热更新；暂不把密码迁入该文件。

普通停止在 Ubuntu 本目录执行 `sudo docker compose stop`，不使用 `down -v`。

官方参考：[Docker 快速开始](https://nacos.io/en/docs/v3.0/quickstart/quick-start-docker/)、[权限初始化](https://nacos.io/en/docs/v3.0/manual/admin/auth/)、[Alibaba 版本说明](https://sca.aliyun.com/docs/2025.x/overview/version-explain/)。
