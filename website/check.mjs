import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const [html, css, script] = await Promise.all([
  readFile(path.join(root, "index.html"), "utf8"),
  readFile(path.join(root, "styles.css"), "utf8"),
  readFile(path.join(root, "main.js"), "utf8"),
]);

for (const id of ["main", "top", "demo", "features", "showcase", "privacy", "opensource"]) {
  assert.match(html, new RegExp(`id=["']${id}["']`), `缺少页面锚点 #${id}`);
}

assert.doesNotMatch(html, /name="robots"/);
assert.doesNotMatch(html, /rel="canonical"/);
assert.equal((html.match(/<section\b/g) || []).length, 4, "页面必须保持四个内容场景");
assert.match(html, /<aside[^>]+id="privacy"/, "隐私锚点必须保留在真机画面场景内");
const starLinks = [...html.matchAll(/<a\b[^>]*href="https:\/\/github\.com\/littlewindy123\/haohao-ime"[^>]*>/g)].map((match) => match[0]);
assert.equal(starLinks.length, 1, "Star 主入口必须保留在开源场景");
for (const link of starLinks) {
  assert.match(link, /target="_blank"/, "Star 入口必须在新标签页打开");
  assert.match(link, /rel="noopener noreferrer"/, "Star 入口必须隔离 opener");
}
const downloadLinks = [...html.matchAll(/<a\b[^>]*href="downloads\/haohao-ime-3\.3\.12-arm64-v8a-runtime-ready-fix-v2-debug\.apk"[^>]*>/g)].map((match) => match[0]);
assert.equal(downloadLinks.length, 2, "导航和首屏必须各提供一个测试包下载入口");
for (const link of downloadLinks) assert.match(link, /\bdownload\b/, "APK 入口必须触发下载");
assert.match(script, /nihao:/);
assert.match(script, /xuexi:/);
assert.match(script, /zhongwen:/);
assert.match(script, /}, 300\);/);
assert.match(html, /id="phonetic-toggle"/, "互动演示必须保留 IPA 开关");
assert.match(script, /prefers-reduced-motion: reduce/);
assert.match(script, /IntersectionObserver/);
assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
assert.match(css, /scroll-snap-type:\s*x mandatory/, "移动端真机画面必须支持横向吸附浏览");
assert.doesNotMatch(html, /fonts\.(googleapis|gstatic)\.com|googletagmanager|google-analytics|analytics\.js|plausible|umami/i);

const localAssets = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)]
  .map((match) => match[1])
  .filter((value) => !/^(?:https?:|mailto:|tel:)/.test(value))
  .filter((value) => !/\.apk(?:\.sha256)?$/.test(value))
  .map((value) => value.split("?")[0]);

for (const asset of new Set(localAssets)) {
  const info = await stat(path.join(root, asset));
  assert.ok(info.size > 0, `资源为空：${asset}`);
}

for (const image of ["screenshot-light.png", "screenshot-dark.png", "screenshot-expanded.png"]) {
  const data = await readFile(path.join(root, "assets", image));
  assert.equal(data.subarray(1, 4).toString("ascii"), "PNG", `${image} 不是 PNG`);
  assert.equal(data.readUInt32BE(16), 1080, `${image} 宽度异常`);
  assert.equal(data.readUInt32BE(20), 2400, `${image} 高度异常`);
}

console.log("website/check.mjs：全部检查通过");
