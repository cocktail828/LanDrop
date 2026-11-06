# LanDrop

[English](../README.md) | [简体中文](README.zh-CN.md)

LanDrop 是面向 **Android TV** 的轻量局域网收发工具，使用 Kotlin 和原生 Android Views 实现。采用 LocalSend 协议，是独立的非官方客户端。

LanDrop 首版本为 **1.0.0**。源码中的版本号不表示已上架或已完成互通认证。

## 使用方式

- 打开后显示设备名、接收状态和保存位置。接收使用前台服务，默认启用后台接收和开机自启。
- 收到文件或文本后选择接受／拒绝。文本需主动复制，链接和 APK 不会自动打开。
- 主动发送：**选择内容 → 文件／文件夹／剪贴板 → 附近设备**，从自动发现的设备中选择对端。
- 查看进度、取消任务和查看收发记录；清空记录不会删除文件。
- 设置提供设备名、语言、保存目录、后台接收、开机自启及电视悬浮确认。支持系统明暗主题和遥控焦点。

默认保存到 `Download/localsend/`，也可选择系统授权目录。最低支持 Android 5.0，界面仅按电视场景维护。手机、电脑上的 LocalSend 是预期收发对端。

## 兼容状态

LanDrop 对外声明 **HTTP 接收地址**、LocalSend 协议 `2.0` 和 TCP/UDP `53317`。注册、发送和取消可连接 HTTP／HTTPS 对端；HTTPS 使用 AndroidKeyStore 持久客户端证书及对端证书指纹核验，对应原版 mTLS 身份要求。这不表示入站 HTTP 已加密，也不表示已完成真实设备默认互通验收。

文件夹收发保留相对路径，拒绝路径越界；文本仍需明确接受和主动复制。已补待确认请求取消并调整确认等待时间；已接入 422 摘要失败及最多三次整文件重试，重试存储、取消竞态与实机互通仍需验收。见 [兼容待办](TV-TODO.md) 和 [设计与兼容性核对](design-review.md)。

双方需处于可互访的局域网。网络隔离、组播过滤、厂商后台限制、强行停止和待机断网可能影响接收。目前不支持浏览器链接接收和断点续传。

## 文档导航

- [TV 使用指南](android-receiver.md)：收发流程、存储、权限和设置。
- [开发与验证](development.md)：容器构建、测试、产物和发布准备。
- [兼容待办](TV-TODO.md)：剩余工作及验收标准。
- [LocalSend 协议 2.2 参考](LocalSend-Protocol-v2.2.md)。
- [版本说明](../CHANGELOG.md)：从 LanDrop 1.0.0 开始记录。
- 离线设计稿位于同级 `landrop-ui-design/`，传输使用模拟数据。

## 许可

使用 [Apache License 2.0](../LICENSE)，第三方许可及上游署名见 [NOTICE](../NOTICE)。

保留上游贡献致谢：[@FXDaily](https://github.com/FXDaily) 曾提供 TLS 指纹处理及 Android 5.1 接收修复。
