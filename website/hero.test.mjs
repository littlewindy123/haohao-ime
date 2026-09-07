import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import {KEY_ROWS,GREETING,greetingEvents} from './keyboard-model.mjs';
import {Element,tick} from './test-dom.mjs';
const source=(await readFile(new URL('./hero.js',import.meta.url),'utf8')).replace(/^import[^\n]+\n/,'').replace(/import\("\.\/vendor\/scene-3d\.min\.js[^"\n]*"\)/,'loadModule()');
async function setup({reduced=false,saveData=false,fail=false}={}){
 const elements=Object.fromEntries(['keyboard-scene','hero-greeting','hero-pause'].map(id=>[id,new Element()]));
 const document=new Element(),window=new Element(),motion=new Element();motion.matches=reduced;document.hidden=false;document.querySelector=s=>elements[s.slice(1)];
 const timelines=[],presses=[];let intersect,imports=0,disposed=0;
 window.gsap={timeline(options){const t={options,calls:[],active:false,killed:false,call(fn,args,time){this.calls.push({fn,time});return this;},to(){return this;},play(){this.active=true;},pause(){this.active=false;},kill(){this.killed=true;this.active=false;}};timelines.push(t);return t;}};
 vm.runInNewContext(source,{document,window,matchMedia:()=>motion,navigator:{connection:{saveData}},greetingEvents,mountKeyboard:()=>({press:k=>presses.push(k)}),IntersectionObserver:class{constructor(fn){intersect=fn;}observe(){}disconnect(){}},loadModule:async()=>{imports++;if(fail)throw Error('WebGL unavailable');return{createKeyboardScene(host,opts){assert.equal(opts.interactive,false);opts.onReady();return{press:k=>presses.push(k),dispose(){disposed++;}};}};}});await tick();
 return{elements,document,window,motion,timelines,presses,imports:()=>imports,disposed:()=>disposed,intersect:v=>intersect([{isIntersecting:v}])};
}
test('complete 26-key board, exact greeting, corresponding keys and phrase holds',()=>{
 const keys=KEY_ROWS.flat().map(k=>k.id);assert.equal(keys.filter(k=>/^[A-Z]$/.test(k)).length,26);assert.equal(new Set(keys).size,keys.length);for(const k of ['SPACE','ENTER','BACKSPACE','LANG'])assert.ok(keys.includes(k));
 const phrases=GREETING.map(s=>s.map(c=>c[0]).join(''));assert.deepEqual(phrases,['你好','欢迎你来看我的作品','跪求star支持']);const seq=greetingEvents();let last=-1;
 for(const e of seq.events){assert.ok(e.time>=last);last=e.time;if(e.key)assert.ok(keys.includes(e.key));}
 for(const [i,text] of phrases.entries()){const j=seq.events.findIndex(e=>e.text===text);assert.ok(seq.events[j+1].time-seq.events[j].time>=(i===2?3:1.5));}
});
test('automatic timeline drives physical keys and clears for next loop',async()=>{
 const a=await setup(),t=a.timelines[0];assert.equal(t.options.repeat,-1);assert.ok(t.active);t.calls.forEach(c=>c.fn());assert.ok(a.presses.includes('N'));assert.equal(a.elements['hero-greeting'].textContent,'');assert.equal(a.presses.filter(k=>k==='ENTER').length,3);
});
test('manual pause survives visibility changes; background and offscreen pause',async()=>{
 const a=await setup(),t=a.timelines[0];a.elements['hero-pause'].emit('click');assert.ok(!t.active);a.intersect(false);a.intersect(true);assert.ok(!t.active);a.elements['hero-pause'].emit('click');assert.ok(t.active);a.document.hidden=true;a.document.emit('visibilitychange');assert.ok(!t.active);a.document.hidden=false;a.document.emit('visibilitychange');assert.ok(t.active);a.intersect(false);assert.ok(!t.active);
});
test('reduced motion/save-data stay static; WebGL failure retains working fallback',async()=>{
 for(const options of [{reduced:true},{saveData:true}]){const a=await setup(options);assert.equal(a.imports(),0);assert.equal(a.timelines.length,0);assert.match(a.elements['hero-greeting'].textContent,/你好.*欢迎你来看我的作品.*跪求star支持/);assert.ok(a.elements['hero-pause'].hidden);}
 const a=await setup({fail:true});assert.ok(!a.elements['keyboard-scene'].classList.contains('is-ready'));a.timelines[0].calls.forEach(c=>c.fn());assert.ok(a.presses.length>0);
});
test('restoration and motion changes release scenes and never duplicate loops',async()=>{
 const a=await setup();a.window.emit('pagehide');assert.ok(a.timelines.every(t=>t.killed));a.window.emit('pageshow');await tick();assert.equal(a.timelines.filter(t=>!t.killed).length,1);a.motion.matches=true;a.motion.emit('change');assert.ok(a.timelines.every(t=>t.killed));a.motion.matches=false;a.motion.emit('change');await tick();assert.equal(a.timelines.filter(t=>!t.killed).length,1);assert.ok(a.disposed()>=2);
});
