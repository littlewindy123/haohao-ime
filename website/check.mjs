import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { signerForRelease } from "./verify-apk.mjs";

const root = path.dirname(fileURLToPath(import.meta.url));
const [html, css, script, model, releaseText] = await Promise.all(
  ["index.html", "styles.css", "main.js", "demo-model.mjs", "release.json"].map((name) => readFile(path.join(root, name), "utf8")),
);
const release = JSON.parse(releaseText);
const signingPolicy = await readFile(path.join(root, "..", "public-signing.properties"), "utf8");
assert.equal(release.signerSha256, signerForRelease(signingPolicy, release.versionCode), "公开包签名必须与该版本的固定发布身份一致");
assert.equal(release.applicationId, signingPolicy.match(/^applicationId=(\S+)\s*$/m)?.[1], "公开包包名不能随发布改变");
const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
assert.equal(ids.length, new Set(ids).size, "页面 ID 不能重复");
for (const id of ["main", "top", "demo", "features", "showcase", "privacy", "community", "download", "opensource", "install"]) {
  assert.ok(ids.includes(id), "缺少页面锚点 #" + id);
}
for (const [, id] of html.matchAll(/href="#([^"]+)"/g)) assert.ok(ids.includes(id), "锚点目标不存在：" + id);
assert.equal((html.match(/<section\b/g) || []).length, 5, "原有四个场景加用户要求的 Star 邀请区");
assert.equal((html.match(/<h1\b/g) || []).length, 1);
assert.match(html, /<aside[^>]+id="privacy"/);
assert.doesNotMatch(html, /完全离线|不请求网络权限|0<\/strong>网络权限|新版安装包准备中/);
assert.match(html, /默认仅本地/);
assert.match(html, /云翻译.*(?:同意|配置)/);
assert.match(html, /早期测试版本的实际运行画面/);
assert.match(html, /Debug 测试版/);
assert.match(release.sha256, /^[a-f0-9]{64}$/);
assert.equal(release.internalCloudEnabled, false);
assert.equal(release.speechCloudEnabled, false, "未开放的语音服务不得宣传为已启用");
assert.equal(release.abi, "arm64-v8a");
assert.ok(release.bytes > 0);
assert.ok(html.includes((release.bytes / 1024 / 1024).toFixed(1) + " MiB"), "包体积与实际 APK 不一致");
const links = [...html.matchAll(/<a\b[^>]*>/g)].map((match) => match[0]);
const downloads = links.filter((link) => link.includes('href="downloads/' + release.filename + '"'));
assert.equal(downloads.length, 2, "首屏与下载区各保留一个实际下载入口");
for (const link of downloads) assert.match(link, /\bdownload\b/);
assert.ok(html.includes('href="downloads/' + release.filename + '.sha256"'));
for (const link of links.filter((link) => link.includes('target="_blank"'))) assert.match(link, /rel="noopener noreferrer"/);
assert.equal(links.filter((link) => link.includes('href="https://github.com/littlewindy123/haohao-ime"')).length, 3, "导航、社区和页脚均可前往实际仓库");
assert.match(html, /class="nav-star"[^>]+aria-label="在 GitHub 给好好输入法点 Star/);
assert.match(html, /给好好点个 Star/);
assert.match(html, /前往 GitHub，登录后点右上角 Star/);
assert.doesNotMatch(html + script, /api\.github\.com|shields\.io|已点亮|已成功点.*Star|stargazers_count/, "不伪造 Star 数量或点击成功状态，不增加跨站请求");
for (const example of ["nihao", "xuexi", "zhongwen"]) assert.ok(model.includes(example + ":"));
assert.match(model, /REVEAL_DELAY_MS = 300/);
assert.match(html, /id="phonetic-toggle"/);
assert.match(script, /aria-pressed/);
assert.match(script, /compositionstart/);
assert.match(script, /compositionend/);
assert.match(script, /visibilitychange/);
assert.match(script, /prefers-reduced-motion: reduce/);
assert.match(script, /IntersectionObserver/);
assert.doesNotMatch(script, /\.innerHTML\s*=|input\.focus\(|setInterval\(/);
assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
assert.match(css, /scroll-snap-type:\s*x mandatory/);
assert.match(css, /\.keyboard-crop\s*\{[^}]*aspect-ratio:\s*1\.22 \/ 1/);
assert.match(css, /\.keyboard-crop img\s*\{[^}]*object-fit:\s*cover;[^}]*object-position:\s*bottom/);
assert.doesNotMatch(html, /phone-speaker|roadmap-ledger|\bstyle="|\bon(?:click|load|error)="/, "避免装饰性手机外框、冗余进度表及违反 CSP 的内联代码");
assert.equal((html.match(/class="feature-card\b/g) || []).length, 3);
assert.equal((html.match(/<figure\b/g) || []).length, 3);
assert.match(html, /<noscript>/);
assert.match(html, /<h1>好好输入法<\/h1>/, "首屏必须首先建立品牌识别");
assert.match(html, /class="hero-logo"[^>]*src="assets\/haohao-golden.png"[^>]*width="432"/);
const hero = html.match(/<section class="hero-scene"[\s\S]*?<\/section>/)?.[0];
assert.ok(hero);
assert.doesNotMatch(hero, /Trime|Rime|ARM64|SHA-256|固定签名|Debug|GitHub|scene-index/);
assert.doesNotMatch(html, /还在认真打磨中|下一次更新|OPEN SOURCE · GPL-3.0|class="eyebrow"|feature-label/);
assert.doesNotMatch(html, /[—–]/);
assert.match(css, /prefers-color-scheme: dark/);
assert.match(css, /\.shell\s*\{[^}]*width:\s*calc\(100% - var\(--gutter\) \* 2\)/, "宽度随视口展开，不再被 1160px 限制");
assert.doesNotMatch(css, /min\(1160px|100vw|backdrop-filter|filter:\s*blur/, "全幅背景不制造横向溢出或昂贵的模糊图层");
for (const scene of ["hero-scene", "feature-scene", "showcase", "community-scene", "download-section"]) {
  assert.match(html, new RegExp('<section class="' + scene + '"'), "全幅场景外层不能受 shell 限宽");
}
assert.match(css, /\.community-inner\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\)/, "社区区域必须在移动端单列");
for (const viewport of [320, 375, 768, 1024, 1440, 1920, 2560]) {
  const gutter = viewport <= 420 ? 16 : viewport <= 767 ? 20 : viewport <= 1024 ? 24 : Math.min(88, Math.max(20, viewport * .04));
  const contentWidth = viewport - 2 * gutter;
  assert.ok(contentWidth / viewport >= .87, "正文应利用至少 87% 的视口宽度");
  assert.ok(contentWidth < viewport, "两侧保留安全边距");
}
assert.match(script, /gsap\.timeline\(/);
assert.match(script, /context\.revert\(/);
const golden = await readFile(path.join(root, "assets", "haohao-golden.png"));
assert.deepEqual(golden, await readFile(path.join(root, "..", "app", "src", "main", "res", "drawable-xxxhdpi", "haohao_golden_foreground.png")), "官网必须复用 App 的正式 Logo 原图");
const vendor = await readFile(path.join(root, "vendor", "gsap-3.15.0.min.js"));
assert.equal(createHash("sha256").update(vendor).digest("hex"), "92bb9a96476f983d212a2bc4f54c889039c1696dd4461d40a736860938570fbb", "GSAP 必须是经过校验的官方固定版本");
for (const tag of html.matchAll(/<img\b[^>]*>/g)) assert.match(tag[0], /\balt="[^"]*"/);
// Validate the source tree without a browser, including accidental layout nesting changes.
const voidTags = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"]);
const stack = [];
for (const [, closing, tag, rest] of html.matchAll(/<(\/?)([a-z][a-z0-9]*)(\s[^>]*|\s*)>/gi)) {
  if (voidTags.has(tag) || rest.endsWith("/")) continue;
  if (closing) assert.equal(stack.pop(), tag, "HTML 标签未正确闭合：" + tag);
  else stack.push(tag);
}
assert.equal(stack.length, 0, "HTML 有未闭合的容器");
function luminance(hex) {
  const channels = hex.match(/[a-f0-9]{2}/gi).map((part) => parseInt(part, 16) / 255)
    .map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}
for (const [foreground, background] of [["6f5e55", "fffdf8"], ["6f5e55", "dcece2"], ["513a32", "f4bf61"], ["513a32", "e8ac43"], ["6f5e55", "f9e8c9"], ["6f5e55", "e5f0e8"], ["6f5e55", "d2e6d9"], ["6f5e55", "f5ebd9"], ["c8b9aa", "25372e"], ["c8b9aa", "30483b"], ["c8b9aa", "332c23"], ["c8b9aa", "473822"], ["c8b9aa", "2d3c32"], ["f7f4ec", "211d19"]]) {
  assert.ok(css.includes("#" + foreground));
  const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  assert.ok((values[0] + 0.05) / (values[1] + 0.05) >= 4.5, `正文配色对比度不足：#${foreground} / #${background}`);
}
assert.doesNotMatch(css, /html\.motion-ready/, "不得先隐藏正文再依赖 JS 揭示");
assert.doesNotMatch(html + script, /fonts\.(googleapis|gstatic)\.com|googletagmanager|google-analytics|analytics\.js|plausible|umami/i);
const localAssets = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)]
  .map((match) => match[1]).filter((value) => !/^(?:https?:|mailto:|tel:)/.test(value))
  .map((value) => value.split("?")[0]).filter((value) => !value.startsWith("downloads/"));
for (const asset of new Set([...localAssets, "demo-model.mjs", "release.json"])) {
  const info = await stat(path.join(root, asset));
  assert.ok(info.size > 0, "资源为空：" + asset);
}
for (const image of ["screenshot-light.png", "screenshot-dark.png", "screenshot-expanded.png"]) {
  const data = await readFile(path.join(root, "assets", image));
  assert.equal(data.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(data.readUInt32BE(16), 1080);
  assert.equal(data.readUInt32BE(20), 2400);
}
const textBytes = gzipSync(Buffer.from(html + css + script + model)).length;
assert.ok(textBytes < 18 * 1024, "首屏文本压缩预算超限");
assert.ok(textBytes + gzipSync(vendor).length < 50 * 1024, "含动画库的文本压缩预算超限");
console.log("网站检查通过：品牌一致、下载、隐私文案、资源、无障碍与动效降级；文本 gzip " + textBytes + " bytes；含 GSAP " + (textBytes + gzipSync(vendor).length) + " bytes");
