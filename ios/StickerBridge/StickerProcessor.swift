import UIKit
import SDWebImage
import SDWebImageWebPCoder

struct StickerError: LocalizedError {
    let text: String
    var errorDescription: String? { text }
    init(_ text: String) { self.text = text }
}
struct ProcessedSticker {
    let data: Data
    let animated: Bool
    let tray: Data
}
enum StickerProcessor {
    static func canvas(_ image: UIImage, side: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let scale = min(side / image.size.width, side / image.size.height)
            let size = CGSize(width: max(1,image.size.width * scale), height: max(1,image.size.height * scale))
            image.draw(in: CGRect(x: (side-size.width)/2, y: (side-size.height)/2, width: size.width, height: size.height))
        }
    }
    static func process(_ data: Data) throws -> ProcessedSticker {
        guard !data.isEmpty, data.count <= 10*1024*1024 else { throw StickerError("Archivo vacío o demasiado grande.") }
        let coder = SDImageWebPCoder.shared
        var frames: [SDImageFrame] = []
        var total: TimeInterval = 0
        if coder.canDecode(from: data) {
            guard let decoder = SDImageWebPCoder(animatedImageData: data, options: nil), decoder.animatedImageFrameCount > 0, decoder.animatedImageFrameCount <= 300 else { throw StickerError("WebP inválido o demasiado grande.") }
            var pixels: CGFloat = 0
            for index in 0..<decoder.animatedImageFrameCount {
                guard let image = decoder.animatedImageFrame(at: index) else { throw StickerError("No se pudo leer la animación.") }
                pixels += image.size.width * image.size.height
                guard pixels <= 24_000_000 else { throw StickerError("Animación demasiado grande.") }
                let duration = decoder.animatedImageDuration(at: index)
                if decoder.animatedImageFrameCount > 1 {
                    guard duration >= 0.008 else { throw StickerError("Fotograma inferior a 8 ms.") }
                    total += duration
                    guard total <= 10 else { throw StickerError("La animación supera 10 segundos; no se recorta automáticamente.") }
                }
                frames.append(SDImageFrame(image: canvas(image,side:512), duration: duration))
            }
        } else {
            let png = data.starts(with: [0x89,0x50,0x4e,0x47])
            let jpeg = data.starts(with: [0xff,0xd8])
            guard png || jpeg, !(png && data.range(of: Data("acTL".utf8)) != nil), let image = UIImage(data:data), image.size.width*image.size.height <= 16_000_000 else { throw StickerError("Formato no compatible. No se convierte una animación en imagen fija.") }
            frames = [SDImageFrame(image:canvas(image,side:512),duration:0)]
        }
        let animated = frames.count > 1, limit = frames.count > 1 ? 500*1024 : 100*1024
        guard let first = frames.first, let tray = canvas(first.image,side:96).pngData(), tray.count <= 50*1024 else { throw StickerError("No se pudo crear el icono.") }
        for quality in [0.85,0.65,0.45,0.25,0.05] {
            let options: [SDImageCoderOption:Any] = [.encodeCompressionQuality:quality]
            let output = animated ? coder.encodedData(with:frames,loopCount:0,format:.webP,options:options) : coder.encodedData(with:first.image,format:.webP,options:options)
            if let output = output, !output.isEmpty, output.count <= limit { return ProcessedSticker(data:output,animated:animated,tray:tray) }
        }
        throw StickerError("No se pudo reducir el sticker al tamaño permitido.")
    }
}
