# Sticker Bridge PWA

Primera fase multiplataforma. Convierte imágenes localmente a WebP 512×512, organiza paquetes de 3–30 stickers y usa Web Share o descarga como alternativa web.

```bash
npm install
npm run dev
npm run build
```

La web no lee la sesión privada ni el DOM de TikTok. El usuario abre TikTok y comparte/guarda imágenes explícitamente. La instalación directa en WhatsApp se realizará mediante un plugin `StickerBridge` específico para Android/iOS; `App.tsx` ya detecta ese contrato.
