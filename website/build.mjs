import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import "./check.mjs";
import { verifyPublicApk } from "./verify-apk.mjs";
import { build } from "esbuild";

const root = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
function argument(name) {
  const index = args.indexOf(name);
  assert.ok(index >= 0 && args[index + 1], "Missing " + name);
  return path.resolve(args[index + 1]);
}
const out = argument("--out");
const apkPath = argument("--apk");
assert.notEqual(out, root, "Never build over the source directory");
await mkdir(out, { recursive: true });
assert.equal((await readdir(out)).length, 0, "Output directory must be empty; previous builds are preserved");
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
const release = JSON.parse(await readFile(path.join(root, "release.json"), "utf8"));
assert.match(release.filename, /^haohao-ime-[a-zA-Z0-9.-]+\.apk$/);
const apk = await readFile(apkPath);
assert.equal(apk.length, release.bytes, "APK size does not match release.json");
assert.equal(digest(apk), release.sha256, "APK hash does not match release.json");
verifyPublicApk(apkPath, release.signerSha256);

let main = await readFile(path.join(root, "main.js"), "utf8");
const model = (await readFile(path.join(root, "demo-model.mjs"), "utf8"))
  .replace(/^export (const|function) /gm, "$1 ");
const importLine = /^import \{[^\n]+\} from "\.\/demo-model\.mjs";\r?\n/;
assert.match(main, importLine);
main = model + "\n" + main.replace(importLine, "");
assert.doesNotMatch(main, /^import |^export /m, "Static production bundle must not need module fetches");
const css = await readFile(path.join(root, "styles.css"), "utf8");
const showroomCss = await readFile(path.join(root, "showroom.css"));
const showroomBuild = await build({ entryPoints: [path.join(root, "showroom.js")], bundle: true, format: "esm", minify: true, write: false, target: "es2022" });
const showroom = Buffer.from(showroomBuild.outputFiles[0].text);
const scene = await readFile(path.join(root, "vendor/scene-3d.min.js"));
let hero = await readFile(path.join(root, "hero.js"), "utf8");
assert.match(hero, importLine);
hero = model + "\n" + hero.replace(importLine, "");
hero = hero.replace('./vendor/scene-3d.min.js', './vendor/scene-3d.min.js?v=' + digest(scene).slice(0, 12));
let html = await readFile(path.join(root, "index.html"), "utf8");
html = html.replace(/main\.js\?v=[^"\s]+/, "main.js?v=" + digest(main).slice(0, 12));
html = html.replace(/hero\.js\?v=[^"\s]+/, "hero.js?v=" + digest(hero).slice(0, 12));
html = html.replace(/styles\.css\?v=[^"\s]+/, "styles.css?v=" + digest(css).slice(0, 12));
html = html.replace(/showroom\.js\?v=[^"\s]+/, "showroom.js?v=" + digest(showroom).slice(0, 12));
html = html.replace(/showroom\.css\?v=[^"\s]+/, "showroom.css?v=" + digest(showroomCss).slice(0, 12));
html = html.replaceAll('loading="lazy"', 'loading="lazy" decoding="async"');
const files = new Map([
  ["index.html", Buffer.from(html)],
  ["styles.css", Buffer.from(css)],
  ["main.js", Buffer.from(main)],
  ["hero.js", Buffer.from(hero)],
  ["showroom.js", showroom],
  ["showroom.css", showroomCss],
  ["vendor/scene-3d.min.js", scene],
  ["release.json", Buffer.from(JSON.stringify(release, null, 2) + "\n")],
]);
for (const name of ["haohao-icon.png", "haohao-golden.png", "og.png", "screenshot-light.png", "screenshot-dark.png", "screenshot-expanded.png", "keyboard-still.webp", "words.png", "review.png"]) {
  files.set("assets/" + name, await readFile(path.join(root, "assets", name)));
}
files.set("vendor/gsap-3.15.0.min.js", await readFile(path.join(root, "vendor", "gsap-3.15.0.min.js")));
for (const name of ["taffy", "raiden", "yasuo", "nailong", "kun", "lanyangyang"]) {
  const filename = "assets/keycap-" + name + ".webp";
  files.set(filename, await readFile(path.join(root, filename)));
}
files.set("vendor/three-LICENSE.txt", await readFile(path.join(root, "vendor/three-LICENSE.txt")));
// Every image URL is content-addressed, including the preloaded fallback image.
html = html.replace(/((?:src|href)="|content=")(assets\/[^"?]+)(?:\?[^"\s]*)?"/g, (tag, prefix, name) =>
  files.has(name) ? prefix + name + '?v=' + digest(files.get(name)).slice(0, 12) + '"' : tag);
files.set("index.html", Buffer.from(html));
files.set("downloads/" + release.filename, apk);
files.set("downloads/" + release.filename + ".sha256", Buffer.from(release.sha256 + "  " + release.filename + "\n"));
for (const [name, bytes] of files) {
  const target = path.join(out, name);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, bytes);
  if (/\.(html|css|js|json)$/.test(name)) {
    await writeFile(target + ".gz", gzipSync(bytes, { level: 9 }));
  }
}
const manifest = Object.fromEntries([...files].map(([name, bytes]) => [name, { bytes: bytes.length, sha256: digest(bytes) }]));
await writeFile(path.join(out, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log("Production site ready: " + out);
console.log("APK: " + release.filename + " (" + release.bytes + " bytes)");
console.log("Only public website assets and the verified APK were included.");
