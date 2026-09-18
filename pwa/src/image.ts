export async function toWhatsAppWebP(file:File):Promise<Blob>{
 if(file.size>10*1024*1024)throw Error(`${file.name}: máximo 10 MB.`)
 if(!['image/png','image/jpeg','image/webp'].includes(file.type))throw Error('Formato no compatible.')
 const b=new Uint8Array(await file.arrayBuffer());const tag=(start:number)=>String.fromCharCode(...b.slice(start,start+4))
 if((tag(12)==='VP8X'&&Boolean(b[20]&2))||(tag(0)==='\u0089PNG'&&new TextDecoder('latin1').decode(b).includes('acTL')))throw Error(`${file.name}: importa la animación desde TikTok en la app móvil para conservarla.`)
 const bitmap=await createImageBitmap(file)
 try{if(bitmap.width*bitmap.height>16000000)throw Error('Imagen demasiado grande.');const canvas=document.createElement('canvas');canvas.width=512;canvas.height=512;const ctx=canvas.getContext('2d')!;const s=Math.min(512/bitmap.width,512/bitmap.height),w=Math.max(1,Math.round(bitmap.width*s)),h=Math.max(1,Math.round(bitmap.height*s));ctx.drawImage(bitmap,(512-w)/2,(512-h)/2,w,h);for(const q of [.86,.7,.5,.3,.1]){const blob=await new Promise<Blob|null>(resolve=>canvas.toBlob(resolve,'image/webp',q));if(!blob||blob.type!=='image/webp')throw Error('Este navegador no puede crear WebP. Usa la app móvil.');if(blob.size<=102400)return blob}throw Error('No se pudo reducir a 100 KB.')}finally{bitmap.close()}
}
export function saveBlob(blob:Blob,name:string){const u=URL.createObjectURL(blob),a=document.createElement('a');a.href=u;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000)}
