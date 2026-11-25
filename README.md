# 驴驴课表 · 官网静态站点（`site` 分支）

本分支仅用于托管 **驴驴课表** 的官网静态文件与安装包直链，与应用源码分支（[`main`](https://github.com/cao-gift/sdjtu-timetable/tree/main)）完全分离。

- **官网地址**：<https://lvlv.freeblock.cn>
- **更新清单**：<https://lvlv.freeblock.cn/latest.json>
- **更新日志**：<https://lvlv.freeblock.cn/changelog.html>
- **应用源码**：[cao-gift/sdjtu-timetable (`main`)](https://github.com/cao-gift/sdjtu-timetable/tree/main)
- **历史发布**：[GitHub Releases](https://github.com/cao-gift/sdjtu-timetable/releases)

---

## 目录说明

| 路径 | 用途 |
|---|---|
| `index.html` | 官网首页，动态读取 `./latest.json` 展示最新版本、校验值与更新说明 |
| `changelog.html` | 完整更新日志页面，读取 `./changelog.json` 渲染各版本变更记录 |
| `changelog.json` | 结构化更新日志数据（由 `main` 分支 `scripts/sync-site-changelog.mjs` 生成） |
| `latest.json` | 应用内检查更新清单，包含版本号、更新说明、SHA-256 与 APK 下载地址 |
| `downloads/` | 最新正式版 APK 安装包目录（仅保留当前最新版本，供官网与客户端直连下载） |
| `icon.png` | 官网图标与网页 Favicon |

> 本地调试或 CLI 产生的 `.edgeone/` 与 `.env` 已写入 `.gitignore`，不会提交到仓库。

---

## 自动部署

本分支已直接绑定至腾讯云 **EdgeOne Pages** 生产分支：

- **仓库**：`cao-gift/sdjtu-timetable`
- **生产分支**：`site`
- **构建命令**：无（纯静态文件）
- **输出目录**：`.`（仓库根目录）

每次向 `site` 分支推送提交后，EdgeOne Pages 会自动拉取并发布至 <https://lvlv.freeblock.cn>。

---

## 更新方式

在项目根目录（`main` 分支已通过 `git worktree` 将 `site/` 挂载到本分支）完成新版本构建后：

```bash
# 1. 同步 APK 与 latest.json（可选：同步 changelog.json）
node scripts/sync-site-release.mjs --apk <apk路径> --tag vX.Y.Z
node scripts/sync-site-changelog.mjs

# 2. 在 site 目录提交并推送，触发 EdgeOne Pages 自动部署
cd site
git add -A
git commit -m "chore(site): release vX.Y.Z"
git push
```

---

## 联系与反馈

如有问题或建议，请前往主仓库提交 [Issue](https://github.com/cao-gift/sdjtu-timetable/issues)，或发送邮件至 `3514088137@qq.com`。
