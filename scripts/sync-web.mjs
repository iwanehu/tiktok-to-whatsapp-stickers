import {cp,mkdir,copyFile} from 'node:fs/promises';
await mkdir('app/src/main/assets/web',{recursive:true});
await mkdir('ios/StickerBridge/Resources/web',{recursive:true});
await cp('pwa/dist','app/src/main/assets/web',{recursive:true});
await cp('pwa/dist','ios/StickerBridge/Resources/web',{recursive:true});
await copyFile('shared/detect-favorites.js','app/src/main/assets/detect-favorites.js');
await copyFile('shared/detect-favorites.js','ios/StickerBridge/Resources/detect-favorites.js');
