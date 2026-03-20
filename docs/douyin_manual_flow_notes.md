# 抖音手工验证记录

这份文档从现在开始同时承担两件事：

1. `基线 runbook`
   - 记录已经验证成功、可直接复用的方法。
2. `成功资产索引`
   - 每次出现新的稳定方法、点位、页面锚点、截图/XML 资产，都只往这份文档追加，不重新摸索。

日期：2026-03-16

## 当前已确认稳定的 4 步

1. 打开抖音首页
2. 点击顶部 `团购`
3. 点击团购页搜索框，进入搜索输入页
4. 搜索目标店铺后，在结果页点击商家名称区域，进入商家首页

## 已验证可用的页面与素材

- 抖音首页截图：`D:\project\adb\artifacts\douyin_step0_home.png`
- 抖音首页 XML：`D:\project\adb\artifacts\douyin_step0_home.xml`
- 团购页截图：`D:\project\adb\artifacts\douyin_step1_groupbuy.png`
- 团购页截图标注用入口点：`1016,213`
- 搜索输入页截图：`D:\project\adb\artifacts\douyin_step2_search_input.png`
- 搜索输入页 XML：`D:\project\adb\artifacts\douyin_step2_search_input.xml`
- 使用 `ADBKeyboard` 输入 `郑州美莱` 后的截图：`D:\project\adb\artifacts\douyin_chain_input_verify.png`
- 使用 `ADBKeyboard` 输入 `郑州美莱` 后的 XML：`D:\project\adb\artifacts\douyin_chain_input_verify.xml`
- 搜索结果页截图：`D:\project\adb\artifacts\douyin_chain_result_verify.png`
- 搜索结果页 XML：`D:\project\adb\artifacts\douyin_chain_result_verify.xml`
- 搜索结果页层级标注图：`D:\project\adb\artifacts\douyin_chain_result_verify.annotated.v2.png`
- 进入商家首页后的截图：`D:\project\adb\artifacts\douyin_chain_shop_entry.png`
- 进入商家首页后的 XML：`D:\project\adb\artifacts\douyin_chain_shop_entry.xml`
- 商家首页层级标注图：`D:\project\adb\artifacts\douyin_chain_shop_entry.annotated.v2.png`
- 首次进入商家页时可能出现的弹窗示例：`D:\project\adb\artifacts\douyin_step4_shop_home_verify.png`
- 关闭优惠券弹窗后的截图：`D:\project\adb\artifacts\douyin_step4_shop_home_clean.png`

## 已确认的稳定点位

- 首页顶部 `团购`：`1016,213`
- 团购页搜索入口：`383,373`
- 团购 tab 已选中页右上角 `搜索`：`1340,219`

## 团购首页新增分支样式

- 现在已经确认，抖音团购首页至少有 2 种 UI，不要再假设只有红框搜索条一种：
- 旧样式：
  - 页面上方直接出现红框搜索条
  - 左侧城市，如 `郑州`
  - 中间可能出现 `美莱团购`
  - 右侧有 `搜索`
  - 这时继续复用 `383,373`
- 新样式：
  - 顶部 tab 区显示 `已选中，团购，按钮`
  - 顶部右上角出现单独 `搜索`
  - 页面底部仍然有抖音主底栏
  - 这时不要点 `383,373`，直接点右上角 `1340,219`

## 这次新增的验证素材

- 团购 tab 已选中页截图：`D:\project\adb\artifacts\groupbuy_current_c0e68dd.png`
- 团购 tab 已选中页 XML：`D:\project\adb\artifacts\groupbuy_current_c0e68dd.xml`
- 点击右上角搜索后的截图：`D:\project\adb\artifacts\after_search_button_c0e68dd.png`
- 点击右上角搜索后的 XML：`D:\project\adb\artifacts\after_search_button_c0e68dd.xml`
- 这次已验证：从“团购 tab 已选中 + 右上角搜索按钮”页，点 `1340,219` 可以直接进入搜索输入页

## 搜索页注意事项

- 抖音搜索页经常残留历史词，不能假设输入框是空的。
- 在搜狗输入法下，`adb shell input keyevent 67` 清空不稳定。
- 为了验证链路，临时切到 `ADBKeyboard` 更容易复现：
  - `adb shell ime set com.android.adbkeyboard/.AdbIME`
  - 验证结束后切回搜狗：
  - `adb shell ime set com.sohu.inputmethod.sogou.xiaomi/.SogouIME`

## 当前明确问题

- 搜索结果页不能再用“卡片中心点”去点。
- 一旦点到团购卡区域，会直接进入团购详情页，而不是商家首页。
- 必须只点“商家名称区域”或你后续确认的安全区域。
- 结果页里可能会出现多个 `郑州美莱医疗美容医院` 文案。
- 这次已经确认，下面这些不能当店铺入口：
  - 团购卡里的医院署名
  - 商品卡片里的品牌/医院补充文案
  - `继续追问`、`已售`、`人逛过` 这一类商品上下文附近的文案
- 结果页真正该点的是：
  - 上半屏第一条店铺入口带
  - 你确认过的入口带 bounds：`[263,485][1384,548]`
  - 当前安全点击点：`508,516`

## 当前仍需注意的逻辑风险

- 搜索输入框的旧词残留问题还没有从根上解决。
- 目前手工验证里，切到 `ADBKeyboard` 后输入 `郑州美莱` 是稳定的，但真实自动链路仍需要更稳的“清空旧词 -> 写入新词”方案。
- 商家首页优惠券弹窗是概率事件，不能假设每次都弹，也不能假设每次都不弹。
- `uiautomator dump` 在页面刚切换或动画未结束时，偶尔会报 `could not get idle state`，这时要优先保住截图，再补抓 XML。
- 团购列表采集下一阶段要重点处理“展开更多”和“滚动后继续抓取”，否则只能拿到首屏。

## 这次验证里记录到的商家名称节点和安全区域

- 结果页目标商家：`郑州美莱医疗美容医院`
- 商家名称节点 bounds：`[263,485][753,548]`
- 你确认的店铺首页入口带 bounds：`[263,485][1384,548]`
- 这是一整条标题区域，位于统计行 `1867条评价 / ¥1449/人` 的上方。
- 后续优先点击这条入口带，不点下面的统计行，也不点下方团购卡。
- 当前记录的安全点击点：`508,516`

## 进入商家首页后的现象

- 正确落点 Activity：`com.bytedance.locallife.page.poi.LifePoiActivity`
- 进入后有概率先弹出优惠券弹窗
- 需要先关闭弹窗，再做页面结构识别和采集
- 弹窗处理优先看截图，不只看 XML
- 原因是这类视觉弹窗有时不会完整出现在 `uiautomator dump` 里，但截图里能明显看到
- 当前人工验证里可用的关闭点大致在底部中间关闭按钮附近：`719,2437`
- 关闭后建议立刻再抓一张截图，确认已经回到干净的商家首页

## 后续测试原则

- 先验证页面路径，再验证采集逻辑
- 路径确认阶段不点团购卡，不滚动，不做额外动作
- 每次只留一份“当前正确页面”的截图和 XML，避免调试资产混乱
- 截图和 XML 不能并行抓取
- 原因是抖音切页过快，并行时容易出现“截图停在抖音，XML 已经切回宿主页”的混淆现场
- 标注图尽量按“父级 -> 子级 -> 具体点击点”的顺序出图，方便快速人工确认

## 复用规则

- 只要某一步已经被验证过，就优先复用这份文档里的方法、点位和页面锚点。
- 新的测试如果和文档基线冲突，先记录“冲突发生在哪一页、哪一步”，不要直接改老结论。
- 每一轮真机验证结束后，至少补一项：
  - 新的稳定点位
  - 新的页面识别锚点
  - 新的失败轨迹
  - 新的截图/XML 基准资产
