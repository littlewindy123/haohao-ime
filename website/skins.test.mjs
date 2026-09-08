import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import { SKINS as STUDIO_SKINS } from './showroom.js';
import {SKINS,skinFor} from './skins.mjs';
test('four existing themes and six optimized character atlases',async()=>{
 assert.equal(Object.values(SKINS).filter(s=>s.kind==='builtin').length,4);assert.equal(Object.values(SKINS).filter(s=>s.kind==='concept').length,6);assert.equal(skinFor('__proto__'),SKINS.cream);
 for(const s of Object.values(SKINS).filter(s=>s.atlas)){const b=await readFile(new URL('./assets/'+s.atlas,import.meta.url));assert.equal(b.subarray(8,12).toString(),'WEBP');assert.ok(b.length<250*1024);}
});
test('key legend palettes have AA contrast',()=>{
 function lum(h){const c=h.slice(1).match(/../g).map(v=>parseInt(v,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return c[0]*.2126+c[1]*.7152+c[2]*.0722;}
 for(const s of Object.values(SKINS))for(const color of s.colors.slice(0,3)){const [hi,lo]=[lum(color),lum(s.ink)].sort((a,b)=>b-a);assert.ok((hi+.05)/(lo+.05)>=4.5,s.name);}
});
test('interactive studio and static hero share all ten identities and six atlases', () => {
 assert.deepEqual(Object.keys(STUDIO_SKINS), Object.keys(SKINS));
 for (const [id,skin] of Object.entries(SKINS)) {
  assert.equal(STUDIO_SKINS[id].name, skin.name);
  assert.equal(STUDIO_SKINS[id].atlas, skin.atlas);
 }
});

test('interactive studio remains usable without a GPU and reduced motion removes transitions', async () => {
 const js = await readFile(new URL('./showroom.js',import.meta.url),'utf8');
 const css = await readFile(new URL('./showroom.css',import.meta.url),'utf8');
 assert.doesNotMatch(js, /createKeyboardScene|loadModule|WebGLRenderer/);
 assert.match(js, /document.createElement\('button'\)/);
 assert.match(js, /setSkin\('taffy'\)/);
 assert.match(js, /pagehide/);
 assert.match(css, /prefers-reduced-motion/);
 assert.match(css, /transition:none/);
});

test('renderer guards stale textures and explicitly releases owned resources',async()=>{
 const s=await readFile(new URL('./scene.js',import.meta.url),'utf8');for(const r of [/token === revision/,/pendingImage.onload = null/,/texture.dispose\(/,/renderer.dispose\(/,/observer.disconnect\(/,/camera.position.set\(0,/,/interactive = false/])assert.match(s,r);
});
