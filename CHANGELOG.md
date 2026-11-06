# LanDrop release notes / 版本说明

LanDrop starts its own release history at 1.0.0. Earlier development identifiers are not LanDrop releases. A version entry describes the source, not proof of publication or device validation.

LanDrop 从 1.0.0 开始独立版本记录；此前开发编号不作为 LanDrop 已发布版本。版本说明不表示已经上架或完成实机验收。

## [v1.0.0]

### First version

- Android TV receiver status screen with D-pad navigation and system light/dark themes.
- Foreground receiving, start-on-boot, notification actions, and optional TV overlay confirmation.
- Explicit acceptance of incoming files and text; file, folder, and clipboard sending through discovered nearby devices.
- Transfer progress, cancellation, send/receive records, device rename, language, and save-directory settings.
- Persistent client-certificate identity, pinned HTTPS/mTLS connections to peers, relative folder paths, and pending-request cancellation.
- Checksum mismatch responses use 422 with bounded whole-file retry; failed transfers are distinguished from user cancellation.
- Default save directory: `Download/localsend/`.

### 首版本

- 面向 Android TV 的接收状态主页、遥控操作和系统明暗主题。
- 后台接收、开机自启、通知操作及可选电视悬浮确认。
- 文件与文本先确认接收；主动选择文件、文件夹或剪贴板后，从附近设备发送。
- 传输进度、取消、收发记录、设备改名、语言和保存目录设置。
- 持久客户端证书身份、对端 HTTPS/mTLS 连接及指纹核验、相对目录路径与待确认请求取消。
- 摘要失败返回 422 并进行有限整文件重试，传输失败与用户取消分开记录。
- 默认保存目录为 `Download/localsend/`。

### Known limits / 已知限制

- Local receiving remains HTTP; outgoing connections support HTTP/HTTPS with mTLS. Actual default-client interoperability remains unverified. / 本地仍为 HTTP 接收，出站支持 HTTP／HTTPS 与 mTLS；原版默认配置的实机互通尚未验收。
- Folder paths are preserved in code; storage-provider behavior, checksum retry handling, and confirmation/cancellation races still require acceptance. / 代码已保留目录层级；存储提供方行为、摘要重试和确认／取消竞态仍需验收。
- Real-TV behavior, default LocalSend interoperability, and target CI require separate validation. / 电视实机、原版默认互传及目标 CI 需单独验收。

See [compatibility roadmap / 兼容待办](docs/TV-TODO.md).
