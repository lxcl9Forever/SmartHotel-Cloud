# Windows 开发、Ubuntu 运行 MySQL 和 Redis

> 当前库存已独立数据库，最新账号、迁移和启动说明见 [库存分库说明](inventory-database-separation.md)。

当前支持 Windows IDEA 运行五个 Java 服务和 Vue 前端，Ubuntu Docker 单独运行本项目的 MySQL 8.0.44、Redis 7.4.7。无需 Windows Docker Desktop，不复用 Ubuntu 既有项目的数据库和缓存。

## Windows 准备上传包

在项目根目录 PowerShell 执行，替换成虚拟机当前 IPv4：

```powershell
.\scripts\Prepare-UbuntuDependencies.ps1 -VmAddress 192.168.229.131
```

没有 `.env` 时自动生成随机开发密码；已有 `.env` 时保留密码。输出 `.run/smarthotel-cloud-ubuntu.zip`，内含独立 Compose、完整 SQL 和仅供数据库/Redis 使用的密码。该文件被 Git 忽略，不要分享或上传 GitHub。JWT 和内部服务密钥保留在 Windows。

## Ubuntu 启动

通过 FinalShell 将 ZIP 上传至 `/home/chenglei`，在 Ubuntu 执行：

```bash
mkdir -p ~/smarthotel-cloud
unzip -n ~/smarthotel-cloud-ubuntu.zip -d ~/smarthotel-cloud
cd ~/smarthotel-cloud
chmod 600 .env
sudo docker compose up -d
sudo docker compose ps
```

`unzip -n` 不覆盖已有配置，适用于首次部署。若没有 unzip，先 `sudo apt install unzip`；如提示找不到软件包，先 `sudo apt update`。后续 SQL 更新需单独迁移，不能删除已有数据卷重置。不要用重新生成密码来修复已经初始化的数据卷。

两个容器均为 healthy 后，首次安装须在 Windows 按 [数据库分离指南](database-separation.md) 演练并迁移，再启动 Java。已有分库环境无需重迁。端口只绑定指定虚拟机 IPv4，使用独立项目名 `smarthotel-cloud-ubuntu` 和独立数据卷，旧服务的 3306、6379 不受影响。

如果镜像拉取失败，保留现有卷，记录实际错误。可用已知镜像代理拉取后打原标签，或按原 Tomcat 方式从电脑 `docker save` 后上传并 `docker load`；应使用本文件中的确切版本。

首次全新安装还需完成酒店与库存各阶段升级，顺序见 [酒店分库说明](hotel-database-separation.md)。已有开发库已经切换，不重跑迁移。

## Windows 启动应用

```powershell
Test-NetConnection 192.168.229.131 -Port 13306
Test-NetConnection 192.168.229.131 -Port 16379
mvn verify
.\scripts\Start-Cloud.ps1 -DependencyHost 192.168.229.131
```

两次 `TcpTestSucceeded` 应为 True。若为 False，检查 VM 当前 IP、容器健康状态及 `sudo ufw status`，再按 Windows 的 VMware 网卡实际地址添加所需端口访问规则，不要直接关闭防火墙。

新开 Windows 终端：

```powershell
cd smarthotel-frontend
npm ci
npm run dev
```

浏览器打开 Vite 输出的 Local 地址。首次注册账号；管理员初始化按 `setup.md`。Navicat 的地址为虚拟机 IP，端口 13306，用户库 smart_hotel_user，用户名 sh_user_app，密码取 Windows `.env` 中 USER_MYSQL_PASSWORD；业务库 smart_hotel_business，用户名 sh_business_app，密码取 BUSINESS_MYSQL_PASSWORD。旧 smarthotel 连接仅用于读取原快照库。

IDEA 直接运行启动类时需设置对应环境变量；脚本默认从 `.env` 读取并启动打包 JAR。`-DependencyHost` 只改变数据库和 Redis 地址，五个 Java 服务仍在 Windows 本地互相调用。启用现有 Nacos 时加 `-Nacos`，并保持 Ubuntu 上的 Nacos 容器运行。

虚拟机 DHCP 地址变化后，需调整 Ubuntu `.env` 的 DEPENDENCY_BIND_ADDRESS 并重新 `sudo docker compose up -d`，同时更新 Windows 启动参数。已有密码和数据卷保持一致。

## 停止

Windows 执行 `.\scripts\Stop-Cloud.ps1`；Ubuntu 在 `~/smarthotel-cloud` 执行 `sudo docker compose stop`。不要用 `down -v` 做日常关机。

2026-11-11 已在 Windows Java + Ubuntu MySQL/Redis/Nacos 环境完成分库后 16 项接口回归；不是完整应用容器部署或生产集群验证。

## 库存服务阶段补充

已迁移环境继续使用原 Start-Cloud.ps1 命令（加 -Nacos 时启用发现），会读取 `.env` 中 `INVENTORY_MODE=remote` 和库存专用账号启动五个服务。不要重复导入 SQL、运行旧基础迁移或仅手改 mode。IDEA 单独启动时也必须配置 remote 模式和库存服务连接，优先沿用已有脚本启动，前端 5173 不变。
