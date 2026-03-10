# 调试指南 - 如何适配新 APP

本指南教你如何分析目标 APP 的 UI 结构，并编写对应的抓取逻辑。

## 🔍 第一步：开启日志

在 Android Studio 的 Logcat 中过滤以下标签：

```
A11yFramework
PluginManager
MeituanPlugin
DouyinPlugin
NodeUtils
```

## 📱 第二步：分析目标页面

### 方法 A：使用节点树打印

在插件的 `scrapeData()` 方法开头添加：

```kotlin
override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
    // 打印节点树
    NodeUtils.printNodeTree(nodeInfo, maxDepth = 8)
    
    // ... 其他逻辑
}
```

然后打开目标 APP，查看 Logcat 输出：

```
=== Node Tree ===
[android.widget.TextView] text="某某餐厅" id="" clickable=false scrollable=false
  [android.widget.TextView] text="双人套餐 ¥99" id="" clickable=false scrollable=false
[androidx.recyclerview.widget.RecyclerView] text="" id="recycler_view" clickable=false scrollable=true
  [android.widget.LinearLayout] text="商家 1" id="" clickable=true scrollable=false
    [android.widget.TextView] text="团购优惠" id="" clickable=false scrollable=false
=== End Tree ===
```

### 方法 B：使用开发者选项

1. 手机开启「开发者选项」
2. 开启「显示布局边界」
3. 观察目标 APP 的控件结构

### 方法 C：使用辅助工具

推荐工具：
- **Accessibility Inspector**（Android Studio 内置）
- **uiautomatorviewer**（SDK 自带）
- **Scene**（需 root）

---

## 🎯 第三步：识别关键节点

### 常见特征

| 元素类型 | 特征 | 查找方法 |
|----------|------|----------|
| 商家卡片 | 包含店名、价格、地址 | `findParentCard()` |
| 团购标题 | 包含"团购"、"套餐"等词 | `findNodesByText("团购")` |
| 价格 | 包含"¥"符号 | 正则提取 |
| 列表容器 | RecyclerView/ListView | `findNodesByClassName()` |

### 美团示例

```kotlin
// 1. 查找包含"团购"的节点
val keywordNodes = rootNode.findAccessibilityNodeInfosByText("团购")

// 2. 向上查找父节点（商家卡片）
keywordNodes.forEach { node ->
    var parent = node.parent
    while (parent != null && depth < 5) {
        if (isShopCardNode(parent)) {
            // 找到商家卡片
            extractShopData(parent)
            break
        }
        parent = parent.parent
    }
}

// 3. 判断是否是商家卡片
fun isShopCardNode(node: AccessibilityNodeInfo): Boolean {
    val text = getNodeText(node)
    return text.length > 20 && text.contains("¥")
}
```

### 抖音示例

```kotlin
// 抖音通常是 Feed 流，查找 RecyclerView
val recyclerViews = NodeUtils.findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView")

recyclerViews.forEach { recyclerView ->
    for (i in 0 until recyclerView.childCount) {
        val item = recyclerView.getChild(i)
        if (item != null && isGroupBuyItem(item)) {
            extractData(item)
        }
    }
}
```

---

## 🔧 第四步：编写提取逻辑

### 文本提取

```kotlin
// 获取节点文本
fun getNodeText(node: AccessibilityNodeInfo): String {
    val sb = StringBuilder()
    
    // 当前节点
    node.text?.let { sb.append(it.toString()).append(" ") }
    
    // 子节点
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
            child.text?.let { sb.append(it.toString()).append(" ") }
            child.recycle()
        }
    }
    
    return sb.toString().trim()
}
```

### 正则解析

```kotlin
// 提取价格
val price = Regex("¥(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1) ?: ""

// 提取销量
val sales = Regex("(\\d+(?:\\.\\d+)?[kKwW]?) 已售").find(text)?.groupValues?.get(1) ?: ""

// 提取店名（第一行）
val shopName = text.split("\n").firstOrNull()?.trim() ?: ""
```

---

## 🧪 第五步：测试与调试

### 测试流程

1. **安装 APP**：`adb install app-debug.apk`
2. **开启服务**：在无障碍设置中启用
3. **打开目标 APP**：美团/抖音
4. **查看日志**：Logcat 中查看抓取结果
5. **检查数据**：主界面查看统计，或导出数据

### 常见问题

#### Q1: 服务开启了但没抓取到数据

**可能原因：**
- `isTargetPage()` 返回 false
- 页面特征识别错误

**解决方法：**
```kotlin
override fun isTargetPage(nodeInfo: AccessibilityNodeInfo?): Boolean {
    val text = getNodeText(nodeInfo).lowercase()
    Log.d(TAG, "Page text: $text")  // 打印页面内容
    
    // 放宽条件
    return text.contains("团购") || text.contains("优惠")
}
```

#### Q2: 抓取到的数据为空

**可能原因：**
- 节点查找方式不对
- 页面还没加载完成

**解决方法：**
```kotlin
override fun scrapeData(nodeInfo: AccessibilityNodeInfo?): List<ScrapedData> {
    // 打印节点树
    NodeUtils.printNodeTree(nodeInfo, maxDepth = 5)
    
    // 尝试多种查找方式
    val nodes1 = nodeInfo?.findAccessibilityNodeInfosByText("团购") ?: emptyList()
    val nodes2 = NodeUtils.findNodesByClassName(nodeInfo, "android.widget.TextView")
    
    Log.d(TAG, "Found ${nodes1.size} nodes by text, ${nodes2.size} by class")
    
    // ...
}
```

#### Q3: APP 检测到无障碍服务

**可能现象：**
- APP 闪退
- 弹出警告
- 数据不显示

**解决方法：**
1. 降低抓取频率（增加 `SCRAPE_COOLDOWN`）
2. 随机化操作间隔
3. 分时段运行
4. 考虑使用其他方案（如 ADB+ 图像识别）

---

## 📋 适配检查清单

适配新 APP 时，按顺序检查：

- [ ] 确定目标 APP 包名
- [ ] 开启日志，打印节点树
- [ ] 识别目标页面特征（`isTargetPage`）
- [ ] 找到数据所在的节点结构
- [ ] 编写提取逻辑（`scrapeData`）
- [ ] 数据清洗（`processData`）
- [ ] 测试多种页面（列表页、详情页、搜索页）
- [ ] 添加配置项（关键词、城市等）
- [ ] 注册插件

---

## 💡 技巧总结

1. **先打印，再编码**：不要猜节点结构，打印出来看
2. **由宽到严**：先放宽条件抓取，再逐步精确
3. **多重备份**：用多种方法查找节点（text、className、id）
4. **及时回收**：`AccessibilityNodeInfo` 用完必须 `recycle()`
5. **异常处理**：包裹 try-catch，避免服务崩溃

---

**有问题随时问！** 🦞
