import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';
const script=await readFile(new URL('../shared/detect-favorites.js',import.meta.url),'utf8');
async function run(panels){const window={};const context={window,URL,location:{href:'https://www.tiktok.com/messages'},document:{querySelectorAll:()=>panels},getComputedStyle:()=>({visibility:'visible',overflowY:'visible'}),setTimeout:fn=>fn(),Error,Set};await vm.runInNewContext(script,context);return window.__sbScanResult}
test('does not scan messages when no picker is open',async()=>{const result=await run([]);assert.match(result.error,/panel/)});
test('deduplicates and rejects non HTTPS resources and avatars',async()=>{const image=(src,alt='sticker')=>({src,currentSrc:src,alt,className:'',getAttribute:()=>null});const imgs=[image('https://p.tiktokcdn.com/a.webp'),image('https://p.tiktokcdn.com/a.webp'),image('http://p.tiktokcdn.com/b.webp'),image('https://p.tiktokcdn.com/avatar.webp','avatar')];const panel={getClientRects:()=>[{}],querySelector:()=>imgs[0],closest:()=>null,querySelectorAll:s=>s==='img'?imgs:[]};const result=await run([panel]);assert.equal(result.urls.length,1);assert.equal(result.urls[0],'https://p.tiktokcdn.com/a.webp')});
