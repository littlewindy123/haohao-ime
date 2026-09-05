---
name: 好好输入法
description: 温暖但精致的 Android 输入与个人词本
colors:
  page-light: "#F7F4EC"
  page-dark: "#1D1C19"
  surface-light: "#FFFDF8"
  surface-dark: "#292723"
  honey-light: "#F4BF61"
  honey-dark: "#E0A947"
  on-honey-light: "#513A32"
  on-honey-dark: "#3D2C26"
  selection-light: "#DCECE2"
  selection-dark: "#354D41"
  text-light: "#513A32"
  text-dark: "#F4ECE2"
  secondary-text-light: "#7A6860"
  secondary-text-dark: "#C6B8AA"
  divider-light: "#E3DDD2"
  divider-dark: "#45413A"
spacing:
  control-gap: "8dp"
  content: "16dp"
  reading: "24dp"
---

# Design System: 好好输入法

## Overview

**Creative North Star: "温暖但精致"**

本规范记录 Android 原生好好界面及个人词本已经落地的视觉语言。奶油色底、薄荷色选中态、蜂蜜色行动与可可色文字延续用户确认的金毛品牌；输入和学习内容保持安静、清楚。上游高级设置和用户自定义 Rime 主题不因此被强制替换。

**Key Characteristics:**

- 温暖的低饱和底色，浅深主题分别定义。
- 英文义项、中文释义、来源和状态形成清晰层级。
- 列表以留白和细分隔线组织，金毛只用于欢迎、空态和复习完成。

依据：[产品约束](PRODUCT.md)、[原生设计示意](design-demos/haohao-learning/native-words.html)、[浅色资源](app/src/main/res/values/colors.xml)、[深色资源](app/src/main/res/values-night/colors.xml)。示意中的示例数据不是使用记录；原生代码和设备截图优先于 HTML 示意。

## Colors

蜂蜜色承载主要行动；薄荷色表示选中或品牌区域；页面、内容表面、主次文字与分隔线使用成对的浅深主题资源。顶部品牌薄荷色与列表选中薄荷色使用不同资源，不能互相替代。

**The 主题配对 Rule.** 原生控件引用 `haohao_*` 颜色资源；改动浅色角色时同时检查 `values-night` 对应角色。蜂蜜色按钮使用 `haohao_on_honey`，不能直接复用普通主文字色。

## Typography

使用 Android 系统文字与 `sp` 字号，遵循系统字体缩放。列表中英文义项为粗体（23sp），中文释义为正文（15sp），音标与次数为次级信息（13sp、12sp）。详情、复习中的主词分别为 34sp、38sp；这些是各页面的当前实现尺寸，不是可任意套用的全局展示字号。

普通操作按钮为 15sp，复习反馈按钮为 14sp。长义项可在列表中省略，完整阅读交给详情和可滚动复习页；不能通过缩小正文解决空间问题。候选英文、音标行高随系统字号缩放，候选栏同步预留空间；英文淡入不改变中文位置、候选顺序或点击区域。

## Layout

词本采用原生工具栏、可折叠介绍区、三项筛选、搜索框与单层列表。列表左右留白为 16dp；详情和复习的内容左右留白为 24dp。按钮组使用 8dp 间距。搜索框高 56dp，图标触控区为 48dp，复习按钮最小高度为 48dp。

词本横屏默认收起介绍区，保留筛选和搜索；详情、计划和复习使用 `ScrollView`，并处理系统栏及 IME insets。增大字号和旋转屏幕时，优先保留可滚动内容及触控面积。

## Elevation & Depth

词本头部和复习按钮显式关闭静态阴影，通过背景色、细分隔线与按压波纹表达层次和交互。原生对话框保留平台交互，不把网页手机示意外框的阴影带入应用内容。

## Shapes

筛选按钮使用柔和圆角（10dp），复习操作按钮使用 12dp 圆角，搜索容器使用 14dp 圆角。单词列表保持平面行结构，避免给每一条义项再加嵌套卡片。

## Components

- **单词行**：英文在前，中文、可选 IPA 和状态在后；发音与收藏是独立图标按钮。点击行打开义项详情，图标须有操作说明。
- **筛选与搜索**：「最近遇见」「我的收藏」「正在学习」用选中态区分；搜索支持中文和英文，空结果与尚无数据有各自说明。
- **义项详情**：呈现来源和保存提示；收藏与加入学习为独立操作，首次保存需确认当前义项。
- **复习**：先呈现问题，查看答案后才显示三个反馈；「记住了」使用蜂蜜色主操作。退出与系统返回可用，已完成反馈持久保存。
- **计划**：开启后首先显示今日新词、到期词和剩余数量；设置独立进入，保存不自动开始复习。数字输入框有标签和范围错误提示，开关配色复用好好资源。
- **任务与恢复**：首屏显示实际任务数量或下次时间；共享会话标注模式和方向。复习与完成页提供单步撤销，发音异常提供重试和系统语音设置入口。
- **空态与完成**：金毛图像为装饰，不重复进入无障碍阅读顺序；主文案说明当前状态，辅助文案给出下一步。

实现入口：[词本布局](app/src/main/res/layout/fragment_input_footprints.xml)、[单词行](app/src/main/res/layout/item_input_footprint.xml)、[详情与复习](app/src/main/java/com/osfans/trime/ui/main/footprints/WordLearningActivity.kt)。

## Do's and Don'ts

- **Do** 保留 Android 原生返回、触控反馈、系统字号与明暗主题适配。
- **Do** 用义项内容和用户主动回答解释状态；学习文案保持克制。
- **Don't** 把遇见次数写成记忆程度、把完成一轮写成已经掌握。
- **Don't** 在正常键盘、单词列表或答题内容中反复放置金毛装饰。
- **Don't** 把 HTML 示例、临时失败截图或未验证的显示效果固化为原生设计规则。
