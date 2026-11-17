# CF 节点测速 APK —— 构建说明

不用装 Android Studio。把这份代码传到 GitHub，它的 Actions 会自动编译出 APK，你在手机上直接下载安装。

---

## 一、它能做什么

在手机上独立完成整套流程，不依赖电脑、不依赖 Termux：

1. 拉取你填的订阅链接（支持 base64 订阅体、明文链接列表）
2. 用内置的 Xray 内核（已编译进 APK）逐个节点建立真实隧道
3. 测量每个节点的首字节时间，再用 1 MB 文件验证下载速度
4. 筛掉不达标的，按速度排序
5. 自动上传到你的 Nextcloud，并给出免认证的订阅地址

界面只有三个按钮：`开始测速`、`设置`、`清空`。

---

## 二、关键设计说明

Android 10 起，普通应用**不能执行自己私有目录里的文件**（SELinux W^X 策略）。所以内核不能像电脑上那样直接 `./xray`。

这里的做法是把 `xray` 二进制改名成 `libxray.so` 放进 `jniLibs/arm64-v8a/`，并在 `AndroidManifest.xml` 里声明 `android:extractNativeLibs="true"`。系统安装时会把它释放到 `nativeLibraryDir`——那个目录**只读且可执行**，正好符合限制。

启动方式是用绝对路径直接 exec 那个 `.so` 文件。

---

## 三、传到 GitHub 并构建

### 1. 建仓库

打开 https://github.com/new

- Repository name：`cfbench`
- 选 **Public**（Private 也行，但 Public 的 Actions 不限时长）
- 不用勾 README
- 点 Create repository

### 2. 上传代码

**方式 A：网页拖拽**

1. 把 `cfbench-apk.zip` 解压到本地任意目录
2. 进入那个目录，**全选所有文件和文件夹**（注意要包含隐藏的 `.github` 文件夹）
3. 在刚建好的仓库页面点 `Add file` → `Upload files`
4. 把选中的内容拖进浏览器窗口
5. 点 `Commit changes`

**方式 B：如果 `.github` 没上传成功**

GitHub 网页对隐藏文件夹有时会漏。确认一下仓库根目录有没有 `.github/workflows/build.yml`。没有就手动建：

1. 仓库页点 `Add file` → `Create new file`
2. 文件名输入框里直接打：`.github/workflows/build.yml`
   （输入 `/` 会自动变成目录层级）
3. 把本地 `build.yml` 的内容粘进去
4. Commit

### 3. 等构建

上传成功后，仓库顶部会多一个 **Actions** 标签。

1. 点进去，能看到名为 `Build APK` 的运行记录
2. 状态是黄点表示正在跑，通常 **4~8 分钟**
3. 变绿勾就是成功

### 4. 下载 APK

1. 点进那次成功的运行
2. 页面底部 **Artifacts** 区域，点 `cfbench-apk` 下载
3. 得到一个 zip，解开里面是 `app-debug.apk`

---

## 四、安装与首次使用

### 装

把 `app-debug.apk` 传到手机，点击安装。会提示"未知来源"，允许即可。

**支持范围**：Android 8.0 及以上，**仅 arm64**（2017 年后的手机基本都是，包括所有骁龙 8 系、天玑、麒麟）。

### 首次配置

打开 App，点右上角的 **设置**：

| 项目 | 填什么 |
|---|---|
| 订阅地址 | 一行一个，你现有那些订阅链接 |
| WebDAV 根地址 | 已预填你的 Nextcloud 地址，一般不用改 |
| WebDAV 用户名 | 你的邮箱 |
| **WebDAV 应用密码** | **必须填**，否则不上传 |
| 云端子目录 | `sub-phone`（手机测的结果放这里） |
| 保留几个最快节点 | 默认 10 |

填完保存。

### 跑

点 **开始测速**，然后等。日志会实时滚动。

跑完最后会打印一行 `Karing 订阅地址:`，把那个地址填进手机 Karing 就行。

---

## 五、必须注意的三件事

**1. 测速前先把 Karing 彻底关掉**

不是暂停，是在后台任务里划掉。Karing 开着会建立 VPN 隧道接管全部流量，这个 App 的测速请求也会被它代理，测出来是"节点套节点"，结果全错。

**2. 用 Wi-Fi，别用流量**

一轮大约 30~50 MB。

**3. 结果只对当前网络有效**

手机走联通、电脑走移动，最优节点通常不是同一批。所以这个 APK 的云端目录默认是 `sub-phone`，不会覆盖电脑那份。

---

## 六、改东西

- **换测速端点**：`BenchRunner.kt` 里的 `probeUrl` 和 `dlUrl`
- **改默认参数**：`MainActivity.kt` 里 `readConfig()` 的默认值
- **改包名**：`app/build.gradle.kts` 的 `namespace` 和 `applicationId`，以及 Kotlin 文件的 `package` 行

改完重新 commit，Actions 会自动重跑。

---

## 七、常见问题

**Actions 报 `gradle: command not found`**
→ 检查 `build.yml` 里 `gradle/actions/setup-gradle@v3` 那步有没有写对

**Actions 报 `SDK location not found`**
→ 检查 `android-actions/setup-android@v3` 那步存在

**构建成功但装上闪退**
→ 大概率是 `libxray.so` 没被打包进去。检查 `app/src/main/jniLibs/arm64-v8a/libxray.so` 是否存在，以及 `manifest` 里 `extractNativeLibs="true"` 有没有写

**界面显示"内核缺失"**
→ 同上。另外确认手机是 arm64 架构（`adb shell getprop ro.product.cpu.abi` 应为 `arm64-v8a`）

**日志里所有节点都显示 `--`**
→ Karing 还开着，先关掉再跑
