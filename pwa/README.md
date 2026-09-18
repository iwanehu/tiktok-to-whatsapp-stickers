# Sticker Bridge: interfaz compartida

`npm ci && npm run build` genera la interfaz para web, Android e iOS.
Desde la raíz ejecuta `node scripts/sync-web.mjs` antes de compilar los móviles.

El navegador permite convertir y compartir imágenes estáticas. Los WebP animados
no se aplanan: se rechazan en esta ruta y se procesan desde el importador nativo.
La web no accede a favoritos privados ni instala paquetes por sí sola.

El contrato nativo utiliza mensajes `{id,method,payload}` y respuestas
`window.__stickerReply({id,result,error})`. TikTok se abre en un WebView separado,
sin ese puente. No se inspeccionan contraseñas, formularios ni cookies.

`importTikTok` devuelve los WebP procesados (base64, nombre, tipo). `exportPack`
recibe entre 3 y 30 WebP del mismo tipo, nombre y autor. Android devuelve
`confirmed` o `cancelled`; iOS solo `handed_off` o `cancelled`. Abrir WhatsApp
no equivale a una importación confirmada.
