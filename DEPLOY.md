# 云端编译部署指南

使用 GitHub Actions 自动编译 APK，无需本地 Android 环境。

## 🚀 快速开始

### 第一步：创建 GitHub 仓库

1. 登录 GitHub
2. 创建新仓库（私有或公开）
   - 仓库名：`android-a11y-framework`
   - 不要初始化 README/.gitignore

### 第二步：推送代码

```bash
cd /home/le/.openclaw/workspace/android-a11y-framework

# 初始化 git
git init

# 添加所有文件
git add .

# 提交
git commit -m "Initial commit: A11y framework"

# 添加远程仓库（替换为你的仓库地址）
git remote add origin https://github.com/YOUR_USERNAME/android-a11y-framework.git

# 推送
git branch -M main
git push -u origin main
```

### 第三步：等待编译

推送后 GitHub Actions 会自动开始编译：

1. 进入仓库页面
2. 点击 **"Actions"** 标签
3. 看到 "Build APK" 工作流正在运行
4. 等待 3-5 分钟

### 第四步：下载 APK

编译成功后：

1. 在 Actions 页面点击最近的运行记录
2. 滚动到底部 **"Artifacts"** 区域
3. 点击 **`app-debug`** 下载 APK
4. 解压后得到 `app-debug.apk`

---

## 📱 安装到手机

```bash
# 通过 ADB 安装
adb install app-debug.apk

# 或者传到手机上直接安装
```

---

## 🔧 手动触发编译

如果想重新编译（比如修改了代码）：

1. 进入仓库 → Actions → Build APK
2. 点击 **"Run workflow"** 按钮
3. 选择分支 → 点击 **"Run workflow"**

---

## 📦 发布版本（可选）

如果想打正式版本：

```bash
# 打标签
git tag v1.0.0
git push origin v1.0.0
```

Actions 会自动创建 GitHub Release 并上传 APK。

---

## ⚙️ 工作流说明

`.github/workflows/build-apk.yml` 配置了：

- **触发条件**：
  - push 到 main/master 分支
  - 手动触发（workflow_dispatch）
  
- **编译环境**：
  - Ubuntu latest
  - JDK 17
  - Gradle 缓存加速

- **输出**：
  - Debug APK（保留 30 天）
  - Release APK（打 tag 时）

---

## 💡 常见问题

### Q: 编译失败怎么办？

查看 Actions 日志：
1. Actions → 点击失败的运行
2. 展开 "Build with Gradle" 步骤
3. 查看错误信息

常见错误：
- **SDK 版本不对**：检查 `build.gradle.kts` 中的 `compileSdk`
- **依赖找不到**：检查网络连接，Gradle 会自动下载

### Q: 编译太慢？

第一次编译需要下载依赖，后续会使用缓存，速度会快很多。

### Q: 想编译 Release 版本？

修改 `.github/workflows/build-apk.yml`：
```yaml
- name: Build Release APK
  run: ./gradlew assembleRelease
```

需要配置签名密钥（先别管，Debug 版够用了）。

---

## 🔗 下一步

1. 推送到 GitHub
2. 等编译完成
3. 下载 APK 安装到手机
4. 开启无障碍服务
5. 开始调试

**有问题随时问！** 🦞
