(async function () {
  if (window.__tiktokStickerScanRunning) return;
  window.__tiktokStickerScanRunning = true;

  const selectors = [
    'img[alt="sticker"]',
    '[data-e2e*="sticker"] img',
    '[data-e2e="chat-message"] img',
    '[data-e2e="chat-msg-list"] img',
    '.message-sticker img',
    '[class*="Sticker"] img',
    '[class*="sticker"] img'
  ];
  const excludedHints = ['avatar', 'profile', 'emoji-picker'];
  const collected = new Set();

  function isStickerImage(img) {
    const alt = (img.alt || '').toLowerCase();
    if (alt === 'sticker') return true;
    const context = `${img.className || ''} ${alt} ${img.closest('[class]')?.className || ''}`.toLowerCase();
    return context.includes('sticker') && !excludedHints.some((hint) => context.includes(hint));
  }

  function scan(root = document) {
    const images = new Set();
    selectors.forEach((selector) => {
      root.querySelectorAll?.(selector).forEach((img) => images.add(img));
    });
    images.forEach((img) => {
      const url = img.currentSrc || img.src;
      if (isStickerImage(img) && /^https?:\/\//.test(url)) collected.add(url);
    });
    AndroidBridge.onStickersFound(JSON.stringify([...collected]));
  }

  function stickerScore(element) {
    if (!element || element === document.body) return -1;
    const style = getComputedStyle(element);
    const scrollable = /(auto|scroll)/.test(style.overflowY) && element.scrollHeight > element.clientHeight;
    if (!scrollable) return -1;
    const stickerCount = selectors.reduce(
      (total, selector) => total + element.querySelectorAll(selector).length,
      0
    );
    return stickerCount * 100000 + Math.min(element.scrollHeight, 99999);
  }

  function findBestScroller() {
    return [...document.querySelectorAll('div, section, main, ul')]
      .map((element) => ({ element, score: stickerScore(element) }))
      .filter(({ score }) => score >= 0)
      .sort((a, b) => b.score - a.score)[0]?.element || null;
  }

  const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

  try {
    scan();
    const scroller = findBestScroller();
    if (scroller) {
      let unchangedRounds = 0;
      let previousHeight = -1;
      let previousCount = -1;
      for (let round = 0; round < 80 && unchangedRounds < 4; round += 1) {
        scroller.scrollTop = scroller.scrollHeight;
        scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
        await wait(450);
        scan(scroller);

        const unchanged = previousHeight === scroller.scrollHeight && previousCount === collected.size;
        unchangedRounds = unchanged ? unchangedRounds + 1 : 0;
        previousHeight = scroller.scrollHeight;
        previousCount = collected.size;
      }
    }
    scan();
    AndroidBridge.onScanFinished(collected.size);
  } catch (error) {
    AndroidBridge.onScanError(error?.message || String(error));
  } finally {
    window.__tiktokStickerScanRunning = false;
  }
})();
