# A11y 数据抓取框架

基于 Android 无障碍服务的可扩展数据抓取框架，支持插件化架构。

## 📁 项目结构

```
android-a11y-framework/
├── app/src/main/
│   ├── java/com/example/a11yframework/
│   │   ├── core/                          # 核心框架
│   │   │   ├── IAccessibilityPlugin.kt    # 插件接口（⭐ 重点）
│   │   │   ├── PluginManager.kt           # 插件管理器
│   │   │   ├── FrameworkAccessibilityService.kt  # 无障碍服务
│   │   │   └── ScrapedData.kt             # 数据结构
│   │   ├── data/                          # 数据层
│   │   │   └── DataStore.kt               # SQLite 存储
│   │   ├── config/                        # 配置层
│   │   │   └── ConfigManager.kt           # SharedPreferences 配置
│   │   ├── plugins/                       # 插件实现
│   │   │   ├── MeituanPlugin.kt           # 美团插件示例
│   │   │   └── DouyinPlugin.kt            # 抖音插件示例
│   │   ├── utils/                         # 工具类
│   │   │   └── NodeUtils.kt               # 节点操作工具
│   │   └── MainActivity.kt                # 主界面
│   ├── res/                               # 资源文件
│   └── AndroidManifest.xml                # 清单文件
├── build.gradle.kts                       # 项目构建配置
└── README.md                              # 本文档
```

## 🚀 快速开始

### 1. 用 Android Studio 打开项目

```bash
# 或使用命令行构建
cd android-a11y-framework
./gradlew assembleDebug
```

### 2. 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开启无障碍服务

1. 打开 APP
2. 点击"启动服务"
3. 跳转到系统设置
4. 找到 "A11y 数据抓取服务" 并开启

### 4. 配置插件

在 APP 中配置要抓取的关键词、城市等参数。

### 5. 查看/导出数据

- 主界面查看数据统计
- 点击"导出数据"生成 JSON 文件
- 通过 ADB 拉取：`adb pull /data/data/com.example.a11yframework/files/exported_data.json`

---

## 🔌 插件开发指南

### 创建新插件步骤

#### 1. 创建插件类

在 `plugins/` 目录下新建文件，实现 `IAccessibilityPlugin` 接口：

```kotlin
class YourAppPlugin : IAccessibilityPlugin {
    
    override val pluginId: String = "yourapp"
    override val pluginName: String = "你的 APP"
    override val targetPackages: List<String> = listOf("com.yourapp.package")
    
    override fun initialize(service: AccessibilityService) {
        // 初始化：加载配置、初始化数据库等
    }
    
    override fun cleanup() {
        // 清理资源
    }
    
    override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
        // 判断当前页面是否需要抓取
        // 返回 true 表示是目标页面
    }
    
    override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
        // 抓取数据的核心逻辑
        // 返回 ScrapedData 列表
    }
    
    override fun processData(data: List<ScrapedData>): List<ScrapedData> {
        // 数据清洗、过滤、转换
    }
    
    override fun onActivate() {
        // 切换到目标 APP 时调用
    }
    
    override fun onDeactivate() {
        // 离开目标 APP 时调用
    }
}
```

#### 2. 注册插件

在 `FrameworkAccessibilityService.kt` 的 `registerPlugins()` 方法中添加：

```kotlin
private fun registerPlugins() {
    pluginManager.registerPlugin(MeituanPlugin())
    pluginManager.registerPlugin(DouyinPlugin())
    pluginManager.registerPlugin(YourAppPlugin())  // 添加这行
}
```

#### 3. 调试技巧

使用 `NodeUtils.printNodeTree()` 打印当前页面节点树：

```kotlin
override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
    // 打印节点树（日志中查看）
    NodeUtils.printNodeTree(nodeInfo, maxDepth = 5)
    
    // ... 抓取逻辑
}
```

---

## 📊 数据结构

### ScrapedData

```kotlin
data class ScrapedData(
    val timestamp: Long,              // 时间戳
    val pluginId: String,             // 插件 ID
    val pageType: String,             // 页面类型
    val dataType: String,             // 数据类型
    val content: Map<String, String>, // 实际数据（key-value）
    val rawText: String,              // 原始文本
    val metadata: Map<String, Any>    // 元数据
)
```

### 示例：美团团购数据

```json
{
  "timestamp": 1710057600000,
  "pluginId": "meituan",
  "pageType": "shop_list",
  "dataType": "group_buy",
  "content": {
    "shopName": "某某餐厅",
    "price": "99",
    "groupBuyTitle": "双人套餐",
    "rawText": "某某餐厅 双人套餐 ¥99"
  }
}
```

---

## ⚙️ 配置管理

### 设置插件配置

```kotlin
val configManager = service.getConfigManager()

// 设置字符串
configManager.setPluginConfigString("yourapp", "mode", "list")

// 设置列表
configManager.setPluginConfigMap("yourapp", mapOf(
    "keywords" to listOf("关键词 1", "关键词 2"),
    "city" to "北京"
))
```

### 读取插件配置

```kotlin
val mode = configManager.getPluginConfigString("yourapp", "mode", "default")
val keywords = configManager.getPluginConfigList("yourapp", "keywords")
```

---

## 🛠️ 常用工具

### NodeUtils

```kotlin
// 查找节点
val nodes = NodeUtils.findNodesByText(rootNode, "团购")
val recyclerViews = NodeUtils.findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView")

// 获取文本
val text = NodeUtils.getAllNodeText(node)

// 点击节点
NodeUtils.clickNode(node)

// 滚动
NodeUtils.scrollNode(node, forward = true)

// 打印节点树（调试用）
NodeUtils.printNodeTree(rootNode)
```

---

## ⚠️ 注意事项

1. **HyperOS 3 限制**：小米新系统可能每次重启后需要重新授权无障碍服务
2. **反爬检测**：大厂 APP 可能检测无障碍服务，建议：
   - 操作间隔随机化（3-8 秒）
   - 每天抓取量限制（<100 条）
   - 分时段运行
3. **节点回收**：使用 `AccessibilityNodeInfo` 后务必调用 `recycle()`
4. **权限**：无障碍服务需要用户手动开启，无法自动激活

---

## 📝 开发清单

- [ ] 完善美团插件（适配实际 UI 结构）
- [ ] 完善抖音插件（适配实际 UI 结构）
- [ ] 添加配置界面
- [ ] 添加定时任务功能
- [ ] 添加数据上传功能
- [ ] 添加更多 APP 插件

---

## 📚 参考资料

- [Android 无障碍服务官方文档](https://developer.android.com/guide/topics/ui/accessibility/service)
- [AccessibilityNodeInfo API](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- 本项目 `NodeUtils.kt` 工具类

---

**祝你开发顺利！** 🦞
