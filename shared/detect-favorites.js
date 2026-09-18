(async () => {
  if (window.__sbScanning) return;
  window.__sbScanning = true;
  window.__sbScanResult = null;
  const found = new Set();
  const visible = el => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden';
  // Deliberately exclude chat history. A sticker panel must be open.
  const panels = [...document.querySelectorAll('[data-e2e*="sticker"], [class*="StickerPanel"], [class*="StickerPicker"], [role="dialog"]')]
    .filter(el => visible(el) && el.querySelector('img') && !el.closest('[data-e2e="chat-message"]'));
  const panel = panels.sort((a,b) => b.querySelectorAll('img').length-a.querySelectorAll('img').length)[0];
  const scan = () => {
    if (!panel) return;
    panel.querySelectorAll('img').forEach(img => {
      if (/avatar|profile/i.test(`${img.alt} ${img.className}`)) return;
      const raw = img.getAttribute('data-original') || img.getAttribute('data-src') || img.currentSrc || img.src;
      try { const u = new URL(raw, location.href); if (u.protocol === 'https:') found.add(u.href); } catch {}
    });
  };
  try {
    if (!panel) throw Error('Abre el panel de favoritos. No se ha encontrado un panel compatible en esta página de TikTok.');
    const scroller = [panel, ...panel.querySelectorAll('*')].find(el => el.scrollHeight > el.clientHeight + 5 && /auto|scroll/.test(getComputedStyle(el).overflowY));
    const initial = scroller?.scrollTop;
    let stable = 0;
    try {
      if (scroller) scroller.scrollTop = 0;
      for (let n=0;n<120;n++) {
        await new Promise(r=>setTimeout(r,250)); scan();
        if (!scroller || found.size >= 120) break;
        const before = scroller.scrollTop;
        scroller.scrollTop += Math.max(50,scroller.clientHeight * .7);
        stable = scroller.scrollTop === before ? stable+1 : 0;
        if (stable >= 4) break;
      }
    } finally { if (scroller) scroller.scrollTop = initial; }
    window.__sbScanResult = {urls:[...found].slice(0,120), warning:'Solo se detectan recursos cargados por el panel abierto. TikTok puede exponer miniaturas en lugar del original.'};
  } catch(e) { window.__sbScanResult = {error:e.message || String(e)}; }
  finally { window.__sbScanning = false; }
})();
