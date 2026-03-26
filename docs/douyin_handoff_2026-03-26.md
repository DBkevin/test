# 抖音真机联调交接说明

日期：2026-03-26

适用对象：
- 继续跟进抖音真机链路的人
- 需要复用今天“标题缺失但可继续抓卡”结论的人

## 1. 今天验证的代码基线

今天真正跑通真机链路的代码基线是：

- 提交：`53e141c`
- 提交信息：`fix: allow douyin capture without detail title`

这版的核心语义是：

- 不再因为详情页顶部商家名提取失败而直接跳过抓取
- 允许 `DouyinPlugin` 在商家名缺失时继续输出团购卡
- 再由 `CaptureCoordinator` 用当前任务医院名回填 `merchant_name` / `hospital_name` / `search_keyword`

## 2. 今天使用的云编译基线

- 分支：`codex/douyin-expand-chain-capture`
- GitHub Actions run：`23583638231`
- 状态：`success`
- URL：
  - `https://github.com/DBkevin/test/actions/runs/23583638231`
- 使用的 APK：
  - `D:\project\adb\ci-apk-23583638231\app-debug.apk`

## 3. 今天再次确认下来的固定装机流程

今天这台 HyperOS 设备上，稳定可复用的流程是：

1. `adb uninstall com.example.a11yframework`
2. `adb push D:\project\adb\ci-apk-23583638231\app-debug.apk /data/local/tmp/app-debug.apk`
3. `adb shell pm install -t -r /data/local/tmp/app-debug.apk`
4. 安装后回读：
   - `adb shell settings --user 0 get secure enabled_accessibility_services`
   - `adb shell settings --user 0 get secure accessibility_enabled`
5. 再写回：
   - `adb shell settings --user 0 put secure enabled_accessibility_services com.example.a11yframework/com.example.a11yframework.core.FrameworkAccessibilityService`
   - `adb shell settings --user 0 put secure accessibility_enabled 1`
6. 最后用 `adb shell dumpsys accessibility` 确认服务同时出现在：
   - `Bound services`
   - `Enabled services`

补充现象：

- 如果 HyperOS 拉起 `com.miui.securitycenter/.permcenter.install.AdbInstallActivity`，需要用户先在手机上确认安装，再重试同一条 `pm install`
- 这轮里安装和 ADB 直写无障碍都成功了

## 4. 今天真实跑通的链路

真实执行顺序是：

1. 宿主点击 `开始抖音采集`
2. 日志记录：
   - `2026-03-26 16:06:19` `Pending local capture saved: hospital=郑州美莱`
3. 手动冷启动抖音
4. 自动切到团购首页并进入专用搜索链路
5. 自动搜索 `郑州美莱`
6. 自动点击商家结果卡上半部分
7. 进入：
   - `2026-03-26 16:07:32` `LifePoiActivity`
8. `2026-03-26 16:07:33` 记录：
   - `Merchant result opened by entry band tap: name=郑州美莱, round=0`

## 5. 今天最关键的成功日志

这轮已经不是“接近成功”，而是实际采集成功。

第一段关键日志：

- `2026-03-26 16:07:36` `Target merchant page detected: merchant=, signals=6, cards=8`
- `2026-03-26 16:07:36` `Hospital info: name=, honors=关注 | 回头客1千+ | 无隐形消费`
- `2026-03-26 16:07:36` `Merchant name missing from detail header, continue capture and wait for coordinator backfill`
- `2026-03-26 16:07:36` `Visible group-buy cards: 5`
- `2026-03-26 16:07:36` `Scraped 5 visible cards from <pending_backfill>`
- `2026-03-26 16:07:36` `已接收抓取结果: hospital=郑州美莱, added=5, updated=0, total=5`

第二段关键日志：

- `2026-03-26 16:07:38` `Visible group-buy cards: 4`
- `2026-03-26 16:07:38` `Scraped 4 visible cards from <pending_backfill>`
- `2026-03-26 16:07:38` `已接收抓取结果: hospital=郑州美莱, added=2, updated=2, total=7`

尾部结束日志：

- `2026-03-26 16:07:44` `Detected Douyin merchant tail boundary, stop merchant collection`
- `2026-03-26 16:07:44` `Stop collection after scroll settle before scrape: round=3`
- `2026-03-26 16:07:44` `Pending local capture cleared`

结论：

- 当前这条链路已经可以稳定拿到 `7` 条团购记录
- “商家标题在无障碍树里为空”不再阻塞抓取

## 6. 今天新确认的页面语义

今天确认了 4 条很重要的项目记忆：

1. 在这台机型上，商家详情页的可见团购卡有时会顶到屏幕最上方，所以日志里的
   - `firstCardTop=0`
   并不代表异常
2. 详情页顶部商家名可能完全不出现在无障碍文本里，但 `关注 / 回头客 / 无隐形消费` 和团购卡仍然能稳定出现
3. `PageMatcher: 页面匹配失败：商家详情页` 目前不等于整条链路失败
   - 真正有效的采集路径是插件侧 `isTargetPage + scrapeData`
4. 进入店铺尾部推荐区后反复出现
   - `Detected hard non-groupbuy module without merchant context, skip target page`
   - `Not a target page, skipping`
   现在应解释为“团购已采完并滚到尾部后的正常现象”，不是新的详情页误判 blocker

## 7. 今天的取证资产

本轮可复用的关键资产有：

- 详情页截图：
  - `D:\project\adb\artifacts\douyin-after-fix-2026-03-26.png`
- 本轮后段截图：
  - `D:\project\adb\artifacts\douyin-postrun-2026-03-26-v2.png`
- 宿主前置页面 XML：
  - `D:\project\adb\artifacts\host-before-run-2026-03-26-v2.xml`
- 宿主回看页面 XML：
  - `D:\project\adb\artifacts\host-after-run-2026-03-26-v2.xml`
- 详情页日志主证据：
  - `adb logcat -d -v time -s DouyinPlugin CaptureCoordinator A11yFramework`

补充说明：

- 这轮 `uiautomator dump` 对抖音详情页仍可能返回 `ERROR: could not get idle state.`
- 因此今天的主证据以日志和截图为准，不要因为 XML 缺失就把这轮结论推翻

## 8. 下一步最值得做什么

“先跑通”这个目标今天已经完成。

接下来更值得做的是：

1. 让 `PageMatcher` 的商家详情页规则和当前成功链路对齐，减少日志噪音
2. 把宿主 App 的结果展示补得更直观，避免每次都要靠日志确认 `total`
3. 在保留当前成功路径的前提下，再补一轮双跑验证，确认 `7` 条不是偶然值

## 9. 一句话总结

今天已经把问题从“详情页识别不稳导致 0 记录”推进到了“真实进入详情页、即使标题缺失也能抓卡并回填，最终稳定拿到 7 条记录”。
