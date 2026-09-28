# PlanLock Android 客户端

PlanLock Android 客户端负责计划与日程管理、设备本地执行限制、记录存储、后端同步和 Compose 界面展示。客户端采用 Gradle Kotlin DSL 多模块结构，默认启用 Mock 数据，未启动后端也可以构建和体验主要流程。

## 环境要求

- JDK 17
- Android Studio 或命令行 Android SDK
- Android SDK Platform 36
- Android 7.0（API 24）或更高版本的设备/模拟器

主要构建参数：

| 参数 | 值 |
| --- | --- |
| applicationId | `com.planlock.app` |
| minSdk | 24 |
| targetSdk | 35 |
| compileSdk | 36 |
| JVM target | 17 |
| versionName | `1.0.0` |

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `app` | 应用入口、导航、前台执行服务和模块组装 |
| `core-api` | Retrofit API、DTO、会话持久化、Mock API 和天气数据 |
| `core-engine` | 计划执行判定、前台应用识别、无障碍拦截和反馈 |
| `core-dnd` | 勿扰模式决策与控制 |
| `core-security` | 权限检查、备用密码、无障碍健康状态和设备管理员 |
| `core-record` | Room 数据库、执行记录、计划快照和待同步队列 |
| `core-sync` | 本地数据与后端的拉取、推送和冲突处理 |
| `core-design` | Compose 主题、设计令牌、通用组件与动效 |
| `feature-plan` | 今日、计划列表、计划详情与编辑界面 |
| `feature-guide` | 首次使用引导和场景模板 |
| `feature-stats` | 今日摘要、周统计和执行记录 |
| `feature-settings` | 账号、设备、权限、位置与应用设置 |

## 构建与测试

在 Windows PowerShell 中：

```powershell
cd android
.\gradlew.bat clean assembleDebug
.\gradlew.bat testDebugUnitTest
```

在 macOS 或 Linux 中：

```bash
cd android
./gradlew clean assembleDebug
./gradlew testDebugUnitTest
```

调试 APK 输出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开 `android/` 目录，选择 `app` 配置运行。

## 数据源与后端联调

`core-api` 在构建时读取两个 Gradle 属性：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `PLANLOCK_USE_MOCK` | `true` | 是否使用内置 Mock API |
| `PLANLOCK_API_BASE_URL` | `http://10.0.2.2:8080/api/v1/` | 真实后端的 API 根地址，必须以 `/` 结尾 |

### 使用 Mock 数据

无需额外参数，直接构建即可：

```powershell
.\gradlew.bat assembleDebug
```

### Android 模拟器连接本机后端

先按照 [后端文档](../backend/README.md) 启动服务，再关闭 Mock：

```powershell
.\gradlew.bat assembleDebug -PPLANLOCK_USE_MOCK=false
```

Android 模拟器中的 `10.0.2.2` 会映射到开发机的回环地址。

### 真机连接开发机后端

确保手机和开发机位于同一局域网，并使用开发机的局域网 IP：

```powershell
.\gradlew.bat assembleDebug `
  -PPLANLOCK_USE_MOCK=false `
  -PPLANLOCK_API_BASE_URL=http://192.168.1.10:8080/api/v1/
```

后端监听 `0.0.0.0:8080`，但操作系统防火墙仍需允许局域网访问该端口。HTTP 明文访问仅适合本地调试；正式环境应使用 HTTPS。

## 系统权限

| 权限或能力 | 用途 | 是否影响核心限制功能 |
| --- | --- | --- |
| 使用情况访问 | 判断当前前台应用 | 是 |
| 无障碍服务 | 发现受限应用并展示拦截反馈 | 是 |
| 设备管理器 | 提供更完整的设备管理能力 | 视策略而定 |
| 勿扰权限 | 在执行期间控制勿扰状态 | 否，可降级 |
| 通知权限 | 展示前台服务和计划状态 | 否，可降级 |
| 已安装应用列表 | 组装黑/白名单及严格模式豁免集合 | 是 |
| 粗略位置 | 获取天气位置 | 否 |

权限需要由用户在系统设置中主动开启。应用内的“设置 → 权限设置”用于查看状态并跳转到对应系统页面。

## 本地数据

- Room 保存计划、日程、策略快照、执行记录和待同步操作。
- DataStore 保存会话、主题、权限提示和其他轻量设置。
- Room schema 导出到 `core-record/schemas/`；修改数据库结构时应同步提交新的 schema 文件和迁移逻辑。

## 常用命令

```powershell
# 查看可用任务
.\gradlew.bat tasks

# 运行所有 Debug 单元测试
.\gradlew.bat testDebugUnitTest

# 构建 Debug APK
.\gradlew.bat assembleDebug

# 构建 Release APK（当前未配置签名）
.\gradlew.bat assembleRelease
```

## 已知边界

- Android 应用不能直接修改其他应用在系统桌面上的图标；PlanLock 通过运行时拦截提供限制反馈。
- 不同厂商对后台运行、无障碍服务和省电策略的处理不同，真机验证时需要检查相关系统限制。
- 当前 Release 构建未启用代码压缩，也未在仓库中配置发布签名，不应直接作为正式发布包。
