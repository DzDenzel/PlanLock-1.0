# 律刻 PlanLock

<p align="center">
  <img src="android/app/src/main/res/mipmap-nodpi/ic_launcher.png" width="112" alt="律刻 PlanLock 图标">
</p>

<p align="center">面向 Android 的计划执行与专注管理应用。</p>

PlanLock 将计划、日程、应用限制和执行记录整合在一起。用户可以为计划设置普通黑名单或严格白名单策略；计划执行期间，客户端通过使用情况访问、无障碍服务和勿扰权限识别并拦截分心应用。项目采用单仓库结构，包含 Android 客户端和 Spring Boot 后端。

> 当前版本：`1.0.0`。项目仍处于开发阶段，默认 Android 构建使用本地 Mock 数据，便于在未启动后端时体验主要流程。

## 功能概览

- 创建、编辑、暂停和删除计划，支持重复规则与跨天时段
- 管理独立日程，并在今日时间轴中统一展示
- 为计划配置普通黑名单或严格白名单策略
- 在执行期间拦截受限应用、控制勿扰模式并记录违规与解锁行为
- 查看今日摘要、周统计和执行记录，导出计划及记录数据
- 游客设备注册、账号登录、设备管理和多端数据同步
- 通过本地数据提供计划助手、天气信息与离线兜底

Android 普通应用无法修改其他应用在系统桌面上的图标。PlanLock 会在受限应用启动时执行拦截并展示限制反馈，不会将系统桌面图标置灰。

## 技术栈

| 组件 | 主要技术 | 运行要求 |
| --- | --- | --- |
| Android 客户端 | Kotlin、Jetpack Compose、Room、Retrofit、DataStore | JDK 17、Android SDK 36、Android 7.0+ |
| 后端服务 | Java 21、Spring Boot 3.5、MyBatis、MySQL、Redis、JWT | JDK 21、Maven 3.9+、MySQL 8、Redis |

## 仓库结构

```text
PlanLock-1.0/
├── android/   Android 多模块客户端
├── backend/   Spring Boot + Maven 多模块后端
└── README.md  项目总览
```

- [Android 开发文档](android/README.md)
- [后端开发文档](backend/README.md)

## 快速开始

### 运行 Android 客户端

默认构建启用 Mock 数据，不依赖后端：

```powershell
cd android
.\gradlew.bat assembleDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。如需运行单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

连接真实后端时必须同时关闭 Mock。Android 模拟器可使用默认的 `10.0.2.2` 访问宿主机：

```powershell
.\gradlew.bat assembleDebug -PPLANLOCK_USE_MOCK=false
```

真机联调还需要指定开发机的局域网地址：

```powershell
.\gradlew.bat assembleDebug `
  -PPLANLOCK_USE_MOCK=false `
  -PPLANLOCK_API_BASE_URL=http://192.168.1.10:8080/api/v1/
```

详细的模块说明、权限配置和联调方式见 [Android README](android/README.md)。

### 运行后端

1. 启动 MySQL 8 和 Redis。
2. 执行 `backend/planlock-app/src/main/resources/db/schema.sql` 初始化数据库。
3. 设置数据库、Redis 和 JWT 相关环境变量。
4. 启动 Spring Boot 应用。

```powershell
cd backend
mvn clean test
mvn install -DskipTests
mvn -pl planlock-app spring-boot:run
```

服务默认监听 `http://localhost:8080`，REST API 前缀为 `/api/v1`。完整配置与接口分组见 [后端 README](backend/README.md)。

## Android 权限说明

完整的计划执行能力需要用户在系统设置中授权使用情况访问、无障碍服务、设备管理器、勿扰和通知权限。定位权限仅用于获取天气信息，不影响计划限制功能。

严格模式还会读取本机已安装应用列表以完成豁免与拦截判定；相关信息仅用于设备本地执行逻辑。

## 开发与验证

提交改动前建议至少运行与修改范围对应的测试：

```powershell
# Android
cd android
.\gradlew.bat testDebugUnitTest

# 后端
cd ..\backend
mvn test
```

涉及 Android 打包或模块依赖调整时，再执行 `assembleDebug`；涉及后端启动配置或模块组装时，再执行 `mvn -pl planlock-app -am package`。

## 安全提示

仓库中的数据库、Redis 和 JWT 默认值仅用于本地开发。部署到共享、测试或生产环境前，必须通过环境变量设置独立的强密码和高强度 JWT 密钥，并限制 MySQL、Redis 与后端端口的网络访问范围。

## 许可证

本仓库当前未声明开源许可证。在许可证文件补充前，请勿假定代码可被自由复制、修改或分发。
