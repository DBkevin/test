# 抖音真机联调交接说明

日期：2026-03-24

适用对象：
- 继续跟进抖音真机链路的人
- 需要复用今天 HyperOS 启用路径和稳定性结论的人

## 1. 今天先改了什么

今天先落了一个最小修复：

- 提交：`dca8430`
- 提交信息：`fix: preserve local capture across service reconnect`

这版只改了一处真正影响恢复语义的逻辑：

- `FrameworkAccessibilityService.executePendingLocalCapture()` 在任务失败返回后，先检查当前协程是否已经被服务销毁取消
- 如果失败其实发生在 `serviceScope` 已取消之后，就不再把它当终态失败去清空 pending
- 目标是避免 HyperOS / 系统短暂拆服务时，把“可恢复的本地采集”误清成终态

同一版里还把“新 APK 的固定装机流程”写回了 `docs/douyin_test_runbook.md`。

## 2. 今天的云编译基线

- 分支：`codex/douyin-expand-chain-capture`
- GitHub Actions run：`23472180985`
- 状态：`success`
- URL：
  - `https://github.com/DBkevin/test/actions/runs/23472180985`
- 使用的 APK：
  - `D:\project\adb\ci-apk-23472180985\app-debug.apk`

## 3. 今天确认下来的新 APK 固定装机流程

本机今天按下面顺序执行，结果稳定：

1. `adb uninstall com.example.a11yframework`
2. `adb install D:\project\adb\ci-apk-23472180985\app-debug.apk`
3. 安装后先检查：
   - `adb shell settings --user 0 get secure enabled_accessibility_services`
   - `adb shell settings --user 0 get secure accessibility_enabled`
4. 如果是新 APK，默认都会被清成：
   - `enabled_accessibility_services = null`
   - `accessibility_enabled = 0`
5. 再决定本轮要测哪条启用路径：
   - ADB 直写
   - ADB 模拟手动通过风险提示

## 4. 今天确认的两条启用路径

### 4.1 ADB 直写路径

执行命令：

- `adb shell settings --user 0 put secure enabled_accessibility_services com.example.a11yframework/com.example.a11yframework.core.FrameworkAccessibilityService`
- `adb shell settings --user 0 put secure accessibility_enabled 1`

今天这台 HyperOS 设备上的实际结果：

- `2026-03-24 11:54:45.061` 日志出现 `Service created`
- `2026-03-24 11:54:45.098` 日志出现 `Service connected`
- `dumpsys accessibility` 中同时能看到：
  - `Bound services`
  - `Enabled services`

结论：

- 这台设备上，ADB 直写不是“假开关”，是真正 bind 成功了

### 4.2 ADB 模拟手动风险确认路径

今天已经把 HyperOS 风险页路径走通，固定流程如下：

1. 打开 `无障碍功能`
2. 进入 `已下载的应用`
3. 点击 `智能辅助服务`
4. 打开 `使用“智能辅助服务”`
5. 进入 `com.miui.securitycenter` 风险页
6. 先勾选：
   - `我已知晓可能存在的风险，并自愿承担可能导致的后果`
7. 等倒计时结束
8. 点击 `确定`

今天保留下来的关键素材：

- `D:\project\adb\artifacts\accessibility-settings-2026-03-24.png`
- `D:\project\adb\artifacts\accessibility-downloaded-apps-off-2026-03-24.png`
- `D:\project\adb\artifacts\accessibility-service-detail-2026-03-24.png`
- `D:\project\adb\artifacts\accessibility-risk-dialog-2026-03-24.png`

风险页里最关键的 UI 事实：

- `确定` 按钮一开始是倒计时禁用态
- 必须先勾风险确认复选框
- 再等倒计时结束才能点 `确定`

补充现象：

- 手动确认完成后，日志里出现过一次短暂的
  - `Service destroying`
  - `Service created`
  - `Service connected`
- 这次发生在设置页切换回服务详情页的过程中
- 这不是之前进入抖音后被 `SmartPower` 后台解绑的那种问题

## 5. 今天最重要的真机结论

今天用“手动风险确认后开启”的状态，重新跑了一轮真实抖音链路。

真实进展：

1. 宿主点击 `开始抖音采集`
2. pending local capture 被保存
3. 手动冷启动抖音
4. 自动进入团购专用搜索链路
5. 成功输入并提交 `郑州美莱`
6. 成功命中商家结果并点开商家页

关键 tap trace：

- `D:\project\adb\artifacts` 未单独存新文件，但应用私有文件已更新：
  - `/data/user/0/com.example.a11yframework/files/tap-trace-latest.txt`

其中最关键的点击轨迹是：

- `douyin_groupbuy_tab_node`
- `douyin_groupbuy_inline_keyword_bounds`
- `douyin_dedicated_search_submit_bounds`
- `merchant_result_entry_band_r0`

## 6. 今天没有再复现的旧问题

这轮最重要的排除项是：

- 没有再出现上次那条
  - `SmartPower ... service unbind com.example.a11yframework/.core.FrameworkAccessibilityService`

同时还确认了两件事：

- pending capture 正常启动
- pending capture 最终被正常清理，没有再因为服务临时销毁而提前丢失

这说明：

- “服务在进入抖音后台态时被 HyperOS 拆掉，导致任务中断并丢 pending” 这一条，在今天这轮手动确认后的运行里没有复现

## 7. 今天暴露出来的新 blocker

今天链路并没有真正采到团购数据。

宿主页回看结果：

- `总记录数：0`

日志里已经把问题收敛得比较清楚：

- `PageMatcher: 页面匹配失败：商家详情页`
- `DouyinPlugin: Detected distance-heavy recommendation section, skip target page`
- `A11yFramework: Not a target page, skipping`

这说明当前真正的新 blocker 不是服务稳定性，而是：

- 商家详情页的识别条件太旧
- 当前抖音店铺页里，顶部出现较多距离/推荐/旅行类结构时，`DouyinPlugin` 过早把页面判成“推荐区”，直接跳过 target page
- 同时 `PageMatcher` 里的“商家详情页”规则也没有命中当前页面结构

## 8. 下一步最应该做什么

不要再回头排查“手动风险确认是否有效”了，这条今天已经确认过。

下一步应直接盯住这两个点：

1. `DouyinPlugin.isTargetPage()` / 相关页面判断
   - 为什么当前 `LifePoiActivity` 商家详情页会被判成 `distance-heavy recommendation`
2. `PageMatcher` 的“商家详情页”规则
   - 为什么当前页面结构没有命中

建议下一轮工作顺序：

1. 把当前商家详情页的截图和 XML 再补一对基线样本
2. 先看页面上哪些节点触发了 `distance-heavy recommendation`
3. 放宽“商家详情页成立”的正向锚点
4. 收紧“推荐区”的负向判定，不要在商家详情首屏过早误杀

## 9. 一句话总结

今天最重要的收获不是“全链路已经结束”，而是把问题从“HyperOS 会不会把服务拆掉”收敛成了“服务已经稳定 enough，新的主 blocker 是商家详情页被误判成推荐区，导致 0 条数据落库”。
