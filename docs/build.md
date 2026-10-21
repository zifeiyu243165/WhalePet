# 构建环境

本项目**不用 Gradle**，直接调 Android SDK 的命令行工具出包。好处是环境干净可复现，
代价是要自己准备 JDK 与 SDK。

## 需要的东西

| 组件 | 版本 | 从哪里拿 |
|---|---|---|
| JDK | 17（Temurin / Microsoft 均可） | [adoptium.net](https://adoptium.net/temurin/releases/?version=17) |
| Android SDK Build-Tools | 34.0.0 | Android Studio 的 SDK Manager，或 `cmdline-tools` 的 `sdkmanager "build-tools;34.0.0"` |
| Android SDK Platform | 34 | 同上，`sdkmanager "platforms;android-34"` |
| Node.js | ≥ 18 | 生成页面、跑测试 |
| Python | 3.x | 把 `classes.dex` 追加进 APK |

`build-tools` 里实际用到的是 `aapt2`、`zipalign`、`lib/d8.jar`、`lib/apksigner.jar`；
`platforms;android-34` 里只用到 `android.jar`。

## 目录摆放

脚本默认去仓库根的 `.toolchain/` 找（该目录已被 `.gitignore` 排除）：

```
.toolchain/
├── jdk-17.0.2/        # JAVA_HOME 指向它，目录名随意，改脚本或建软链即可
├── android-14/        # build-tools 34.0.0 的目录（脚本里叫 BT）
└── android-34/        # platform-34 的目录，含 android.jar
```

也可以不改目录名，直接用环境变量指过去：

```bash
PET_TOOLCHAIN=/d/Android/sdk bash scripts/build-apk.sh   # 该目录下按上面三个子目录组织
```

脚本里几个可覆盖的变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PET_TOOLCHAIN` | `$ROOT/.toolchain` | 工具链根目录 |
| `PET_KEYSTORE` | `$ROOT/keystore/whale.jks` | 签名密钥，缺失时自动生成自签 |
| `PET_KS_PASS` | `whale123456` | 密钥口令（自签默认值，正式发布请换掉） |
| `PYTHON` / `NODE` | 自动探测 | 指定具体的解释器 |

## 一条命令出包

```bash
bash scripts/build-apk.sh
```

流程共 8 步：

1. `node scripts/gen-assets.mjs` —— 把 `web/catalog.json` 内联进 `android/assets/pet.html`
2. 清 `build/`
3. `aapt2 compile --dir android/res -o build/res.zip`
4. `aapt2 link` —— 生成 `R.java`，把 `android/assets` 一起打进 `base.apk`；
   `.webm` 用 `-0` 不压缩（透明视频需要能随机读，压了反而更大更慢）
5. `javac -source 8 -target 8` 编译 `android/src` 与生成的 `R.java`
6. `d8 --release --min-api 26` 转 `classes.dex`
7. `zipfile` 追加 `classes.dex` 到 APK 根部，再 `zipalign -p 4`
8. `apksigner` 以 v2+v3 方案签名，输出 `dist/WhalePet-v<versionName>.apk`

## 改版本号

改 `android/AndroidManifest.xml`：

```xml
android:versionCode="10"      <!-- 每次发版必须 +1，否则装不上 -->
android:versionName="1.9"
```

包名 `com.whale.pet` 一旦发布就不要改，改了等于另一个 App。

## 常见问题

**`!! javac 失败` 但日志是乱码**
脚本用 `iconv -f GBK` 转码输出。如果机器 locale 不同导致仍是乱码，直接看 `build/javac.log`。

**`App not installed as package conflicts with an existing package`**
签名不一致。之前装的是别的 key 签的包，得先卸载——注意卸载会清掉设置与已导入的动画。

**装完桌面上什么都不出现**
先看 App 里「显示在其他应用上层」是否已授予；再确认「桌面常驻」开关是开的；
国产 ROM 还要在自启动 / 后台白名单里放行，否则服务起来几秒就被清掉。

**角色外面有一块底色**
先跑 `node scripts/check-alpha.mjs` 生成三色底截图：

- 底色**跟着** stage 变色 → 是页面自己的元素在绘制（历史上是收起的面板阴影），见 [troubleshooting.md](troubleshooting.md)
- 底色**固定**为黑/灰且不跟 stage 变 → 上屏的图层被当成不透明纹理，检查是否又走了 canvas 中转

**`aapt2 link` 报找不到资源**
先确认 `android/res` 里的 `@mipmap/ic_launcher`、`@style/AppTheme`、`@color/*` 都在；
换过 SDK 版本时也检查 `--min-sdk-version` / `--target-sdk-version` 是否与 `uses-sdk` 一致。
