# LanDrop 开发与验证

## 版本与目录

首版本名称为 `1.0.0`。Android `versionCode` 使用 `24`，用于让现有开发安装正常升级；它不表示此前发布过 23 个 LanDrop 版本。后续版本递增该值，不重置应用 ID、协议身份、用户配置或记录数据库版本。

| 路径 | 内容 |
|---|---|
| `app/src/main/java/` | 原生界面、发现、服务、传输和存储 |
| `app/src/main/res/` | 布局、语言、明暗配色及多密度图标 |
| `app/src/test/java/` | JVM 单元测试 |
| `app/src/android/java/` | Android 设备测试 |
| `scripts/generate_icons.py` | 图标生成工具，仅在容器中使用 |
| `fastlane/metadata/android/` | 应用描述、图标和按 versionCode 命名的版本说明 |
| `.github/workflows/` | 构建、API 21 测试及标签发布流程 |

同级 `localsend/` 是互通审查参考源码，`landrop-ui-design/` 是离线设计稿。应用构建不依赖它们；兼容待办中的跨目录源码链接需要参考仓库。

## 容器构建

构建、依赖安装、测试和环境模拟均在容器内执行，不在宿主机安装工具链。保持现有 Gradle Wrapper 和插件配置：

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle / AGP | 9.7.1 / 9.3.2 |
| Compile SDK / build-tools | 36 / 36.0.0 |
| 最低 / 目标 API | 21 / 33 |
| UI | Android Views/XML，AGP 内置 Kotlin |

在已配置 SDK、JDK 和依赖缓存的构建容器内，以仓库根目录为工作目录执行：

```sh
./gradlew lintDebug test assembleDebug assembleDebugAndroidTest --no-daemon --stacktrace
```

离线验证需预先准备完整缓存，再加 `--offline`。现有本地验证镜像为 `localhost/landrop-tv-build:local`；使用的 SDK 和 Gradle 缓存卷可能保留旧项目名称，不属于应用品牌或协议标识。

| 产物 | 路径 |
|---|---|
| 调试应用 | `app/build/outputs/apk/debug/app-debug.apk` |
| 设备测试包 | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Lint | `app/build/reports/lint-results-debug.html` |
| 单元测试 | `app/build/reports/tests/testDebugUnitTest/` |

`build/`、`app/build/`、`.gradle/`、`.kotlin/` 和 `.idea/` 是生成产物、缓存或本地 IDE 配置，不进入源码版本管理。多密度 PNG、VectorDrawable、设计截图和第三方许可属于源文件，不按缓存删除。

## 设备与互通验收

生成设备测试包不等于执行测试。给容器接入电视或在容器内配置 Android 模拟器后执行：

```sh
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
```

至少验证遥控焦点与返回、接受／拒绝、改名、取消和失败、存储授权撤销、记录升级、其他应用上方确认、开机延迟联网、有线／Wi-Fi 切换和待机恢复。LocalSend 双向验收按 [兼容待办](TV-TODO.md) 记录对端版本、网络、字节／文本／目录比较及日志。

本地 Podman 容器通过不能替代真实 CI、电视实机或双机互通。每次验证应分别记录执行的命令、环境、结果和未覆盖范围；日志及 APK 保存在本地生成目录，不作为产品文档中的固定通过结论。

## 发布准备

1. 核对 `versionName`、递增的 `versionCode`、[版本说明](../CHANGELOG.md) 和两种语言的 fastlane 说明。`CHANGELOG.md` 使用 `## [v版本号]` 标题，供标签发布流程提取。
2. 完成对应范围的容器检查及实机／互通验收；仍未完成的事项必须在说明中保留。
3. 签名构建使用既有 `ANDROID_KEYSTORE_PATH`、`ANDROID_RELEASE_STORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS` 和 `ANDROID_RELEASE_KEY_PASSWORD` 环境变量，不把密钥提交源码。
4. 发布工作流由 `v*` 标签触发；本地编译通过不表示标签发布或应用商店发布已执行。

## 设计与协议回归

每次改动按 [设计与兼容性核对](design-review.md) 区分源码对应、浏览器原型、JVM 回环、Android 设备和原版双机验收。`TlsPeerClientTest` 使用公开测试证书，文件位于 `app/src/test/resources/tls/`，不进入应用 APK；生产私钥由 AndroidKeyStore 保存。

原型检查在已有浏览器容器中运行 `landrop-ui-design/validation/check.py`；它只验证模拟界面。原生 SDK 构建容器不能代替电视的证书提供方、文件提供方和悬浮授权环境。
