# 浏览器版（不用装 App）

把 95 个动作搬到手机浏览器里，做成一个**整屏独立小页面**：加到主屏幕之后，从图标进去就是全屏无地址栏的小应用。

- 95 个动作全部保留，16 个分类（点她 / 日常 / 美食 / 节日 / 游戏 / 梗图 …）
- 触屏手势：**轻点**有反应、**按住拖动**把她拎走、**点空白处**她自己游过去、**长按**呼动作库
- 双视频交叉淡入，切动作不闪白；淡出后停掉旧视频，省电
- 切后台自动暂停、Wi-Fi/4G 才预热、`saveData` 或 2G 不预热
- 可调大小 / 透明度 / 背景 / 自动漫游 / 屏幕常亮
- 素材源四选一：jsDelivr → Fastly → 本地 → 自定义，失败自动降级

## 怎么跑起来

### 线上（已部署）

**<https://zifeiyu243165.github.io/WhalePet/>**

手机浏览器直接打开就能用，点 Chrome 右上角菜单 →「添加到主屏幕」，
之后从桌面图标进就是全屏无地址栏的小应用。

线上由 GitHub Actions 自动部署：`master` 一推送就重新构建发布
（工作流 `.github/workflows/pages.yml`）。站点内容 = 本目录 + `android/assets/media/thumb/`
的 95 个透明 WebM，**不依赖 jsDelivr**，素材同源自取。

### 本地

`index.html` 是**生成物**（动作目录内联进去的单文件），仓库里不带，先生成再起服务：

```bash
node scripts/gen-assets.mjs     # 生成 web/index.html
node scripts/serve.mjs          # 默认 8000，会打印手机可用的局域网地址
```

手机连同一个 Wi-Fi，浏览器打开脚本打印的 `http://<局域网 IP>:8000`，
再点 Chrome 右上角菜单 →「添加到主屏幕」，之后从桌面图标进就是全屏无地址栏。

> 本地起的服务不带素材包，素材走 jsDelivr，第一次点某个动作会有 0.5~2 秒加载
> （取决于网络和该动作体积，单个 400KB~1MB）。线上 Pages 版自带素材包，没这个等待。

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

## 部署

GitHub Pages，工作流在 `../.github/workflows/pages.yml`：

```
push master → 生成 web/index.html → 拼站点目录 → upload-pages-artifact → deploy-pages
```

站点目录 = `web/` 里的 4 个文件 + `android/assets/media/thumb/` 的 95 个 WebM
（复制到站点根的 `assets/thumb/`），另加一个 `.nojekyll`。

两点注意：

- `web/index.html` 是生成物、本就不入库，CI 里现场生成；脚本只用 Node 内置模块，**不需要 npm install**
- Pages 站点跑在子路径 `/WhalePet/` 下，页面里所有资源引用都是相对路径（`./manifest.webmanifest`、
  `./assets/thumb/...`），换域名或换子路径都不用改代码

手动触发一次部署：Actions 页面 → `Deploy web to GitHub Pages` → `Run workflow`。

## 已知限制

- **只适合 Android / Chromium 内核**。iPhone 的 WebKit 会播放 WebM 但**不合成 alpha 通道**，实际表现是一块灰黑底；要上 iPhone 得再补一套 HEVC-alpha 或 APNG/WebP 素材，属于另一条链路。
- 不与 agent 状态联动。想保留「思考 / 写代码 / 等待」自动切动作，只能用上游那个 DSH Web 插件。
- 素材版权归原作者，见仓库根的 [NOTICE](../NOTICE)。
