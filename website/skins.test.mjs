import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import {SKINS,skinFor} from './skins.mjs';
import {Element,tick} from './test-dom.mjs';
test('four existing themes and six optimized character atlases',async()=>{
 assert.equal(Object.values(SKINS).filter(s=>s.kind==='builtin').length,4);assert.equal(Object.values(SKINS).filter(s=>s.kind==='concept').length,6);assert.equal(skinFor('__proto__'),SKINS.cream);
 for(const s of Object.values(SKINS).filter(s=>s.atlas)){const b=await readFile(new URL('./assets/'+s.atlas,import.meta.url));assert.equal(b.subarray(8,12).toString(),'WEBP');assert.ok(b.length<250*1024);}
});
test('key legend palettes have AA contrast',()=>{
 function lum(h){const c=h.slice(1).match(/../g).map(v=>parseInt(v,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return c[0]*.2126+c[1]*.7152+c[2]*.0722;}
 for(const s of Object.values(SKINS))for(const color of s.colors.slice(0,3)){const [hi,lo]=[lum(color),lum(s.ink)].sort((a,b)=>b-a);assert.ok((hi+.05)/(lo+.05)>=4.5,s.name);}
});
const source=(await readFile(new URL('./showroom.js',import.meta.url),'utf8')).replace(/^import[^\n]+\n/gm,'').replace(/import\('\.\/vendor\/scene-3d\.min\.js[^']*'\)/,'loadModule()');
async function setup({reduced=false,saveData=false,fail=false}={}){
 const root=new Element(),host=new Element(),document=new Element(),window=new Element(),motion=new Element();motion.matches=reduced;document.hidden=false;
 const name=new Element(),status=new Element();root.querySelector=s=>s==='#skin-name'?name:status;const choices=Object.keys(SKINS).map(id=>{const b=new Element();b.dataset.skinChoice=id;return b;});root.querySelectorAll=()=>choices;document.querySelector=s=>s==='#skin-studio'?root:host;
 let intersect,imports=0,disposed=0;const applied=[],fallback=[];
 vm.runInNewContext(source,{SKINS,skinFor,document,window,matchMedia:()=>motion,navigator:{connection:{saveData}},mountKeyboard:(host,opts)=>{assert.equal(opts.interactive,true);return{setSkin:s=>fallback.push(s.name)};},IntersectionObserver:class{constructor(fn){intersect=fn;}observe(){}disconnect(){}},loadModule:async()=>{imports++;if(fail)throw Error('offline');return{createKeyboardScene(h,opts){assert.equal(opts.interactive,true);opts.onReady();return{setSkin:s=>applied.push(s.name),dispose(){disposed++;}};}};}});
 return{root,host,name,choices,motion,window,applied,fallback,imports:()=>imports,disposed:()=>disposed,async enter(){intersect([{isIntersecting:true}]);await tick();}};
}
test('lazy default Taffy, latest pending choice and all ten themes',async()=>{
 const a=await setup();assert.equal(a.root.dataset.skin,'taffy');assert.equal(a.imports(),0);a.choices[9].emit('click');await a.enter();assert.equal(a.applied.at(-1),'懒羊羊');for(const b of a.choices){b.emit('click');assert.equal(a.name.textContent,SKINS[b.dataset.skinChoice].name);assert.equal(a.choices.filter(b=>b.attributes['aria-pressed']==='true').length,1);assert.equal(a.applied.at(-1),a.fallback.at(-1));}
});
test('motion/save-data/module-failure retain same complete themed fallback',async()=>{
 for(const options of [{reduced:true},{saveData:true},{fail:true}]){const a=await setup(options);await a.enter();a.choices[8].emit('click');assert.equal(a.fallback.at(-1),'蔡徐坤');assert.ok(!a.host.classList.contains('is-ready'));assert.equal(a.imports(),options.fail?1:0);}
});
test('restoration and reduced motion release GPU resources and preserve selection',async()=>{
 const a=await setup();await a.enter();a.choices[7].emit('click');a.window.emit('pagehide');a.window.emit('pageshow');await tick();assert.equal(a.applied.at(-1),'奶龙');a.motion.matches=true;a.motion.emit('change');assert.equal(a.disposed(),2);
});
test('renderer guards stale textures and explicitly releases owned resources',async()=>{
 const s=await readFile(new URL('./scene.js',import.meta.url),'utf8');for(const r of [/token === revision/,/pendingImage.onload = null/,/texture.dispose\(/,/renderer.dispose\(/,/observer.disconnect\(/,/camera.position.set\(0,/,/interactive = false/])assert.match(s,r);
});
