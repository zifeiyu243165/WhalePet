# 手机桌宠背景与长按菜单排查记录

适用项目：whale-app。目标设备：OPPO Reno6 Pro+ / ColorOS 13.1。
结论来自源码审查和 Windows Edge 自动化隔离实验；未连接目标手机，不能声称完成 ColorOS 实机验收。

## 1. 背景矩形的已复现来源

动作面板 #sheet 收起时只做 translateY(112%)，仍然参与绘制。
面板带有向上偏移 18px、模糊半径 46px、透明度 0.6 的 box-shadow。
在 96×113 CSS px 的桌宠小窗口中，面板约 66px 高，阴影即使移出视口仍向上漏进窗口。
因此灰色区域以窗口边界裁切，集中在角色下方，与截图表现一致。

隔离证据：完全隐藏 #pet 和 #dec，旧版截图仍存在半透明底。
旧版隔离截图 alpha 范围 0–97（最高约 38% 不透明）；仅隐藏收起的面板，alpha 范围变为 0–0。
修复后在宽度 96、150、190 CSS px、DPR=3 三种尺寸下，隔离截图 alpha 均为 0–0。
证据文件在 .tmp/shadow-before.png、shadow-after.png 和 shadow-fixed-*.png。

修复：面板默认 visibility:hidden、pointer-events:none；打开时可见并接收触摸；关闭动画结束后隐藏。
保留面板阴影用于全屏菜单，不再污染桌宠小窗口。

## 2. 逐链路排查

| 链路 | 检查结果 | 判断 |
|---|---|---|
| 素材文件→解码帧 | 当前待机视频通过 canvas 64×36 采样，2304 个像素中 1896 个 alpha<60，最低 alpha=1 | 测试环境中存在有效透明通道；少量透明残值不等于完整不透明底 |
| 视频直出 / canvas | 默认 native 走视频直出；auto 才执行 alpha 检测 | 之前对默认档位、Canvas、软件层的调整不能消除独立 UI 阴影；本次保留档位避免引入抠图损伤 |
| HTML/body/stage | 透明背景 | 无需继续反复增加透明背景声明 |
| 页面其他 UI | #sheet 收起后阴影仍绘制，隔离测试可复现 | 本次确认的问题来源 |
| Android 根容器/WebView | 均设置 Color.TRANSPARENT | 源码已配置；目标手机最终合成仍需实测 |
| WindowManager | PixelFormat.TRANSLUCENT，悬浮窗硬件加速 | 源码满足透明窗口配置；没有证据把根因定为 ColorOS |
| 导入动作 | 独立素材来源、独立包围盒计算 | 本次待机素材验证不代表所有导入视频都含 alpha |

Android 的背景透明与图层设置属于不同环节，不能通过重复调用 setBackgroundColor 修正页面其他元素的阴影。
官方参考：https://developer.android.com/reference/android/webkit/WebView
硬件图层说明：https://developer.android.com/topic/performance/hardware-accel

## 3. 长按跳变与消失

旧流程把 lp.x/y 清零、把小窗口改为全屏；页面角色仍依赖 left:50%、bottom 定位，坐标系立刻改变。
随后隐藏整个原生根容器，固定延迟 64ms 再显示；body.menu 又把角色和解码元素 visibility:hidden。
所以角色消失是主动隐藏，固定延迟也无法保证和 WebView 重排帧同步。
关闭时再用 1200ms 兜底恢复，增加了时序竞争。

新流程先把原窗口坐标按 density 转为 CSS 坐标，通过 JS 固定角色位置；等待 evaluateJavascript 回调后扩展窗口。
面板通过双 requestAnimationFrame 启动滑入；角色不再被隐藏、不再抬高跳位。
面板使用 320ms 减速曲线，收起完成后再恢复小窗口；收起期间原生仍保持菜单状态，阻止拖动、漫游和重复打开。
打开菜单会取消进行中的漫游及待启动回调。
长按后继续发给子视图的事件改为 CANCEL，防止松手又触发一次轻点动作。

## 4. 验证与产物

- scripts/test-shadow-menu.mjs：检查三种窗口尺寸、素材 alpha、扩窗角色坐标保持、关闭恢复。
- 自动化中模拟角色位于 (210,320)，窗口扩为 360×780 后仍保持 (210,320)，角色可见。
- Java 编译、DEX 构建、APK 签名校验通过。
- 安装包：dist/WhalePet-shadow-fix.apk，版本 1.7 / versionCode 8，沿用项目签名。

目标手机还需确认：覆盖安装后灰块是否消失；靠近四角长按/收起是否跳位；漫游中长按、快速重复操作、选择动作后能否正常回到桌面。
本轮没有真实设备 ADB 数据，不能据此保证目标 ROM 所有视频解码路径均正确。如果仍有底色，应导出当前渲染诊断及 WebView 版本，继续区分素材 alpha 残值、解码与系统合成，而不是再次盲目切换渲染方案。
