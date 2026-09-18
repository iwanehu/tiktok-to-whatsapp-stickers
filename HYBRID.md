# Sticker Bridge híbrido

## Implementado
- Interfaz React compartida incluida localmente en ambos contenedores.
- TikTokBridge Kotlin/Swift abre TikTok sin exponerle el puente de la interfaz.
- Detección del panel visible, scroll gradual, URLs HTTPS y deduplicación por bytes.
- Conversión WebP con transparencia, animación y límites de WhatsApp: 512×512,
  estáticos hasta 100 KB, animados hasta 500 KB, duración hasta 10 segundos.
- Selección por tipo y paquetes de 3–30 elementos sin mezclar tipos.
- Android: ContentProvider e intent oficial; comprobación del resultado.
- iOS: portapapeles local con caducidad y esquema oficial; confirmación manual.
- Temporales de Android eliminados en finally; iOS procesa en memoria.
  Android conserva el paquete que expone su ContentProvider. La limpieza no borra
  la sesión de TikTok ni archivos ajenos a esta importación.

## Límites y estado de validación
El detector es experimental y requiere prueba con sesión real. No hay garantía
 de que TikTok web exponga Favoritos ni archivos originales: algunas URLs son
miniaturas. No se adivinan URLs ni se cambia su extensión para fingir originales.
No se considera un listado de imágenes como prueba de acceso a toda la colección.
El login se realiza en TikTok y la app no lee ni registra credenciales; WebView/WKWebView
sí conservan los datos de sesión gestionados por el navegador.

La app rechaza animaciones demasiado largas/pesadas y formatos animados distintos
 de WebP, en vez de aplanarlos o recortarlos sin consentimiento. Si se mezclan tipos,
se exportan por separado. Los formatos o imágenes rechazados se cuentan.

## Android
```
npm ci --prefix pwa
npm run build --prefix pwa
node scripts/sync-web.mjs
./gradlew testDebugUnitTest assembleDebug
```
Requiere JDK 17 y SDK Android 35. CI genera el APK debug.

## iOS
En macOS con Xcode, CocoaPods y XcodeGen:
```
npm ci --prefix pwa
npm run build --prefix pwa
node scripts/sync-web.mjs
cd ios
xcodegen generate
pod install
open StickerBridge.xcworkspace
```
Selecciona tu equipo de firma para instalarlo en un iPhone físico. El flujo de
WhatsApp debe probarse en dispositivo; CI solo compila para simulador.

## Verificación manual necesaria
1. Abrir TikTok, iniciar sesión y comprobar que aparece el panel de favoritos.
2. Importar un original transparente y uno animado; comparar todos sus fotogramas.
3. Probar selección, duplicados, errores de red y cancelación.
4. Crear paquetes independientes de 3 estáticos y 3 animados.
5. Cancelar en WhatsApp: la selección debe conservarse.
6. Añadir en WhatsApp y volver: Android confirma; iOS pide confirmación del usuario.
7. Reiniciar las apps y comprobar que los paquetes siguen disponibles.

Fuentes: https://github.com/WhatsApp/stickers y
https://github.com/SDWebImage/SDWebImageWebPCoder .
