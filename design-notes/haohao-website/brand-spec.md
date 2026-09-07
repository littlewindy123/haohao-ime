---
name: 好好输入法官网
description: 中文输入，英文一起懂。
colors:
  page: "#faf8f2"
  surface: "#fffdf8"
  ink: "#453b32"
  muted: "#706559"
  line: "#dcd7ca"
  honey: "#efbc63"
  on-honey: "#443322"
  mint: "#dce8df"
  honey-soft: "#f1e6cc"
  focus: "#36664f"
  header: "rgb(250 248 242 / 96%)"
  honey-hover: "#e4ad4d"
  dark-page: "#242821"
  dark-surface: "#2b3028"
  dark-ink: "#f1eddf"
  dark-muted: "#bfc4b5"
  dark-line: "#4c5347"
  dark-honey: "#e8b95f"
  dark-mint: "#354b3e"
  dark-honey-soft: "#4c4634"
  dark-focus: "#b3d4ba"
  dark-header: "rgb(36 40 33 / 96%)"
typography:
  display:
    fontFamily: '"PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif'
    fontSize: "clamp(38px, 4.45vw, 72px)"
    fontWeight: 650
    lineHeight: 1.28
    letterSpacing: "-.035em"
  body:
    fontFamily: '"PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif'
    fontSize: "16px"
    lineHeight: 1.75
  english-display:
    fontFamily: 'Georgia, "Times New Roman", serif'
    fontSize: "clamp(48px, 5.4vw, 80px)"
    fontWeight: 500
    lineHeight: 1.1
rounded:
  button: "8px"
  example: "6px"
  demo: "14px"
  key: "5px"
spacing:
  gutter: "clamp(24px, 5.2vw, 112px)"
  gutter-mobile: "24px"
  gutter-narrow: "18px"
---

# 好好输入法官网 · Brand Spec

## Overview

更新日期：2026-09-07。本文件仅记录 `website/` 官网；根目录 `PRODUCT.md` 与 `DESIGN.md` 继续服务 Android App。前置 token 来自当前 `website/styles.css`，不另建 sidecar。

**视觉母题：从一次中文输入，到自己的英语词本。** 用户确认的方向为暖白、蜂蜜金、薄荷绿和真实 Three.js 哑光键盘与词本。正式金毛保持原样，出现在导航、下载收尾和页脚；首屏以立体产品场景为主体。文案为「中文输入，英文一起懂。」，Star 主、下载次，测试版提示可见。

本轮没有独立的已批准效果图；视觉契约是用户同意的代码 3D 实施计划与已确认方向，不宣称完成批准稿像素级还原。

## Colors

蜂蜜金承担主要操作与少量键帽，薄荷绿承担词本、候选选择与社区表面，可可色用于正文，深绿用于重点英文和焦点。网页 token 延续 App 暖色品牌，但不能以旧 App 色值覆盖当前网页值。

深色模式按系统偏好整页替换为前置 `dark-*` 色板；蜂蜜按钮文字色与悬停色沿用浅色定义。章节可使用不同品牌表面，但不在滚动途中交替切换深浅主题。

3D 材质独立于网页语义色：象牙键帽（`#f7f2e5`）、蜂蜜（`#eab65d`）、薄荷词本（`#a1c6b1`）、灰绿底座（`#c7c9bc`）、纸页（`#fdf8e9`）、键帽文字（`#564635`）。它们受灯光与色调映射影响，不用于网页正文。

## Typography

中文与常规界面使用前置系统字体栈，不加载外部字体。英文主视觉使用 Georgia 衬线斜体；3D 键帽由 Canvas 使用 `"Segoe UI", sans-serif` 绘制。

品牌 h1 为 `clamp(26px, 2.6vw, 42px)`、700 字重、1.3 行高；主要文案采用前置 display。章节标题为 `clamp(30px, 3.5vw, 52px)`、600 字重、1.4 行高。`keep.` 与 `again.` 为衬线斜体，字号 `clamp(68px, 9vw, 132px)`、500 字重、1 行高。正文通常 16px，故事段落行高 1.9；辅助说明 10–13px。标题使用 `text-wrap: balance`，正文使用 `text-wrap: pretty`。

## Layout

章节背景横跨视口，内容宽度为视口减双侧流式边距、最大 1680px。桌面首屏为约 `.85fr / 1.3fr` 双栏，场景比例 4:3，适度超出内容列。常规首屏最小高度为 `min(740px, calc(100svh - 138px))`，1800px 以上为 790px。

主章节留白通常 110px，隐私与社区约 90px；候选演示最大 650px，安装说明最大 760px。学习故事交替双栏，真实截图显示宽度最大 275px。

1050px 以下隐藏常规导航链接并调整首屏。760px 以下改为单列，导航高 68px、隐藏导航下载按钮，章节留白约 55–65px，截图宽 250px；键盘画廊横向滚动，每张约占 85%，带吸附。360px 以下边距缩为 18px，并压缩操作间距。

## Elevation & Depth

页面以实色表面与细线分隔组织层级；柔和阴影只用于演示、截图与立体场景。演示阴影为 `0 20px 65px -30px rgb(69 59 50 / 22%)`，截图阴影为 `0 18px 50px -25px rgb(69 59 50 / 35%)`。

首屏使用真实圆角几何体，材质粗糙度 .55、金属度 .06，暖色主光、薄荷轮廓光与低不透明度接触阴影形成哑光质感。无外部模型或纹理请求。

## Shapes

短圆角为主要形态：按钮与候选格 8px、示例 6px、演示面板 14px、网页按键 5px。导航 Logo 圆角 10px，下载 Logo 圆角 14px。故事背景采用完整矩形色面，不反复嵌套卡片；主按钮为短圆角矩形。

## Components

### 行动、演示与动效

主要按钮最小高 54px，导航小按钮 42px，独立 Star 按钮 66px；悬停上移 2px，按下下移 2px。全局键盘焦点为 3px 重点色描边、偏移 5px，提供跳到主要内容入口。Star 打开真实仓库，由用户自行登录后点赞，无 API、虚假计数或自动点赞。

`hero.js` 连接「你好」「学习」「中文」三组示例和收藏示意；只改变本页状态，不保存数据。3D 按键射线拾取与 HTML 按钮并存。按键回弹约 360ms，词本收藏反馈约 650ms；仅轻微鼠标视差，不自动旋转或循环漂浮。

Three.js 0.185.1 与 GSAP Core 3.15.0 固定版本自托管。GSAP 用于首屏顺序入场和章节一次性出现，缺失时保留原生降级。常规 CSS 缓动为 `cubic-bezier(.16, 1, .3, 1)`，操作转场约 180–250ms；不劫持滚动。

透明静态首图先显示，成功绘制后才切换 WebGL；减少动态效果、省流量时不下载 3D，模块失败、WebGL 不可用或上下文丢失时回到静态图。场景在静止、离屏或页面隐藏时停止绘制，离开页面时释放资源；像素比上限 1.5，使用 `low-power`。减少动态效果时禁用 CSS 动画、转场和平滑滚动。

候选演示只支持三组已收录拼音，其他输入显示空态，不虚构翻译；保留音标开关、字母键、清除和退格。按键与示例至少 44px 高，切换示例不主动唤起手机键盘。

### 资产与真实画面

- `website/assets/haohao-golden.png`：既有正式金毛资产，导航、下载、页脚及 favicon 复用，不重画或变色。
- `website/assets/keyboard-still.webp`：由同一程序化场景在 1200×900 首次绘制后导出的透明 WebP，不含网页控件，用于占位和降级。
- `website/assets/words.png` 与 `review.png`：既有真实词本和轻复习截图，用于 `keep.`、`again.`；HTML 标注尺寸 1080×2400，并注明早期测试版、当前细节可能不同。
- `screenshot-light.png`、`screenshot-dark.png`、`screenshot-expanded.png`：既有真实键盘截图，位于折叠画廊，仅通过 CSS 裁切键盘区域，同样保留早期测试版说明。
- `website/assets/og.png`：沿用既有分享卡与 metadata，本轮未重做。
- 版本、ARM64、校验文件及迁移细节收进安装折叠说明；测试版性质保持可见。隐私独立呈现，开源与许可收进页脚。

### 验证记录与边界

主线程本轮记录：35 项单元测试通过；浏览器检查覆盖 320、390、768、1024、1440px 五种宽度与浅深双主题，以及示例、收藏、音标、键盘；另覆盖无 JavaScript、无 WebGL、减少动态效果、省流量、模块加载失败五种降级。独立审查的按键与对比度两项问题修复后取得 `ship`，该复核结论仅覆盖这两项。

主线程构建记录：静态首图 30,610 bytes，3D bundle gzip 135,594 bytes。README 规定首图上限 200 KB、3D 模块 gzip 上限 250 KB，由构建检查约束。本次文档子任务核对源码与 README，未重新量测上述字节数或重复运行测试。

上述记录不代表所有设备、GPU、浏览器及 Android 真机均已验证。离屏暂停会影响完整长截图中的 3D 状态，应分别使用首屏和逐段截图；不声称完成 Lighthouse、批准稿像素比对或分享卡重新验收。

## Do's and Don'ts

- **Do** 保持暖白、蜂蜜金、薄荷绿及正式金毛身份，以立体键盘和词本表达产品。
- **Do** 保持首屏 Star 主、下载次，以及可用的静态降级、键盘操作和安装说明。
- **Do** 分别标注网页示意、早期真实截图与 Android 测试包，保持本地优先和主动学习边界。
- **Don't** 恢复旧大 Logo 首屏、旧色板和胶囊主按钮说明。
- **Don't** 将网页示意宣称为 Android 当前真实界面，或把演示收藏写入持久存储。
- **Don't** 引入虚假评价、下载量、Star 数、未收录翻译或持续无意义动画。
- **Don't** 把官网布局约束扩展到 Android 全局规范，或把局部复核扩写为整站全面认证。
