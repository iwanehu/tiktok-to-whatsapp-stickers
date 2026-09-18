export type NativeSticker={base64:string;animated:boolean;name:string}
export type ImportResult={stickers:NativeSticker[];warning?:string}
export type ExportResult={status:'confirmed'|'handed_off'|'cancelled'}
declare global{interface Window{StickerNative?:{postMessage(message:string):void};webkit?:{messageHandlers?:{stickerBridge?:{postMessage(message:unknown):void}}};__stickerReply?:(r:{id:string;result?:unknown;error?:string})=>void}}
const pending=new Map<string,{resolve:(v:any)=>void;reject:(e:Error)=>void;timer:ReturnType<typeof setTimeout>}>()
window.__stickerReply=r=>{const p=pending.get(r.id);if(!p)return;clearTimeout(p.timer);pending.delete(r.id);if(r.error)p.reject(Error(r.error));else p.resolve(r.result)}
export const isNative=()=>Boolean(window.StickerNative||window.webkit?.messageHandlers?.stickerBridge)
export function nativeCall<T>(method:string,payload:unknown={}):Promise<T>{return new Promise((resolve,reject)=>{if(!isNative())return reject(Error('Necesitas la aplicación móvil.'));const id=crypto.randomUUID();const timer=setTimeout(()=>{pending.delete(id);reject(Error('La operación ha caducado.'))},900000);pending.set(id,{resolve,reject,timer});try{const m={id,method,payload};if(window.StickerNative)window.StickerNative.postMessage(JSON.stringify(m));else window.webkit!.messageHandlers!.stickerBridge!.postMessage(m)}catch(e){clearTimeout(timer);pending.delete(id);reject(e)}})}
export async function blobBase64(blob:Blob){const b=new Uint8Array(await blob.arrayBuffer());let s='';for(let i=0;i<b.length;i+=8192)s+=String.fromCharCode(...b.subarray(i,i+8192));return btoa(s)}
export const base64Blob=(v:string)=>new Blob([Uint8Array.from(atob(v),c=>c.charCodeAt(0))],{type:'image/webp'})
