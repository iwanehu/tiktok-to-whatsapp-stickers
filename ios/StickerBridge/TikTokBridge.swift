import UIKit
import WebKit
import CryptoKit

final class StickerDownloader: NSObject, URLSessionTaskDelegate {
    static func allowed(_ url: URL) -> Bool {
        let suffixes = ["tiktok.com","tiktokcdn.com","tiktokcdn-us.com","byteoversea.com","ibytedtos.com","muscdn.com","byteimg.com"]
        guard url.scheme == "https", url.user == nil, url.password == nil, url.port == nil || url.port == 443, let host=url.host?.lowercased() else { return false }
        return suffixes.contains { host == $0 || host.hasSuffix("."+$0) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url.map(Self.allowed) == true ? request : nil)
    }
    func download(_ url: URL) async throws -> Data {
        guard Self.allowed(url) else { throw StickerError("Origen no permitido.") }
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false; config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 40
        let session=URLSession(configuration:config,delegate:self,delegateQueue:nil)
        defer { session.invalidateAndCancel() }
        let (bytes,response) = try await session.bytes(from:url)
        guard let response=response as? HTTPURLResponse, response.statusCode == 200, response.expectedContentLength <= 10*1024*1024 else { throw StickerError("No se pudo descargar el original.") }
        var data=Data()
        for try await byte in bytes { data.append(byte); if data.count > 10*1024*1024 { throw StickerError("Archivo demasiado grande.") } }
        return data
    }
}
final class TikTokBridge: UIViewController, WKNavigationDelegate {
    private let web = WKWebView(frame:.zero)
    private let status=UILabel(), scan=UIButton(type:.system)
    private var task: Task<Void,Never>?
    var completion: ((Result<[String:Any],Error>)->Void)?
    override func viewDidLoad() {
        super.viewDidLoad();view.backgroundColor = .systemBackground
        web.navigationDelegate=self
        web.customUserAgent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15"
        status.numberOfLines=0;status.text="Inicia sesión en TikTok. Abre Mensajes → Stickers → Favoritos."
        scan.setTitle("Detectar favoritos",for:.normal);scan.addTarget(self,action:#selector(detect),for:.touchUpInside)
        let close=UIButton(type:.system);close.setTitle("Volver sin importar",for:.normal);close.addTarget(self,action:#selector(cancel),for:.touchUpInside)
        let stack=UIStackView(arrangedSubviews:[status,close,scan,web]);stack.axis = .vertical;stack.spacing=8
        stack.translatesAutoresizingMaskIntoConstraints=false;view.addSubview(stack)
        NSLayoutConstraint.activate([stack.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),stack.bottomAnchor.constraint(equalTo:view.safeAreaLayoutGuide.bottomAnchor),stack.leadingAnchor.constraint(equalTo:view.leadingAnchor,constant:12),stack.trailingAnchor.constraint(equalTo:view.trailingAnchor,constant:-12)])
        web.load(URLRequest(url:URL(string:"https://www.tiktok.com/messages")!))
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy)->Void) {
        let url=action.request.url,host=url?.host ?? ""
        decisionHandler(url?.scheme == "https" && (host == "tiktok.com" || host.hasSuffix(".tiktok.com")) ? .allow : .cancel)
    }
    @objc private func cancel() { task?.cancel();completion?(.failure(StickerError("Importación cancelada.")));completion=nil;dismiss(animated:true) }
    @objc private func detect() {
        guard task == nil else { return };scan.isEnabled=false
        task = Task { @MainActor in
            defer { task=nil;scan.isEnabled=true }
            do {
                guard let url=Bundle.main.url(forResource:"detect-favorites",withExtension:"js") else { throw StickerError("Falta el detector.") }
                status.text="Recorriendo el panel abierto…"
                _ = try await web.evaluateJavaScript(try String(contentsOf:url) + "\nvoid 0;")
                var result:[String:Any]?
                for _ in 0..<150 {
                    try await Task.sleep(nanoseconds:300_000_000)
                    if let value=try await web.evaluateJavaScript("window.__sbScanResult || null") as? [String:Any] { result=value;break }
                }
                if let error=result?["error"] as? String { throw StickerError(error) }
                guard let urls=result?["urls"] as? [String] else { throw StickerError("El panel no respondió.") }
                var imported:[[String:Any]]=[], seen=Set<String>(),failed=0
                let downloader=StickerDownloader()
                for (index,value) in urls.prefix(120).enumerated() {
                    try Task.checkCancellation();status.text="Preparando \(index+1)/\(urls.count)…"
                    do {
                        guard let url=URL(string:value) else { continue }
                        let data=try await downloader.download(url)
                        let hash=SHA256.hash(data:data).map { String(format:"%02x",$0) }.joined()
                        guard seen.insert(hash).inserted else { continue }
                        let processed=try await Task.detached(priority:.userInitiated) { try StickerProcessor.process(data) }.value
                        imported.append(["name":"Sticker \(index+1)","base64":processed.data.base64EncodedString(),"animated":processed.animated])
                    } catch is CancellationError { throw CancellationError() } catch { failed += 1 }
                }
                guard !imported.isEmpty else { throw StickerError("No se encontraron originales compatibles. TikTok puede no exponerlos en este panel.") }
                completion?(.success(["stickers":imported,"warning":"\(failed) archivos no compatibles. Solo recursos expuestos por el panel; pueden ser miniaturas."]))
                completion=nil;dismiss(animated:true)
            } catch { status.text=error.localizedDescription }
        }
    }
}
