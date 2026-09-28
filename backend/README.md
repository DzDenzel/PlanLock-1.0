# PlanLock 后端

PlanLock 后端是基于 Java 21 和 Spring Boot 3.5 的 Maven 多模块服务，为 Android 客户端提供账号、设备、计划、日程、策略、记录和同步 API。

## 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8
- Redis 6 或更高版本

默认服务地址为 `http://localhost:8080`，API 前缀为 `/api/v1`。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `planlock-common` | 统一响应、错误处理、JWT 鉴权和 Web 公共配置 |
| `planlock-account` | 游客注册、验证码、登录、设备、备用密码和数据合并 |
| `planlock-plan` | 计划 CRUD、暂停、重复规则和导出 |
| `planlock-schedule` | 独立日程 CRUD 与契约校验 |
| `planlock-strategy` | 计划黑名单、白名单策略的读取与保存 |
| `planlock-record` | 执行、违规、解锁记录，统计聚合和导出 |
| `planlock-sync` | 数据拉取、推送及基于版本号的冲突检测 |
| `planlock-app` | Spring Boot 启动模块，组装所有业务模块 |

## 本地启动

### 1. 准备 MySQL

启动 MySQL 后执行建库脚本：

```powershell
mysql -u root -p -e "source planlock-app/src/main/resources/db/schema.sql"
```

脚本会创建并使用名为 `planlock` 的数据库。应用当前固定连接本机 `3306` 端口；如需修改连接地址，请覆盖 Spring Boot 配置或调整 `planlock-app/src/main/resources/application.yml`。

### 2. 准备 Redis

启动可从本机访问的 Redis 实例。默认配置连接 `localhost:6379`，密码为 `root`。

### 3. 配置环境变量

| 环境变量 | 开发默认值 | 说明 |
| --- | --- | --- |
| `PLANLOCK_DB_USER` | `root` | MySQL 用户名 |
| `PLANLOCK_DB_PASSWORD` | `root` | MySQL 密码 |
| `PLANLOCK_REDIS_HOST` | `localhost` | Redis 主机 |
| `PLANLOCK_REDIS_PORT` | `6379` | Redis 端口 |
| `PLANLOCK_REDIS_PASSWORD` | `root` | Redis 密码 |
| `PLANLOCK_JWT_SECRET` | 内置开发密钥 | JWT 签名密钥 |
| `PLANLOCK_JWT_EXPIRE_MINUTES` | `10080` | Token 有效期，单位为分钟 |

PowerShell 示例：

```powershell
$env:PLANLOCK_DB_USER = 'planlock'
$env:PLANLOCK_DB_PASSWORD = 'replace-with-a-strong-password'
$env:PLANLOCK_REDIS_PASSWORD = 'replace-with-a-strong-password'
$env:PLANLOCK_JWT_SECRET = 'replace-with-a-long-random-secret'
```

如果本地 Redis 未启用密码，应将 `PLANLOCK_REDIS_PASSWORD` 设为空字符串。

### 4. 测试并启动

在 `backend/` 目录执行：

```powershell
mvn clean test
mvn install -DskipTests
mvn -pl planlock-app spring-boot:run
```

服务启动后监听所有网络接口的 `8080` 端口。可以通过 Android 模拟器的 `http://10.0.2.2:8080/api/v1/` 或开发机局域网 IP 联调。

## 构建可执行包

```powershell
mvn -pl planlock-app -am clean package
java -jar planlock-app/target/planlock-app-1.0.0.jar
```

## API 概览

| 分组 | 路径 | 主要能力 |
| --- | --- | --- |
| 认证 | `/api/v1/auth` | 游客设备、验证码、注册、登录和账号合并 |
| 当前用户 | `/api/v1/me` | 设置或重置备用密码 |
| 设备 | `/api/v1/devices` | 查询设备、设置主设备和删除设备 |
| 计划 | `/api/v1/plans` | CRUD、暂停和导出 |
| 日程 | `/api/v1/schedules` | CRUD |
| 策略 | `/api/v1/plans/{planId}/strategy` | 获取与更新黑/白名单策略 |
| 记录 | `/api/v1/records` | 上报执行、违规、解锁记录，查询统计与导出 |
| 同步 | `/api/v1/sync` | 拉取和推送客户端数据 |

除以下公开接口外，`/api/v1/**` 请求都需要携带访问令牌：

- `POST /api/v1/auth/device`
- `POST /api/v1/auth/code/send`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/me/backup-password/reset`

认证请求头格式：

```http
Authorization: Bearer <token>
```

接口统一返回 `ApiResponse` 结构；业务异常由全局异常处理器转换为统一错误码与消息。项目当前未集成 OpenAPI/Swagger，精确的请求字段与响应模型请查看各模块的 `controller` 和 `dto` 包。

## 数据与同步

- MyBatis 将数据库下划线字段映射为 Java 驼峰字段。
- 计划与日程更新使用版本字段检测并发冲突。
- 同步模块提供 pull/push 接口，客户端待同步操作带有唯一 `clientId`。
- 数据库时间配置使用 `Asia/Shanghai`，跨时区部署前应复核业务时间语义。

## 配置与安全

`application.yml` 中的用户名、密码和 JWT 密钥都是方便本地启动的开发默认值。任何共享或生产环境都必须：

- 替换数据库、Redis 和 JWT 密钥；
- 限制 MySQL、Redis 与 `8080` 端口的暴露范围；
- 在反向代理或网关处启用 HTTPS；
- 根据部署环境调整日志级别，避免长期使用 `com.planlock: debug`；
- 使用外部化配置管理敏感信息，不要将真实密钥提交到 Git。

## 常用命令

```powershell
# 运行所有模块测试
mvn test

# 仅运行指定模块及其依赖的测试
mvn -pl planlock-sync -am test

# 检查并打包启动模块
mvn -pl planlock-app -am clean package

# 本地启动
mvn install -DskipTests
mvn -pl planlock-app spring-boot:run
```
