import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import {Element} from "./test-dom.mjs";
const source=await readFile(new URL("./main.js",import.meta.url),"utf8");
function setup({reduced=false,saveData=false,gsap=true,hash=""}={}) {
  const document=new Element(),window=new Element(),motion=new Element(),install=new Element(),link=new Element();
  motion.matches=reduced;document.hidden=false;document.readyState="loading";window.location={hash};window.matchMedia=()=>motion;
  document.querySelector=s=>s==="#install"?install:null;
  document.querySelectorAll=s=>s==='a[href="#install"]'?[link]:s.includes("copy")?[new Element()]:[];
  const contexts=[],timelines=[];let callback,disconnects=0;
  if(gsap)window.gsap={context(fn){const ctx={reverted:false,revert(){this.reverted=true;}};fn();contexts.push(ctx);return ctx;},timeline(options){const t={options,fromTo(){return this;}};timelines.push(t);return t;},fromTo(){}};
  class Observer {constructor(fn){callback=fn;}observe(){}unobserve(){}disconnect(){disconnects++;}}
  window.IntersectionObserver=Observer;
  vm.runInNewContext(source,{document,window,navigator:{connection:{saveData}},IntersectionObserver:Observer});
  return {document,window,motion,install,link,contexts,timelines,disconnects:()=>disconnects,intersect:()=>callback([{isIntersecting:true,target:new Element()}])};
}
test("page polish contains no duplicate input controller",()=>{
  assert.doesNotMatch(source,/pinyin-input|candidate-list|compositionstart|demo-model|REVEAL_DELAY/);
  assert.match(source,/\[data-intro="copy"\] \.hero-actions/);
  assert.doesNotMatch(source,/\[data-intro="copy"\] > \*/);
});
test("install deep links and direct navigation remain functional",()=>{
 const a=setup({hash:"#install"});assert.equal(a.install.open,true);
 const b=setup();b.link.emit("click");assert.equal(b.install.open,true);b.install.open=false;b.window.location.hash="#install";b.window.emit("hashchange");assert.equal(b.install.open,true);
});
test("intro uses one cancellable transform-only GSAP timeline",()=>{
 const a=setup();a.window.emit("load");assert.equal(a.timelines.length,1);assert.equal(a.timelines[0].options.defaults.clearProps,"transform");
 a.document.hidden=true;a.document.emit("visibilitychange");assert.ok(a.contexts.every(c=>c.reverted));
});
test("reduced motion, data saver, deep links and script failure keep content static",()=>{
 for(const opts of [{reduced:true},{saveData:true},{hash:"#features"},{gsap:false}]) {const a=setup(opts);a.window.emit("load");assert.equal(a.timelines.length,0);}
});
test("page departure releases observers and animations; back-forward restores observation",()=>{
 const a=setup();a.window.emit("load");a.intersect();a.window.emit("pagehide");assert.ok(a.contexts.every(c=>c.reverted));assert.equal(a.disconnects(),1);a.window.emit("pageshow");assert.equal(a.disconnects(),2);
});
