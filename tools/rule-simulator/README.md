# A11y 规则模拟器

本地测试规则，无需真机，秒级反馈！

---

## 安装

```bash
cd tools/rule-simulator
npm install
```

---

## 快速开始

### 1. 测试示例规则

```bash
# 使用示例规则和示例页面
npx a11y-sim \
  --rule examples/douyin_hospital.json \
  --page examples/hospital_page.xml
```

### 2. 输出示例

```
🔍 A11y 规则模拟器 v1.0.0

① 加载规则文件...
✓ 规则加载成功
   规则 ID: douyin_hospital_v1
   适用 APP: com.ss.android.ugc.aweme
   页面数：1

② 加载页面快照...
✓ 页面加载成功
   页面标题：N/A
   节点数：15

③ 页面匹配...
✓ 页面匹配成功
   匹配页面：hospital_detail

④ 数据提取...
✓ 数据提取完成

📊 提取结果:

🏥 医院名称:
   北京 XX 医疗美容医院

🏆 荣誉项:
   中国整形美容协会认证单位 | 5A 级机构

📦 团单 (4 个):
   1. 黄金微针体验套餐
      价格：¥999
      销量：已售 1000+
   2. 水光针补水保湿套餐
      价格：¥1999
      销量：已售 500+
   3. 热玛吉面部提升疗程
      价格：¥3999
      销量：已售 200+
   4. 光子嫩肤美白次卡
      价格：¥2999
      销量：已售 800+

✅ 验证通过!
```

---

## 工作流

### 开发新规则

```bash
# 1. 从真机导出页面（只需一次）
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml ./pages/hospital_detail.xml

# 2. 编写规则
cat > rules/douyin_hospital.json << 'EOF'
{
  "rule_id": "douyin_hospital_v1",
  "app_package": "com.ss.android.ugc.aweme",
  "pages": [{
    "page_id": "hospital_detail",
    "match_rules": [...],
    "extract_rules": {...}
  }]
}
EOF

# 3. 本地测试（秒级反馈）
npx a11y-sim -r rules/douyin_hospital.json -p pages/hospital_detail.xml

# 4. 调整规则（重复步骤 3，秒级反馈）

# 5. 验证通过后推送到云端
curl -X POST http://server/api/rules -d @rules/douyin_hospital.json
```

---

## 命令行选项

```
Usage: a11y-sim [options]

Options:
  -V, --version      输出版本号
  -r, --rule <path>  规则文件路径 (JSON) [必需]
  -p, --page <path>  页面快照文件路径 (XML) [必需]
  -v, --verbose      详细输出模式
  -o, --output <path> 输出结果文件路径
  -h, --help         显示帮助信息
```

---

## 规则格式

参考：`examples/douyin_hospital.json`

```json
{
  "rule_id": "douyin_hospital_v1",
  "app_package": "com.ss.android.ugc.aweme",
  "pages": [
    {
      "page_id": "hospital_detail",
      "match_rules": [
        {
          "type": "text_contains",
          "values": ["医院", "门诊", "整形"]
        }
      ],
      "extract_rules": {
        "hospital_name": {
          "type": "find_by_keywords",
          "keywords": ["医院", "门诊"]
        }
      }
    }
  ]
}
```

---

## 页面快照格式

参考：`examples/hospital_page.xml`

标准 Android `uiautomator dump` 输出格式。

---

## 开发调试

```bash
# 详细输出模式
npx a11y-sim -r rules/test.json -p pages/test.xml -v

# 保存结果到文件
npx a11y-sim -r rules/test.json -p pages/test.xml -o result.json

# 查看结果
cat result.json | jq .
```

---

## 集成到 CI

```yaml
# .github/workflows/test-rules.yml
name: Test Rules

on:
  push:
    paths:
      - 'rules/**/*.json'

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Install simulator
        run: |
          cd tools/rule-simulator
          npm install
      
      - name: Test rules
        run: |
          for rule in rules/*.json; do
            npx a11y-sim -r $rule -p pages/sample.xml
          done
```

---

## 下一步

- [ ] 支持更复杂的匹配规则（AND/OR/NOT）
- [ ] 支持正则表达式提取
- [ ] 支持 XPath 选择器
- [ ] Web 界面调试工具

---

*最后更新：2026-03-10*
