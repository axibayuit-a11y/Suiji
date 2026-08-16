# 应用更新分发与校验

更新日期：2026-08-16

## 下载顺序

检查版本时依次使用 `gh-proxy.com` 和 GitHub 官方 API。下载 APK 时依次尝试
`gh-proxy.com`、`ghproxy.net` 和 GitHub 官方 Release。任一来源中断后，下一来源
会通过 HTTP Range 继续已有临时文件；用户重新点击下载时也会保留有效进度。

公共代理只负责传输公开的 GitHub Release，不接收 GitHub Token。代理不可用时会
自动切换，不把单一第三方站点作为唯一更新来源。

## 安全边界

- 元数据中的 APK 必须来自本项目 GitHub Release 路径，拒绝其他主机和仓库。
- 下载字节数必须与 GitHub Release 资产大小一致。
- 有 GitHub SHA-256 digest 时必须完全匹配。
- APK 包名必须为当前应用包名，版本号必须高于已安装版本。
- APK 签名证书必须与当前安装版本一致；代理无法伪造此签名。
- 所有检查完成后才把临时文件交给 Android 系统安装器。

长期最稳妥的方案仍是部署项目自有域名的下载代理或国内对象存储；届时只需在
`UpdateSourcePolicy` 中替换加速前缀，不影响版本选择、断点续传和安全校验逻辑。
