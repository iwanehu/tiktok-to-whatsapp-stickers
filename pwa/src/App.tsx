import { ChangeEvent, useMemo, useState } from 'react'
import { saveBlob, toWhatsAppWebP } from './image'

type Sticker = { id: string; name: string; blob: Blob; url: string }
declare global { interface Window { Capacitor?: { Plugins?: { StickerBridge?: { addPack(input: unknown): Promise<void> } } } } }

export default function App() {
  const [packName, setPackName] = useState('Mis stickers')
  const [author, setAuthor] = useState('Sticker Bridge')
  const [stickers, setStickers] = useState<Sticker[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('Importa entre 3 y 30 imágenes.')
  const canExport = stickers.length >= 3 && stickers.length <= 30
  const nativeBridge = Boolean(window.Capacitor?.Plugins?.StickerBridge)
  const total = useMemo(() => stickers.reduce((sum, item) => sum + item.blob.size, 0), [stickers])

  async function importFiles(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []).slice(0, 30 - stickers.length)
    if (!files.length) return
    setBusy(true); setMessage('Convirtiendo imágenes…')
    try {
      const converted = await Promise.all(files.map(async file => {
        if (!file.type.startsWith('image/')) throw new Error(`${file.name} no es una imagen.`)
        const blob = await toWhatsAppWebP(file)
        return { id: crypto.randomUUID(), name: file.name.replace(/\.[^.]+$/, ''), blob, url: URL.createObjectURL(blob) }
      }))
      setStickers(current => [...current, ...converted])
      setMessage(`${converted.length} sticker(s) importados correctamente.`)
    } catch (error) { setMessage(error instanceof Error ? error.message : 'No se pudo importar.') }
    finally { setBusy(false); event.target.value = '' }
  }

  function remove(id: string) {
    setStickers(current => { const found=current.find(x=>x.id===id); if(found) URL.revokeObjectURL(found.url); return current.filter(x=>x.id!==id) })
  }

  async function exportPack() {
    if (!canExport) return setMessage('WhatsApp requiere al menos 3 stickers por paquete.')
    const bridge = window.Capacitor?.Plugins?.StickerBridge
    if (bridge) {
      await bridge.addPack({ name: packName, author, stickers: stickers.map(x => ({ name: x.name })) })
      return
    }
    if (navigator.share && navigator.canShare?.({ files: [new File([stickers[0].blob], 'sticker.webp', {type:'image/webp'})] })) {
      const files = stickers.map((item, index) => new File([item.blob], `sticker-${index + 1}.webp`, { type: 'image/webp' }))
      await navigator.share({ title: packName, text: `Paquete creado por ${author}`, files })
      return
    }
    stickers.forEach((item, index) => saveBlob(item.blob, `sticker-${index + 1}.webp`))
    setMessage('Archivos descargados. La instalación directa llegará con el puente móvil.')
  }

  return <main>
    <header><div><span className="eyebrow">ANDROID · IOS · WEB</span><h1>Sticker Bridge</h1><p>Crea tu paquete sin entregar tus credenciales de TikTok.</p></div><a className="secondary" href="https://www.tiktok.com/messages" target="_blank" rel="noreferrer">Abrir TikTok ↗</a></header>
    <section className="card intro"><div><b>1.</b><span>Guarda o comparte las imágenes desde TikTok.</span></div><div><b>2.</b><span>Impórtalas aquí y crea el paquete.</span></div><div><b>3.</b><span>Compártelo o añádelo desde la app móvil.</span></div></section>
    <section className="card editor">
      <div className="fields"><label>Nombre del paquete<input value={packName} maxLength={128} onChange={e=>setPackName(e.target.value)}/></label><label>Autor<input value={author} maxLength={128} onChange={e=>setAuthor(e.target.value)}/></label></div>
      <label className={`drop ${busy?'disabled':''}`}><input type="file" accept="image/png,image/jpeg,image/webp" multiple disabled={busy||stickers.length>=30} onChange={importFiles}/><strong>{busy?'Procesando…':'Seleccionar imágenes'}</strong><small>PNG, JPEG o WebP · máximo 30</small></label>
      <div className="status"><span>{message}</span><span>{stickers.length}/30 · {(total/1024).toFixed(0)} KB</span></div>
      {stickers.length ? <div className="grid">{stickers.map((item,index)=><article key={item.id}><img src={item.url} alt={item.name}/><button aria-label={`Eliminar sticker ${index+1}`} onClick={()=>remove(item.id)}>×</button><span>{index+1}</span></article>)}</div> : <div className="empty">Tus stickers aparecerán aquí</div>}
      <button className="primary" disabled={!canExport||busy} onClick={()=>void exportPack()}>{nativeBridge?'Añadir a WhatsApp':'Compartir paquete'}</button>
      {!nativeBridge&&<p className="hint">En el navegador se compartirán o descargarán los WebP. La instalación automática requiere la app móvil.</p>}
    </section>
  </main>
}
