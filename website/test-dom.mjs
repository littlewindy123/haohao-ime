export const tick=()=>new Promise(resolve=>setImmediate(resolve));
export class Element {
 constructor(){this.events={};this.attributes={};this.dataset={};this.children=[];this.style={setProperty(){},removeProperty(){}};const names=new Set();this.classList={add:n=>names.add(n),remove:n=>names.delete(n),contains:n=>names.has(n)};}
 addEventListener(name,fn){(this.events[name]??=[]).push(fn);}
 emit(name){this.events[name]?.forEach(fn=>fn());}
 setAttribute(name,value){this.attributes[name]=value;}
 getAttribute(name){return this.attributes[name];}
 querySelector(){return undefined;}
 append(child){this.children.push(child);}
}
