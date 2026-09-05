import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const prototypes = ["word-garden.html", "word-constellation.html", "living-candidate.html"];
const repository = "https://github.com/littlewindy123/haohao-ime";

function jpegDimensions(image) {
  let offset = 2;
  while (offset < image.length) {
    if (image[offset] !== 0xff) { offset += 1; continue; }
    const marker = image[offset + 1];
    offset += 2;
    if (marker === 0xd8 || marker === 0xd9) continue;
    const length = image.readUInt16BE(offset);
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      return { height: image.readUInt16BE(offset + 3), width: image.readUInt16BE(offset + 5) };
    }
    offset += length;
  }
  throw new Error("无法读取 JPEG 尺寸");
}

for (const file of prototypes) {
  const html = await readFile(path.join(root, file), "utf8");

  assert.match(html, /<html lang="zh-CN">/, `${file} 必须声明简体中文`);
  assert.match(html, /未来实验室|Future Lab/i, `${file} 缺少未来实验室标识`);
  assert.match(html, /正在探索/, `${file} 必须标注未来能力正在探索`);
  assert.match(html, /现在可用/, `${file} 必须区分当前能力`);
  assert.match(html, /暂无正式稳定 Release/, `${file} 必须保留发布状态说明`);
  assert.match(html, /id="demo-input"/, `${file} 缺少拼音输入`);
  assert.match(html, /id="ipa-toggle"/, `${file} 缺少 IPA 开关`);
  assert.match(html, /id="save-word"/, `${file} 缺少收藏操作`);
  assert.match(html, /id="review-options"/, `${file} 缺少轻量回想`);
  assert.match(html, /id="reset-demo"/, `${file} 缺少重置操作`);

  for (const word of ["nihao", "xuexi", "zhongwen"]) {
    assert.match(html, new RegExp(`${word}:\\s*\\{`), `${file} 缺少示例 ${word}`);
  }
  for (const stage of ["encountered", "saved", "reviewed"]) {
    assert.match(html, new RegExp(`stage:\\s*"${stage}"|"${stage}"`), `${file} 缺少状态 ${stage}`);
  }
  assert.match(html, /setTimeout\([\s\S]*?,\s*300\)/, `${file} 必须使用 300ms 延迟`);
  assert.match(html, /@media \(prefers-reduced-motion: reduce\)/, `${file} 必须支持减少动效`);
  assert.match(html, /min-height:\s*44px/, `${file} 必须提供至少 44px 的触控目标`);
  assert.match(html, /:focus-visible/, `${file} 必须提供键盘焦点样式`);

  const starLinks = [...html.matchAll(new RegExp(`<a\\b[^>]*href="${repository.replaceAll("/", "\\/")}"[^>]*>`, "g"))].map((match) => match[0]);
  assert.equal(starLinks.length, 3, `${file} 必须恰好包含导航、首屏、页尾三处 Star 入口`);
  for (const link of starLinks) {
    assert.match(link, /target="_blank"/, `${file} 的 Star 入口必须打开新标签页`);
    assert.match(link, /rel="noopener noreferrer"/, `${file} 的 Star 入口必须隔离 opener`);
  }

  assert.doesNotMatch(html, /<script\s+src=|<link[^>]+rel="stylesheet"/i, `${file} 不得依赖外部脚本或样式`);
  assert.doesNotMatch(html, /https?:\/\/(?!github\.com\/littlewindy123\/haohao-ime)/i, `${file} 不得产生 GitHub 之外的网络请求`);
  assert.doesNotMatch(html, /localStorage|sessionStorage|document\.cookie|fetch\(|XMLHttpRequest|navigator\.sendBeacon/i, `${file} 不得持久化、跟踪或上传输入`);
  assert.doesNotMatch(html, /googletagmanager|google-analytics|plausible|umami|fonts\.googleapis|fonts\.gstatic/i, `${file} 不得包含统计或外部字体`);
  assert.doesNotMatch(html, /style="/i, `${file} 不应混入行内样式`);

  const localAssets = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)]
    .map((match) => match[1])
    .filter((value) => !/^https?:/.test(value));
  for (const asset of new Set(localAssets)) {
    await access(path.resolve(root, asset));
  }
  for (const image of ["haohao-icon.png", "screenshot-light.png", "screenshot-dark.png", "screenshot-expanded.png"]) {
    assert.match(html, new RegExp(image.replace(".", "\\.")), `${file} 必须复用真实品牌资产 ${image}`);
  }
}

const comparison = await readFile(path.join(root, "index.html"), "utf8");
for (const file of prototypes) {
  assert.match(comparison, new RegExp(`href="${file.replace(".", "\\.")}"`), `对比页缺少 ${file} 入口`);
}
for (const name of ["word-garden", "word-constellation", "living-candidate"]) {
  for (const size of [
    { suffix: "desktop", minWidth: 1400, minHeight: 840 },
    { suffix: "mobile", minWidth: 375, minHeight: 800 },
  ]) {
    const image = await readFile(path.join(root, "screenshots", `${name}-${size.suffix}.jpg`));
    assert.equal(image.subarray(0, 2).toString("hex"), "ffd8", `${name}-${size.suffix} 不是 JPEG`);
    const dimensions = jpegDimensions(image);
    assert.ok(dimensions.width >= size.minWidth, `${name}-${size.suffix} 宽度过小`);
    assert.ok(dimensions.height >= size.minHeight, `${name}-${size.suffix} 高度过小`);
  }
}

console.log("design-demos/haohao-learning/check.mjs：三套方向静态检查通过");
