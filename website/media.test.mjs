import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import {Element,tick} from './test-dom.mjs';
const source=await readFile(new URL('./media.js',import.meta.url),'utf8');
function setup({reduced=false,saveData=false,reject=false}={}){
 const video=new Element(),button=new Element(),document=new Element(),window=new Element(),motion=new Element();let intersect,loads=0,plays=0;motion.matches=reduced;document.hidden=false;video.paused=true;video.attributes['aria-label']='实录';button.firstElementChild={};video.parentElement={querySelector:()=>button};const sources=[{dataset:{src:'clip.webm'}}];video.querySelectorAll=()=>sources;
 video.load=()=>loads++;video.pause=()=>{video.paused=true;video.emit('pause');};video.play=()=>{plays++;if(reject)return Promise.reject(Error('NotAllowedError'));video.paused=false;video.emit('play');return Promise.resolve();};document.querySelectorAll=()=>[video];
 vm.runInNewContext(source,{document,window,matchMedia:()=>motion,navigator:{connection:{saveData}},IntersectionObserver:class{constructor(fn){intersect=fn;}observe(){}disconnect(){}}});return{video,button,document,motion,loads:()=>loads,plays:()=>plays,enter:()=>intersect([{target:video,isIntersecting:true}]),exit:()=>intersect([{target:video,isIntersecting:false}])};
}
test('lazy media plays in view and pauses hidden/offscreen',async()=>{const a=setup();assert.equal(a.loads(),0);a.enter();await tick();assert.equal(a.loads(),1);assert.ok(!a.video.paused);a.exit();assert.ok(a.video.paused);a.enter();await tick();assert.equal(a.loads(),1);a.document.hidden=true;a.document.emit('visibilitychange');assert.ok(a.video.paused);});
test('manual pause persists and explicit resume works',async()=>{const a=setup();a.enter();await tick();a.button.emit('click');assert.ok(a.video.paused);a.exit();a.enter();assert.ok(a.video.paused);a.button.emit('click');await tick();assert.ok(!a.video.paused);});
test('reduced motion/save-data keep poster until explicit play',async()=>{for(const c of [{reduced:true},{saveData:true}]){const a=setup(c);a.enter();assert.equal(a.loads(),0);a.button.emit('click');await tick();assert.equal(a.loads(),1);assert.ok(!a.video.paused);}});
test('autoplay refusal cannot retry-loop; loading error stays accessible',async()=>{const a=setup({reject:true});a.enter();await tick();a.exit();a.enter();await tick();assert.equal(a.plays(),1);assert.match(a.button.attributes['aria-label'],/播放/);a.video.emit('error');assert.ok(a.button.disabled);assert.match(a.button.attributes['aria-label'],/无法播放/);});
