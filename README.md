# AdBlocker Xposed 🛡️

基于 **LSPosed API 93** 的 Android 广告屏蔽模块。

## 功能特性

- 🛡️ **广告屏蔽** — 拦截应用内广告网络请求（HTTP/HTTPS/DNS/WebView）
- 🌐 **Mosdns 规则支持** — 兼容 anti-AD、AdGuard、Hagezi 等主流规则源
- 🔍 **App 广告扫描** — 扫描已安装应用，检测广告 SDK 和广告域名
- 📡 **网络抓包** — 记录应用网络请求，查看 URL、Headers、参数
- 🔑 **关键词过滤** — 自定义关键词匹配拦截
- 📋 **多规则格式** — 支持 Hosts、AdBlock、域名列表、Mosdns 格式
- 🎨 **Material Design 3** — 现代化 UI 设计

## 支持的规则源

| 规则源 | 说明 |
|--------|------|
| anti-AD | Mosdns 推荐，中文广告过滤 |
| AdGuard DNS | AdGuard 官方 DNS 过滤列表 |
| Steven Black | 经典 hosts 合并列表 |
| OISD Small/Big | 轻量级/完整版 |
| Hagezi Pro/Multi | 高质量多合一规则 |

## 支持的广告 SDK 检测

Google Ads · Facebook Ads · 友盟 UMeng · 腾讯广告 GDT · 百度广告 · 头条/穿山甲 · 快手广告 · AppLovin · Unity Ads · Vungle · IronSource · Chartboost · MoPub · InMobi · Firebase · Adjust 等

## 安装要求

- Android 9.0+ (API 28+)
- LSPosed 框架已激活
- Root 权限

## 安装步骤

1. 在 LSPosed Manager 中启用模块
2. 选择要屏蔽广告的应用（建议全选）
3. 重启目标应用
4. 进入 App 首页点击「导入 Mosdns 规则」
5. 点击「扫描全部」检测应用广告 SDK

## 构建

```bash
./gradlew assembleRelease
```

输出文件位于 `app/build/outputs/apk/release/`

GitHub Actions 自动构建：push 到 main/master 分支或创建 tag 即可触发。

## 技术栈

- **语言**: Kotlin
- **UI**: Material Design 3 + ViewBinding
- **数据库**: Room
- **网络**: OkHttp
- **Xposed**: LSPosed API 93

## 许可证

MIT License
