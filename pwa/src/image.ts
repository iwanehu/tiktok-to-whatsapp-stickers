export async function toWhatsAppWebP(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const canvas = document.createElement('canvas')
  canvas.width = 512; canvas.height = 512
  const ctx = canvas.getContext('2d')!
  ctx.clearRect(0, 0, 512, 512)
  const scale = Math.min(512 / bitmap.width, 512 / bitmap.height)
  const width = Math.round(bitmap.width * scale)
  const height = Math.round(bitmap.height * scale)
  ctx.drawImage(bitmap, (512 - width) / 2, (512 - height) / 2, width, height)
  bitmap.close()
  const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/webp', .86))
  if (!blob) throw new Error('El navegador no pudo convertir la imagen a WebP.')
  return blob
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
