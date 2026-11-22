---
name: app-release-update
description: 打包并发布 XSTAR Android 应用更新，生成 APK 和 WebDAV latest.json。Use when the user asks to 打包升级、生成发布包、生成更新 JSON、发布新版本或准备应用内更新。
---

# XSTAR 应用更新发布

用于生成本项目的 Android 更新包和 WebDAV 更新清单。

## 固定发布配置

- Android 模块：`app`
- 版本配置：`app/build.gradle.kts` 的 `defaultConfig.versionCode` 和 `versionName`
- 构建任务：`./gradlew.bat :app:assembleDebug`
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 发布目录：`release/`
- 发布 APK：`release/xstar-<versionName>.apk`
- 更新清单：`release/latest.json`
- WebDAV 清单路径：`/App/latest.json`
- WebDAV APK 路径：`/App/xstar-<versionName>.apk`
- 更新 JSON 地址：`https://dav.xstar-life.fnos.net:443/App/latest.json`

## JSON 格式

生成以下字段：

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "forceUpdate": false,
  "title": "发现新版本 1.1.0",
  "changelog": [],
  "apkPath": "/App/xstar-1.1.0.apk",
  "apkSize": 0,
  "sha256": "",
  "publishedAt": "2026-11-22T00:00:00Z"
}
```

`apkSize` 必须是发布 APK 的实际字节数，`sha256` 必须是发布 APK 的 SHA-256 小写值。

## 发布流程

1. 检查工作区状态，保留用户已有修改，不覆盖无关文件。
2. 读取 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
3. 确认新版本 `versionCode` 大于上一发布版本；如果没有明确升级版本，询问用户需要的版本号，不要擅自重复发布同一版本。
4. 将用户提供的更新内容写入 `changelog`；没有提供时使用简短默认更新说明。
5. 执行：

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

6. 复制 APK：

   ```powershell
   Copy-Item app/build/outputs/apk/debug/app-debug.apk release/xstar-<versionName>.apk -Force
   ```

7. 使用 `Get-Item` 和 `Get-FileHash -Algorithm SHA256` 获取发布包大小和哈希。
8. 生成 `release/latest.json`，使用 UTF-8 编码，并写入实际大小、SHA-256、版本和发布时间。
9. 使用 Node.js 或等效 JSON 解析器校验 JSON；再次计算发布 APK 的大小和 SHA-256，确保与 JSON 完全一致。
10. 最终报告 APK 路径、JSON 路径、版本、大小、SHA-256 和构建结果。

## 安全和签名要求

- 当前项目没有配置正式 release signing 时，使用 Debug APK，并明确告知用户。
- 不要把 NAS 用户名、密码、密钥或 Token 写入 `latest.json`、技能文件或脚本。
- APK 必须始终使用与已安装版本相同的签名证书才能覆盖升级。
- 生产发布前应配置固定的 release keystore；不能用新密钥替代旧密钥。
- 不要执行上传、删除旧发布包或覆盖 NAS 文件，除非用户明确要求上传或清理。

## 常见用户请求映射

- “生成当前版本发布包”：读取当前版本并构建；若版本未递增，先提示风险。
- “升级到 1.2.0”：修改 `versionName`，同时将 `versionCode` 设置为用户指定或下一个更大值，再构建。
- “生成更新 JSON”：必须基于实际 APK 重新计算大小和 SHA-256，不能使用占位值。
- “上传到 NAS”：先完成本地构建和校验，再询问或执行用户明确指定的上传步骤。
