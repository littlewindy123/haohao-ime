import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';

// Patch a COPY of the current public release: newer hero/media changes stay intact.
const source = path.dirname(fileURLToPath(import.meta.url));
const index = process.argv.indexOf('--site');
assert.ok(index >= 0 && process.argv[index + 1], 'Usage: node patch-showroom.mjs --site <copied-public-release>');
const destination = path.resolve(process.argv[index + 1]);
assert.notEqual(destination, source, 'Do not overwrite source files');
let html = await readFile(path.join(destination, 'index.html'), 'utf8');
assert.match(html, /id="skin-studio"/, 'The target must contain the existing skin studio');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(await readFile(path.join(destination, 'manifest.json'), 'utf8'));
const result = await build({ entryPoints: [path.join(source, 'showroom.js')], bundle: true, format: 'esm', minify: true, write: false, target: 'es2022' });
let script = result.outputFiles[0].text;
const files = new Map();
for (const name of ['taffy', 'raiden', 'yasuo', 'nailong', 'kun', 'lanyangyang']) {
  const filename = `keycap-${name}.webp`;
  const bytes = await readFile(path.join(source, 'assets', filename));
  script = script.replaceAll(filename, filename + '?v=' + hash(bytes).slice(0, 12));
  files.set('assets/' + filename, bytes);
}
const css = await readFile(path.join(source, 'showroom.css'));
files.set('showroom.js', Buffer.from(script)); files.set('showroom.css', css);
html = html.replace(/showroom\.js(?:\?[^"\s]*)?/g, 'showroom.js?v=' + hash(script).slice(0, 12));
const stylesheet = 'showroom.css?v=' + hash(css).slice(0, 12);
html = /href="showroom\.css/.test(html) ? html.replace(/showroom\.css(?:\?[^"\s]*)?/g, stylesheet) : html.replace('</head>', `  <link rel="stylesheet" href="${stylesheet}" />\n</head>`);
files.set('index.html', Buffer.from(html));
for (const [name, bytes] of files) {
  const file = path.join(destination, name);
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, bytes);
  if (/\.(js|css|html)$/.test(name)) await writeFile(file + '.gz', gzipSync(bytes));
  manifest[name] = { bytes: bytes.length, sha256: hash(bytes) };
}
await writeFile(path.join(destination, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`Keyboard updated in ${destination}; APK and unrelated assets preserved.`);
