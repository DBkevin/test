# App Plugin Architecture

> 目标：让手机端 APK 只保留基础能力，把指定 App 的页面路径、规则和滚动策略下沉到插件包。

## 核心原则

1. `FrameworkAccessibilityService` 只负责服务生命周期、事件分发、数据保存。
2. `CaptureCoordinator` 只负责任务编排，不再硬编码抖音/美团页面按钮。
3. `AppPluginManager` 负责安装和加载插件包，运行时插件目录固定为 `filesDir/app_plugins`。
4. `NavigationExecutor` 解释插件清单里的步骤，复用统一的 `SearchController` 能力。
5. `RuleEngine` 继续做页面匹配和数据提取，插件包只负责把规则文件带进来。

## 目录结构

```text
app/src/main/assets/app_plugins/
  douyin/
    plugin.json
    rules/
      douyin_hospital_v1.json
  meituan/
    plugin.json
    rules/
      meituan_hospital_v1.json
```

首次安装时，内置插件会被复制到：

```text
filesDir/app_plugins/<pluginId>/
  plugin.json
  rules/*.json
```

后续如果需要做热更新，只要覆盖这份运行时目录，再触发 `AppPluginManager.reloadPlugins()` 即可，不要求重新编译 APK。

当前已经补上两条真正可用的热更新入口：

1. 远端下发 `update_plugin`
   手机端可以直接接收插件清单与规则 JSON，或下载 `manifest_url` / `rule_urls`。
2. 远端下发 `reload_plugins`
   适合你先用 ADB 或同步盘把 `filesDir/app_plugins` 覆盖好，再让服务立即重载。

## 插件清单字段

```json
{
  "plugin_id": "douyin",
  "plugin_name": "抖音团购插件",
  "version": 1,
  "enabled": true,
  "app_packages": ["com.ss.android.ugc.aweme"],
  "entry_package": "com.ss.android.ugc.aweme",
  "rule_assets": ["douyin_hospital_v1.json"],
  "capture_flow": {
    "app_start_delay_ms": 2500,
    "steps": [
      { "type": "click_text", "target_text": "团购", "wait_ms": 1200 },
      { "type": "search_keyword", "source": "hospital_name" },
      { "type": "click_text_fuzzy", "source": "hospital_name", "max_scroll_rounds": 3 }
    ],
    "collection": {
      "capture_timeout_ms": 60000,
      "capture_round_wait_ms": 2500,
      "scroll_settle_ms": 1800,
      "max_scroll_rounds": 6,
      "max_idle_scroll_rounds": 2
    }
  }
}
```

## 当前落地状态

- 抖音：已经迁到插件导航主链路。
- 美团：已迁插件包和规则安装，导航仍走兼容路径。
- 旧版 Kotlin 插件：保留为 fallback，方便逐步退出，不一次性推翻。
- 运行时热更新：已支持通过远程命令安装/覆盖插件包，后续改 `plugin.json` 或 `rules/*.json` 不必重新发 APK。

## 推荐发布方式

如果你主要在 GitHub 上改插件文件，推荐把插件仓库组织成下面这种结构：

```text
plugins/
  douyin/
    plugin.json
    rules/
      douyin_hospital_v1.json
  meituan/
    plugin.json
    rules/
      meituan_hospital_v1.json
```

然后在远程命令里只给 `manifest_url`：

```json
{
  "type": "update_plugin",
  "data": {
    "plugin": {
      "plugin_id": "douyin",
      "manifest_url": "https://raw.githubusercontent.com/<repo>/main/plugins/douyin/plugin.json"
    }
  }
}
```

手机端会自动推导每个规则文件的默认地址为：

```text
<manifest_url 所在目录>/rules/<rule_file_name>
```

这样以后大多数“按钮文案变化、XPath/规则变化、滚动参数变化”都可以只改 GitHub 上的插件文件，然后下发一条热更新命令。
