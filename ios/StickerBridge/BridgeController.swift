import UIKit
import WebKit
import CryptoKit

final class BridgeController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate {
    private var web: WKWebView!
    private var busy=false
    override func viewDidLoad() {
        super.viewDidLoad()
        let config=WKWebViewConfiguration();config.userContentController.add(self,name:"stickerBridge")
        web=WKWebView(frame:.zero,configuration:config);web.navigationDelegate=self;web.translatesAutoresizingMaskIntoConstraints=false
        view.addSubview(web);NSLayoutConstraint.activate([web.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),web.bottomAnchor.constraint(equalTo:view.bottomAnchor),web.leadingAnchor.constraint(equalTo:view.leadingAnchor),web.trailingAnchor.constraint(equalTo:view.trailingAnchor)])
        guard let url=Bundle.main.url(forResource:"index",withExtension:"html",subdirectory:"web") else { return }
        web.loadFileURL(url,allowingReadAccessTo:url.deletingLastPathComponent())
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy)->Void) { decisionHandler(action.request.url?.isFileURL == true ? .allow : .cancel) }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, message.frameInfo.request.url?.isFileURL == true, let body=message.body as? [String:Any],let id=body["id"] as? String,id.count<=64,let method=body["method"] as? String else { return }
        guard !busy else { reply(id,error:"Termina la operación anterior.");return };busy=true
        if method == "importTikTok" {
            let importer=TikTokBridge();importer.modalPresentationStyle = .fullScreen
            importer.completion={ [weak self] result in self?.busy=false;switch result { case .success(let value):self?.reply(id,result:value);case .failure(let error):self?.reply(id,error:error.localizedDescription) } }
            present(importer,animated:true)
        } else if method == "exportPack", let payload=body["payload"] as? [String:Any] {
            Task { @MainActor in
                defer { busy=false }
                do { let pack=try await Task.detached(priority:.userInitiated) { try Self.preparePack(payload) }.value
                    let url=URL(string:"whatsapp://stickerPack")!
                    guard UIApplication.shared.canOpenURL(url) else { throw StickerError("Instala WhatsApp para añadir el paquete.") }
                    let data=try JSONSerialization.data(withJSONObject:pack)
                    UIPasteboard.general.setItems([["net.whatsapp.third-party.sticker-pack":data]],options:[.localOnly:true,.expirationDate:Date(timeIntervalSinceNow:300)])
                    let opened=await UIApplication.shared.open(url)
                    reply(id,result:["status":opened ? "handed_off" : "cancelled"])
                } catch { reply(id,error:error.localizedDescription) }
            }
        } else { busy=false;reply(id,error:"Operación desconocida.") }
    }
    private func reply(_ id:String,result:[String:Any]?=nil,error:String?=nil) {
        var object:[String:Any]=["id":id];if let error=error { object["error"]=error } else { object["result"]=result ?? [:] }
        guard let data=try? JSONSerialization.data(withJSONObject:object),let json=String(data:data,encoding:.utf8) else { return }
        web.evaluateJavaScript("window.__stickerReply && window.__stickerReply(\(json))")
    }
    private static func preparePack(_ payload:[String:Any]) throws -> [String:Any] {
        guard let name=payload["name"] as? String,!name.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,name.count<=128,let author=payload["author"] as? String,!author.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,author.count<=128,let items=payload["stickers"] as? [[String:Any]],(3...30).contains(items.count) else { throw StickerError("Paquete inválido: selecciona 3–30 stickers e indica nombre y autor.") }
        var stickers:[[String:Any]]=[],type:Bool?,tray:Data?,hashes=Set<String>()
        for item in items {
            guard let raw=item["base64"] as? String,raw.count<=700000,let data=Data(base64Encoded:raw),let expected=item["animated"] as? Bool else { throw StickerError("Sticker inválido.") }
            let hash=SHA256.hash(data:data).map { String(format:"%02x",$0) }.joined()
            guard hashes.insert(hash).inserted else { throw StickerError("Hay stickers duplicados.") }
            let processed=try StickerProcessor.process(data)
            guard processed.animated == expected,type == nil || type == processed.animated else { throw StickerError("Separa los animados de los estáticos.") }
            type=processed.animated;tray=tray ?? processed.tray
            stickers.append(["image_data":processed.data.base64EncodedString(),"emojis":["✨"]])
        }
        return ["identifier":"sb_"+UUID().uuidString,"name":name,"publisher":author,"tray_image":tray!.base64EncodedString(),"animated_sticker_pack":type ?? false,"stickers":stickers]
    }
}
