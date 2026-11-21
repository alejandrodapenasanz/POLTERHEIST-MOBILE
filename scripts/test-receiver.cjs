// Run the actual embedded receiver script with a small DOM/HTTP test harness.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/receiver.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const elements = Object.fromEntries(['image','video','message','status','sound','full'].map(id => [id, {
  style: {}, src: '', textContent: '', muted: true, plays: 0, pauses: 0,
  play() { this.plays++; return { catch() {} }; },
  pause() { this.pauses++; }, load() {},
  removeAttribute(attr) { this[attr] = ''; }
}]));
let request;
const scheduled = [];
class XHR {
  constructor() { request = this; }
  open(method,url) { this.url=url; assert.equal(method,'GET'); }
  send() {}
}
vm.runInNewContext(script, {
  document: { getElementById: id => elements[id], documentElement: {} },
  XMLHttpRequest: XHR, setTimeout: callback => scheduled.push(callback)
});
function respond(state) {
  request.status=200; request.responseText=JSON.stringify(state); request.onload();
  scheduled.shift()();
}
const videoState={url:'/media/token.mp4',name:'sample',image:false,loop:false,command:'play'};
respond(videoState); assert.equal(elements.video.src,videoState.url); assert.equal(elements.video.plays,1);
respond(videoState); assert.equal(elements.video.plays,1,'polling must not repeatedly restart playback');
respond({...videoState,command:'pause'}); const pauses=elements.video.pauses;
respond({...videoState,command:'pause'}); assert.equal(elements.video.pauses,pauses);
respond({...videoState,loop:true}); assert.equal(elements.video.loop,true); assert.equal(elements.video.plays,2);
respond({url:'/media/photo.jpg',name:'photo',image:true,command:'play'});
assert.equal(elements.image.style.display,'block'); assert.equal(elements.video.src,'');
respond({command:'stop'}); assert.equal(elements.image.src,'');assert.equal(elements.image.style.display,'none');
respond(videoState);
for(let i=0;i<3;i++){request.onerror();scheduled.shift()();}
assert.equal(elements.video.style.display,'none');assert.match(elements.status.textContent,/perdido la conexión/);
respond(videoState);assert.equal(elements.video.style.display,'block');
console.log('Receiver checks passed: load, pause, resume, repeat, image, stop, connection loss and recovery.');
