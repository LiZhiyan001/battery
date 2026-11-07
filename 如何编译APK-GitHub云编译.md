# 用 GitHub 云编译出 APK —— 图文步骤

> 不需要装 Android Studio。全程在浏览器里点，大约 15 分钟。

---

## 第 0 步：安全提醒（先做这个）

你在对话里发过的账号密码，**请立刻去改密码**。
后面的操作都用「网页登录」，我不需要你的任何密码。

---

## 第 1 步：注册/登录 GitHub

打开 https://github.com

- 有账号 → Sign in
- 没账号 → Sign up（用邮箱注册，免费）

**注意**：GitHub 现在不支持"邮箱+密码"直接登录命令行，
但**网页版登录用密码是可以的**，我们全程用网页。

---

## 第 2 步：新建仓库

1. 右上角 `+` → **New repository**
2. 填写：
   - Repository name: `btbattery`
   - 选 **Public**（私有也能编译，但 Public 更省事）
   - **不要勾** Add a README file
3. 点 **Create repository**

---

## 第 3 步：上传文件（关键步骤）

创建后页面会显示一个空仓库，找到：

```
uploading an existing file
```

点这个链接 → 进入上传页。

### 上传方法

**方法 A：拖 zip 里的内容（推荐）**

1. 先在电脑上把 `BTBatteryLogger.zip` **解压**
2. 解压后有个 `BTBatteryLogger` 文件夹，**打开它**
3. 全选里面的**所有文件和文件夹**（Ctrl+A）
4. 拖到浏览器的上传区域

**⚠️ 注意**：
- 要拖 `BTBatteryLogger` **里面的内容**，不是这个文件夹本身
- `.github` 文件夹是隐藏的，Windows 要先在「查看」里勾上「隐藏的项目」才能看见

**方法 B：分两批传（如果方法 A 卡住）**

先传外层文件（`build.gradle`、`settings.gradle`、`gradle.properties`、`.gitignore`、`README.md`），
再传文件夹。

### 提交

上传完，页面底部填 commit message，随便写比如 `init`，点 **Commit changes**。

---

## 第 4 步：检查 `.github` 有没有传上去

**这一步最容易翻车。**

点仓库首页的 `Actions` 标签：

- **能看到 "Build APK" 这个 workflow** → 成功，跳到第 5 步
- **显示 "There are no workflow runs yet"** 或者提示要启用 → 说明 `.github` 文件夹没传上去

### 如果 `.github` 没传上去

手动创建：

1. 仓库首页 → **Add file** → **Create new file**
2. 文件名输入：`.github/workflows/build.yml`
   （输入 `/` 会自动创建文件夹，很好用）
3. 把下面内容**整个复制**进去：

```yaml
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    env:
      ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
      ANDROID_HOME: /usr/local/lib/android/sdk
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Install SDK components
        run: |
          yes | sdkmanager --licenses > /dev/null 2>&1 || true
          sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

      - name: Create local.properties
        run: echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.2'

      - name: Build Debug APK
        run: gradle assembleDebug --stacktrace --no-daemon

      - name: Find APK
        if: always()
        run: find . -name "*.apk" -type f || echo "no apk found"

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: BTBatteryLogger-debug
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn
          retention-days: 30
```

4. 点 **Commit changes**

---

## 第 5 步：等编译

1. 点 **Actions** 标签
2. 能看到一条 `Build APK` 的记录，黄点 = 正在跑
3. 刷新页面，等它变绿勾（约 3-6 分钟）

**如果变红叉** = 编译失败，点进去看日志，把报错发我。

---

## 第 6 步：下载 APK

编译成功后：

1. 点进那条绿色的 workflow
2. 页面底部找到 **Artifacts**
3. 点 **BTBatteryLogger-debug** 下载（是个 zip）
4. 手机传输或发到自己手机上，解压出 `app-debug.apk`

**手机上装**：
- 点击 APK → 提示"未知来源"，去设置里允许
- 装完打开，授权蓝牙

---

## 常见问题

**Q: 上传时提示文件太多？**
A: 一次最多传 100 个文件，我们只有 22 个，没问题。

**Q: Actions 页面提示 "Workflows aren't being run on this repository"**
A: 需要在 Settings → Actions → General → 选 "Allow all actions"，然后重新 push 一次。

**Q: 编译报错 `SDK location not found`**
A: `local.properties` 那一步没生效。检查 build.yml 里有没有 `Create local.properties` 这一步。

**Q: 编译报错 `Could not find com.android.application`**
A: settings.gradle 里的 `pluginManagement` 配置没传对，重新检查这个文件。

**Q: 装上了但扫不到设备**
A: 先在系统蓝牙里「取消保存」耳机，再打开 App 扫描。

---

## 需要我配合的

遇到任何一步卡住，把**截图**发我。特别是：
- Actions 的报错日志（红色那部分）
- 上传后的文件列表截图（确认文件都齐了）

我根据实际报错改代码或配置。
