import {cp,mkdir,copyFile,readFile,writeFile,readdir} from 'node:fs/promises';
import vm from 'node:vm';
// WKWebView file URLs cannot reliably load module scripts. The current Vite
// single-entry build has no imports/exports; verify it parses as classic JS.
for (const name of await readdir('pwa/dist/assets')) {
  if(name.endsWith('.js')) new vm.Script(await readFile(`pwa/dist/assets/${name}`,'utf8'));
}
for (const dir of ['app/src/main/assets/web','ios/StickerBridge/Resources/web']) {
  await mkdir(dir,{recursive:true});await cp('pwa/dist',dir,{recursive:true});
  const html=await readFile(`${dir}/index.html`,'utf8');
  await writeFile(`${dir}/index.html`,html.replace(/type="module"/g,'defer').replace(/ crossorigin/g,''));
}
await copyFile('shared/detect-favorites.js','app/src/main/assets/detect-favorites.js');
await copyFile('shared/detect-favorites.js','ios/StickerBridge/Resources/detect-favorites.js');
