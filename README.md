<div align="center">

<img src="app/src/main/res/drawable-nodpi/lvlv_artwork.png" width="96" height="96" alt="驴驴课表图标" />

# 驴驴课表

**山东交通学院专属安卓课表应用** · 一次登录 WebVPN，离线秒开课表、成绩、空教室、培养方案与考试

[![Version](https://img.shields.io/badge/Version-v1.6.0_(79)-1B1B1F?style=flat-square)](https://github.com/cao-gift/sdjtu-timetable/releases)
[![Android](https://img.shields.io/badge/Android-7.0+-2F5D3A?style=flat-square)](https://lvlv.freeblock.cn)
[![License](https://img.shields.io/badge/License-GPL--3.0-555450?style=flat-square)](LICENSE)
[![Website](https://img.shields.io/badge/官网直连-lvlv.freeblock.cn-FF4A1C?style=flat-square)](https://lvlv.freeblock.cn)

**简体中文** · [English](README_EN.md)

[界面预览](#界面预览) · [交院教务](#交院教务) · [日常体验](#日常体验) · [相比原版](#相比原版) · [安装与构建](#安装与构建) · [隐私与许可](#隐私与许可)

</div>

---

## 界面预览

> 展示图内课程与教师信息已脱敏替换；默认启用 **磨砂玻璃** 主题，可在设置中一键切换 **纸墨** 主题（均支持浅色 / 深色）。

<table>
  <tr>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/01-timetable.jpg" alt="极简周课表" />
      <br /><sub><b>周课表 · 离线秒开</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/02-grades.jpg" alt="学业成绩与 GPA 趋势" />
      <br /><sub><b>成绩与 GPA 趋势</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/03-rooms.jpg" alt="实时空教室矩阵" />
      <br /><sub><b>实时空教室矩阵</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/04-exams.jpg" alt="考试时间轴" />
      <br /><sub><b>考试时间轴与提醒</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/05-plan.jpg" alt="培养方案离线查" />
      <br /><sub><b>培养方案离线查</b></sub>
    </td>
  </tr>
</table>

---

## 交院教务

应用内置山东交通学院统一门户（`wwebvpn.sdjtu.edu.cn`），校内校外均可直连同步，五项教务共用同一登录会话：

| 模块 | 核心能力 | 离线支持 |
| :--- | :--- | :---: |
| **课表同步** | 课表页顶栏一键同步当前 / 历史学期；自动合并相邻连堂，精准区分课程名与课程号，保留完整地点与自建课程 | 离线可读 |
| **学业成绩** | 按学期汇总成绩与绩点，内置 GPA 趋势图、分数升/降序排列、定时查新成绩通知与「点我翻开」防剧透卡片 | 离线可读 |
| **查空教室** | 进入手动触发实时查询（不缓存），默认预置无影山校区、1 号教学楼、当前周次与星期，按节次矩阵展示空闲时段 | 实时直连 |
| **培养方案** | 同步旧版强智「培养方案执行计划」，统计总学分与必修门数，支持学期刻度筛选、课程体系分组与关键词检索 | 离线可读 |
| **考试安排** | 自动合并当学期「期末考试」与「随堂提前期末」，去重后按发丝时间轴排序，支持考前提醒与写入手机日历 | 离线可读 |
| **自动登录** | 可选开启，使用 Android Keystore（AES-256-GCM）本机加密保存门户凭据；遇验证码时自动切到可见页面验证 | 可选开关 |

> [!TIP]
> 交院旧版强智的「教室课表查询」与「培养方案执行计划」均位于学生主页子框架中。若学校改版导致自动跳转失败，可点击「在教务中定位」，手动打开目标页面后再点「读取此页」完成兜底解析。

---

## 日常体验

- **精准课表交互**：一屏看完一整周；左右滑或按住左上角大号周数拖动刻度切换周次；正在上的课「走时间」并显示剩余分钟，开课前 15 分钟出现倒计时；长按空白格子上下拖直接画课。
- **地点简化与作息校准**：自动提取课表高频校区/教学楼前缀，轻点即可隐藏并实时预览（详情与导出保留原地点）；已预置交院上下课时刻（`08:30–11:45`、`13:30–16:45`、`19:00–20:35`）与 2026–2027 第一学期首周日期（`2026-08-24`），支持一键校准周次与法定节假日调休。
- **桌面小组件与贴心提醒**：2×2 至宽幅「今日课程」桌面小组件（支持自定义背景图与实时倒计时）；默认开启「课块下雨提示」（内置无影山 / 长清 / 威海三校区固定坐标查 Open-Meteo，免定位权限）；支持后台调课变动通知、课前静音提醒、通知栏常驻下一节课与课程作业附图截止提醒。
- **小工具与同学约空课**：内置 GPA 目标模拟器（目标反推均分 & 预估课程测算）、单节课成本与缺勤记录；同学间可通过二维码图片或分享码互换脱敏课表（不含学号与教师），一键算出共同空闲时段。
- **双主题与数据掌控**：默认「磨砂玻璃」、可选「纸墨」，均支持浅色 / 深色与圆形展开切换；支持一键直写系统日历与全量 JSON 本地备份恢复。

---

## 相比原版

本项目基于开源项目 [augustlies/tangli（烫梨）](https://github.com/augustlies/tangli) 修改，遵循 **GPL-3.0** 协议开源，采用独立包名 `app.lvlv.timetable`（可与原版共存）。

<details>
<summary>展开查看相对原版的主要新增与精简项</summary>

- **交院专属新增**：实时查空教室（节次矩阵与筛选）、培养方案执行计划离线查、期末与提前期末合并时间轴、Android Keystore 本机加密自动登录、连堂自动合并、地点隐藏词组快捷提取与实时预览、课程号显示开关、手动「在教务中定位 → 读取此页」兜底。
- **单校聚焦与精简**：锁定山东交通学院 WebVPN 与旧版强智解析（修复首行课程号误识为课名）；移除多校目录/选校适配器、DNS 广告过滤、VPN 服务及 AI 文本指令（`lvlv://`）功能；底栏精简为「课表 / 设置」，默认启用磨砂玻璃主题并统一全应用 Gauge 组件规范。

</details>

---

## 安装与构建

### 下载安装

1. 前往 **[国内官网（高速直连）](https://lvlv.freeblock.cn)** 或 **[GitHub Releases](https://github.com/cao-gift/sdjtu-timetable/releases)** 下载最新 APK，在 **Android 7.0+** 设备安装（应用内检查更新优先直连官网并校验 SHA-256，自动支持多路镜像回退）。
2. 打开「设置 → 登录统一门户」登录交院 WebVPN 即可同步数据。

### 本地编译（JDK 17）

```bash
./gradlew assembleDebug        # 构建调试版 APK
./gradlew testReleaseUnitTest  # 运行 JVM 单元测试
```

---

## 隐私与许可

- **隐私承诺**：纯学生个人非官方工具，与山东交通学院及教务厂商无关。**无自有云端服务器、无商业广告与行为统计 SDK**，所有学业数据与可选加密凭据仅存于本机。详情见 [隐私声明](https://lvlv.freeblock.cn/privacy.html) 与 [更新日志](CHANGELOG.md)。
- **反馈渠道**：欢迎提交 [GitHub Issue](https://github.com/cao-gift/sdjtu-timetable/issues) 或发邮件至 `3514088137@qq.com`（请附页面与复现步骤）。
- **开源致谢**：基于 [tangli（烫梨）](https://github.com/augustlies/tangli)（© 2026 August Lies）衍生，遵循 [GPL-3.0](LICENSE)；磨砂玻璃风格参考 [Uiverse.io](https://uiverse.io) galaxy 组件（MIT）；二维码基于 ZXing（Apache-2.0）。
