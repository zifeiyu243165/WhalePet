# 浏览器版（不用装 App）

把 95 个动作搬到手机浏览器里，做成一个**整屏独立小页面**：加到主屏幕之后，从图标进去就是全屏无地址栏的小应用。

- 95 个动作全部保留，16 个分类（点她 / 日常 / 美食 / 节日 / 游戏 / 梗图 …）
- 触屏手势：**轻点**有反应、**按住拖动**把她拎走、**点空白处**她自己游过去、**长按**呼动作库
- 双视频交叉淡入，切动作不闪白；淡出后停掉旧视频，省电
- 切后台自动暂停、Wi-Fi/4G 才预热、`saveData` 或 2G 不预热
- 可调大小 / 透明度 / 背景 / 自动漫游 / 屏幕常亮
- 素材源四选一：jsDelivr → Fastly → 本地 → 自定义，失败自动降级

## 怎么跑起来

`index.html` 是**生成物**（动作目录内联进去的单文件），仓库里不带，先生成再起服务：

```bash
node scripts/gen-assets.mjs     # 生成 web/index.html
node scripts/serve.mjs          # 默认 8000，会打印手机可用的局域网地址
```

手机连同一个 Wi-Fi，浏览器打开脚本打印的 `http://<局域网 IP>:8000`，
再点 Chrome 右上角菜单 →「添加到主屏幕」，之后从桌面图标进就是全屏无地址栏。

> 素材走 jsDelivr，第一次点某个动作会有 0.5~2 秒加载（取决于网络和该动作体积，单个 400KB~1MB）。

## 三种素材源

### 1. CDN（默认）

`https://cdn.jsdelivr.net/npm/@luweiyabo/dsh-whale-pet@0.1.3/assets/thumb/<分类>/<动作>.webm`，
失败自动切 Fastly、unpkg。

### 2. 本地素材包（离线、秒切、不吃流量）

把 95 个动画压成手机尺寸，放到 `web/assets/` 下，页面会**自动探测**同目录的 `assets/thumb/...`，
探测到就切本地源（设置里手动选过源则以你的选择为准）。

压缩思路：`640×360 / 24fps / 431KB` → `320×180 / 10fps / 84KB`（约 19%），95 个约 8~10MB，
关键是用 `-pix_fmt yuva420p` 保留 alpha。

### 3. 自定义托管

`设置 → 素材源 → 自定`，填一个根地址，例如 `https://your-domain.com/whale`。
只要该地址下面有 `assets/thumb/<分类>/<动作>.webm` 就能用。

## 文件说明

| 文件 | 作用 |
|---|---|
| `index.template.html` | 页面源码，`node scripts/gen-assets.mjs` 把动作目录内联进去 |
| `index.html` | 生成物，可直接部署的单文件（不入库） |
| `catalog.json` | 动作目录：id / 中文名 / 分类，Android 端也用同一份 |
| `manifest.webmanifest` · `icon-*.png` | PWA 图标，支持「添加到主屏幕」 |

## 已知限制

- **只适合 Android / Chromium 内核**。iPhone 的 WebKit 会播放 WebM 但**不合成 alpha 通道**，实际表现是一块灰黑底；要上 iPhone 得再补一套 HEVC-alpha 或 APNG/WebP 素材，属于另一条链路。
- 不与 agent 状态联动。想保留「思考 / 写代码 / 等待」自动切动作，只能用上游那个 DSH Web 插件。
- 素材版权归原作者，见仓库根的 [NOTICE](../NOTICE)。
