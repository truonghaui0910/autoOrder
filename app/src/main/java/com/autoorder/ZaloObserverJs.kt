package com.autoorder

internal const val ZALO_OBSERVER_JS = """
(function() {
  if (window.__autoOrderInstalled) return;
  window.__autoOrderInstalled = true;

  try {
    if (!window.__autoOrderMobileView) {
      var vp = document.querySelector('meta[name=viewport]');
      if (!vp) {
        vp = document.createElement('meta');
        vp.name = 'viewport';
        document.head.appendChild(vp);
      }
      vp.setAttribute('content', 'width=1400, initial-scale=0.25, user-scalable=yes');
    }
  } catch (e) {}

  function snippet(s, max) {
    if (!s) return '';
    s = s.replace(/\s+/g, ' ').trim();
    max = max || 1000;
    return s.length > max ? s.substring(0, max) + '…' : s;
  }
  function classOf(node) {
    if (!node || !node.className) return '';
    return (typeof node.className === 'string') ? node.className
      : (node.className.baseVal || node.className.toString() || '');
  }

  function extractContactCard(it) {
    if (!it || !it.querySelector) return '';
    var card = it.querySelector('.contact-message__container') ||
               it.querySelector('.contact-card__container');
    if (!card) return '';
    var nameEl = card.querySelector('.contact-card__name-wrapper');
    var descEl = card.querySelector('.contact-card__description-wrapper');
    var nm = nameEl ? (nameEl.innerText || '').trim() : '';
    var ph = descEl ? (descEl.innerText || '').trim() : '';
    if (!nm && !ph) return '';
    if (nm && ph) return '[Liên hệ: ' + nm + '] ' + ph;
    return '[Liên hệ] ' + (nm || ph);
  }

  function classifyConv(item) {
    var dataId = item.getAttribute('data-id') || '';
    if (dataId === 'div_TabMsg_ThrdChFileXFER') return 'self-file';
    var animId = item.getAttribute('anim-data-id') || '';
    if (animId.charAt(0) === 'g') return 'group';
    if (!animId) return 'other';
    return '1on1';
  }

  function isUnreadItem(item) {
    if (item.querySelector('.z-conv-message.--unread')) return true;
    if (item.querySelector('.conv-action__unread-v2')) return true;
    if (item.querySelector('.z-noti-badge.--counter')) return true;
    return false;
  }

  var lastSeen = {};

  function checkConvForNew(item) {
    if (!item || !item.matches) return;
    if (!item.matches('.msg-item')) {
      item = item.closest && item.closest('.msg-item');
      if (!item) return;
    }
    if (classifyConv(item) !== '1on1') return;
    if (!isUnreadItem(item)) return;

    var animId = item.getAttribute('anim-data-id') || '';
    var nameInner = item.querySelector('.conv-item-title__name .truncate');
    var nameEl = nameInner || item.querySelector('.conv-item-title__name');
    var name = nameEl ? snippet((nameEl.innerText || '').trim(), 100) : '';
    var prevEl = item.querySelector('.z-conv-message__preview-message');
    var preview = prevEl ? snippet((prevEl.innerText || '').trim(), 500) : '';
    var timeEl = item.querySelector('.preview-time');
    var timeText = timeEl ? snippet((timeEl.innerText || '').trim(), 30) : '';
    if (!preview) return;

    if (lastSeen[animId] === preview) return;
    lastSeen[animId] = preview;

    AutoOrderBridge.onNewMessage(animId, name, preview, timeText);
  }

  var observer = new MutationObserver(function(muts) {
    var checked = new Set();
    function tryCheck(node) {
      if (!node || node.nodeType !== 1) return;
      var it = node.matches && node.matches('.msg-item') ? node :
               (node.closest && node.closest('.msg-item'));
      if (it && !checked.has(it)) {
        checked.add(it);
        checkConvForNew(it);
      }
      if (node.querySelectorAll) {
        var inner = node.querySelectorAll('.msg-item');
        for (var i = 0; i < inner.length; i++) {
          if (!checked.has(inner[i])) {
            checked.add(inner[i]);
            checkConvForNew(inner[i]);
          }
        }
      }
    }
    muts.forEach(function(m) {
      tryCheck(m.target);
      if (m.addedNodes) m.addedNodes.forEach(tryCheck);
    });
  });
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class']
  });

  window.__autoOrderExtractSelected = function() {
    var selRel = document.querySelector('.conv-rel.selected');
    var peerName = '';
    var animId = '';
    var avatarUrl = '';
    if (selRel) {
      var item = (selRel.closest && selRel.closest('.msg-item')) || selRel;
      animId = item.getAttribute && (item.getAttribute('anim-data-id') || '') || '';
      var nameInner = item.querySelector('.conv-item-title__name .truncate');
      var nameEl = nameInner || item.querySelector('.conv-item-title__name');
      peerName = nameEl ? snippet((nameEl.innerText || '').trim(), 100) : '';
      var imgEl = item.querySelector('.zavatar img');
      if (imgEl && imgEl.src) avatarUrl = imgEl.src;
    }
    var container = document.getElementById('messageViewScroll') ||
                    document.getElementById('messageViewContainer');
    if (!container) {
      AutoOrderBridge.onConversation(animId, peerName, avatarUrl, '[]');
      return;
    }
    var nodes = container.querySelectorAll('.chat-item');
    var arr = [];
    for (var i = 0; i < nodes.length; i++) {
      var it = nodes[i];
      var cls = ' ' + (classOf(it) || '') + ' ';
      var isMe = / me /.test(cls);
      var textEl = it.querySelector('[data-component=text-container]') ||
                   it.querySelector('.text-message__container');
      var text = textEl ? (textEl.innerText || '').trim() : '';
      if (!text) {
        var card = extractContactCard(it);
        if (card) text = card;
        else if (it.querySelector('.img-msg-v2') || it.querySelector('.photo-message-v2')) {
          text = '[hình ảnh]';
        }
      }
      if (!text) continue;
      arr.push({ from: isMe ? 'me' : 'them', text: snippet(text, 2000) });
    }
    AutoOrderBridge.onConversation(animId, peerName, avatarUrl, JSON.stringify(arr));
  };

  window.__autoOrderFetchById = function(animId, dayStartMs, dayEndMs) {
    if (!animId) {
      AutoOrderBridge.onCheckoutMessages('', 'NO_ID', '[]');
      return;
    }
    var items = document.querySelectorAll('.msg-item');
    var target = null;
    for (var i = 0; i < items.length; i++) {
      if (items[i].getAttribute('anim-data-id') === animId) { target = items[i]; break; }
    }
    if (!target) {
      AutoOrderBridge.onCheckoutMessages(animId, 'NOT_FOUND', '[]');
      return;
    }
    var clickable = target.querySelector('.conv-rel') || target;
    try { clickable.click(); } catch (e) {}

    function tsOf(node) {
      if (!node) return NaN;
      var id = node.id || '';
      var m = id.match(/bb_msg_id_(\d+)/);
      if (m) return parseInt(m[1], 10);
      var inner = node.querySelector && node.querySelector('[id^="bb_msg_id_"]');
      if (inner && inner.id) {
        var m2 = inner.id.match(/bb_msg_id_(\d+)/);
        if (m2) return parseInt(m2[1], 10);
      }
      return NaN;
    }
    function isMeNode(it) {
      var cls = ' ' + (classOf(it) || '') + ' ';
      if (/ me /.test(cls)) return true;
      var inner = it.querySelector && it.querySelector('.chat-body.me, .me');
      return !!inner;
    }
    function getContainer() {
      return document.getElementById('messageViewScroll') ||
             document.getElementById('messageViewContainer');
    }
    function getNodes(c) {
      return c ? c.querySelectorAll('.chat-item') : [];
    }
    function findScrollable(c) {
      var nodes = getNodes(c);
      var probe = nodes && nodes.length ? nodes[0] : c;
      var el = probe;
      while (el && el !== document.body) {
        try {
          var cs = window.getComputedStyle(el);
          var ovy = cs.overflowY;
          if ((ovy === 'auto' || ovy === 'scroll') && el.scrollHeight > el.clientHeight + 4) {
            return el;
          }
        } catch (e) {}
        el = el.parentElement;
      }
      return c;
    }
    function scrollToTop(scroller, c) {
      try { scroller.scrollTop = 0; } catch (e) {}
      try {
        var nodes = getNodes(c);
        if (nodes && nodes.length) {
          nodes[0].scrollIntoView({ block: 'start', inline: 'nearest' });
        }
      } catch (e) {}
      try {
        scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
      } catch (e) {}
    }
    function earliestTs(nodes) {
      for (var i = 0; i < nodes.length; i++) {
        var t = tsOf(nodes[i]);
        if (!isNaN(t)) return t;
      }
      return NaN;
    }

    var waitOpen = 0;
    function waitForOpen() {
      waitOpen++;
      var c = getContainer();
      var nodes = getNodes(c);
      if (nodes.length === 0 && waitOpen < 25) {
        setTimeout(waitForOpen, 400);
        return;
      }
      if (!c) {
        AutoOrderBridge.onCheckoutMessages(animId, 'NO_CONTAINER', '[]');
        return;
      }
      var scroller = findScrollable(c);
      try {
        AutoOrderBridge.onDump('checkout-scroller', '',
          'tag=' + (scroller && scroller.tagName) +
          ' id=' + (scroller && scroller.id) +
          ' cls=' + (scroller && (typeof scroller.className === 'string' ? scroller.className : '')) +
          ' h=' + (scroller && scroller.scrollHeight) + '/' + (scroller && scroller.clientHeight), '');
      } catch (e) {}
      autoScroll(c, scroller, 0, -1, 0);
    }

    function autoScroll(c, scroller, attempts, lastCount, stableCount) {
      var nodes = getNodes(c);
      var eTs = earliestTs(nodes);
      if (!isNaN(eTs) && eTs < dayStartMs) { finish(nodes); return; }
      if (attempts > 80) { finish(nodes); return; }
      if (nodes.length === lastCount) {
        stableCount++;
        if (stableCount >= 6) { finish(nodes); return; }
      } else {
        stableCount = 0;
      }
      scrollToTop(scroller, c);
      setTimeout(function() {
        autoScroll(c, scroller, attempts + 1, nodes.length, stableCount);
      }, 700);
    }

    function finish(nodes) {
      var TARGET_SENDER = 'ĐH Tuấn ck Đù';
      var arr = [];
      var lastNonMeSender = '';
      var dbgTotal = nodes.length, dbgWithTs = 0, dbgInRange = 0, dbgFromMe = 0;
      var dbgReplies = 0, dbgRepMatched = 0, dbgNonMe = 0, dbgWithQuote = 0;
      var dbgSenders = {};
      var dbgFirstTs = NaN, dbgLastTs = NaN;
      function norm(s) {
        return (s || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
      }
      function stripTail(s) {
        return s.replace(/[\s\.…]+$/g, '');
      }
      function matchQuote(meText, qKey) {
        if (!meText || !qKey) return false;
        if (meText === qKey) return true;
        var a = stripTail(meText), b = stripTail(qKey);
        if (a === b) return true;
        if (b.length >= 12 && a.indexOf(b) === 0) return true;
        if (b.length >= 20 && a.indexOf(b) >= 0) return true;
        if (a.length >= 12 && b.indexOf(a) === 0) return true;
        return false;
      }
      for (var i = 0; i < nodes.length; i++) {
        var it = nodes[i];
        var t = tsOf(it);
        if (isNaN(t)) continue;
        dbgWithTs++;
        if (isNaN(dbgFirstTs)) dbgFirstTs = t;
        dbgLastTs = t;
        if (t < dayStartMs || t > dayEndMs) continue;
        dbgInRange++;
        if (isMeNode(it)) {
          var textEl = it.querySelector('[data-component=text-container]') ||
                       it.querySelector('.text-message__container');
          var text = textEl ? (textEl.innerText || '').trim() : '';
          if (!text) {
            var card = extractContactCard(it);
            if (card) text = card;
            else if (it.querySelector('.img-msg-v2') || it.querySelector('.photo-message-v2')) {
              text = '[hình ảnh]';
            }
          }
          if (!text) continue;
          dbgFromMe++;
          arr.push({ from: 'me', text: snippet(text, 2000), time: String(t), replies: [] });
        } else {
          dbgNonMe++;
          var nameEl = it.querySelector('.message-sender-name-content .truncate') ||
                       it.querySelector('.message-sender-name-content');
          var senderName = nameEl ? norm(nameEl.innerText || '') : '';
          if (senderName) lastNonMeSender = senderName;
          else senderName = lastNonMeSender;
          if (senderName) dbgSenders[senderName] = (dbgSenders[senderName] || 0) + 1;
          if (norm(senderName) !== norm(TARGET_SENDER)) continue;
          var quoteEl = it.querySelector('.message-quote-fragment__description');
          if (!quoteEl) continue;
          dbgWithQuote++;
          var quoteKey = snippet((quoteEl.innerText || '').trim(), 2000);
          if (!quoteKey) continue;
          var bodyEl = it.querySelector('[data-component=text-container]');
          var rText = '';
          if (bodyEl) {
            var bClone = bodyEl.cloneNode(true);
            var mentions = bClone.querySelectorAll('.mention-name');
            for (var mi = 0; mi < mentions.length; mi++) {
              if (mentions[mi].parentNode) mentions[mi].parentNode.removeChild(mentions[mi]);
            }
            rText = (bClone.innerText || '').trim();
          } else {
            var holder = it.querySelector('.text-message__container');
            if (holder) {
              var clone = holder.cloneNode(true);
              var q = clone.querySelector('.message-quote-fragment__container');
              if (q && q.parentNode) q.parentNode.removeChild(q);
              var mentions2 = clone.querySelectorAll('.mention-name');
              for (var mj = 0; mj < mentions2.length; mj++) {
                if (mentions2[mj].parentNode) mentions2[mj].parentNode.removeChild(mentions2[mj]);
              }
              rText = (clone.innerText || '').trim();
            }
          }
          if (!rText) {
            if (it.querySelector('.img-msg-v2') || it.querySelector('.photo-message-v2')) {
              rText = '[hình ảnh]';
            }
          }
          if (!rText) continue;
          dbgReplies++;
          var matched = false;
          for (var k = arr.length - 1; k >= 0; k--) {
            if (matchQuote(arr[k].text, quoteKey)) {
              arr[k].replies.push({ text: snippet(rText, 2000), time: String(t) });
              dbgRepMatched++;
              matched = true;
              break;
            }
          }
          if (!matched) {
            try {
              AutoOrderBridge.onDump('checkout-nomatch', '',
                'q=' + quoteKey.substring(0, 120) +
                ' | r=' + rText.substring(0, 80), '');
            } catch (e) {}
          }
        }
      }
      try {
        var senderList = [];
        for (var sk in dbgSenders) {
          if (dbgSenders.hasOwnProperty(sk)) senderList.push(sk + '(' + dbgSenders[sk] + ')');
        }
        AutoOrderBridge.onDump('checkout-debug', '',
          'total=' + dbgTotal + ' withTs=' + dbgWithTs +
          ' inRange=' + dbgInRange + ' fromMe=' + dbgFromMe +
          ' nonMe=' + dbgNonMe + ' withQuote=' + dbgWithQuote +
          ' replies=' + dbgReplies + ' matched=' + dbgRepMatched +
          ' senders=[' + senderList.join(', ') + ']' +
          ' target=[' + TARGET_SENDER + ']' +
          ' firstTs=' + dbgFirstTs + ' lastTs=' + dbgLastTs +
          ' dayStart=' + dayStartMs + ' dayEnd=' + dayEndMs, '');
      } catch (e) {}
      AutoOrderBridge.onCheckoutMessages(animId, 'OK', JSON.stringify(arr));
    }

    setTimeout(waitForOpen, 700);
  };

  function reportConvItem(item) {
    var animId = item.getAttribute('anim-data-id') || '';
    if (!animId) return;
    var dataId = item.getAttribute('data-id') || '';
    if (dataId === 'div_TabMsg_ThrdChFileXFER') return;
    var isGroup = animId.charAt(0) === 'g';
    var nameInner = item.querySelector('.conv-item-title__name .truncate');
    var nameEl = nameInner || item.querySelector('.conv-item-title__name');
    var name = nameEl ? snippet((nameEl.innerText || '').trim(), 200) : '';
    if (!name) return;
    var avatarUrl = '';
    var imgEl = item.querySelector('.zavatar img');
    if (imgEl && imgEl.src) avatarUrl = imgEl.src;
    var timeEl = item.querySelector('.preview-time');
    var timeText = timeEl ? snippet((timeEl.innerText || '').trim(), 30) : '';
    AutoOrderBridge.onConvItem(animId, name, avatarUrl, isGroup, timeText);
  }

  window.__autoOrderScanConvs = function() {
    var items = document.querySelectorAll('.msg-item');
    for (var i = 0; i < items.length; i++) reportConvItem(items[i]);
  };

  function parseHoursAgoFromText(text) {
    var t = (text || '').trim().toLowerCase();
    if (!t) return Infinity;
    if (t.indexOf('vài giây') >= 0 || t === 'bây giờ') return 0;
    var m = t.match(/(\d+)\s*giây/); if (m) return parseInt(m[1], 10) / 3600;
    m = t.match(/(\d+)\s*phút/); if (m) return parseInt(m[1], 10) / 60;
    m = t.match(/(\d+)\s*giờ/); if (m) return parseInt(m[1], 10);
    if (t.indexOf('hôm qua') >= 0) return 36;
    return Infinity;
  }

  function findSidebarScrollable() {
    var items = document.querySelectorAll('.msg-item');
    if (!items.length) return null;
    var el = items[0].parentElement;
    while (el && el !== document.body) {
      try {
        var cs = window.getComputedStyle(el);
        var ovy = cs.overflowY;
        if ((ovy === 'auto' || ovy === 'scroll') && el.scrollHeight > el.clientHeight + 4) {
          return el;
        }
      } catch (e) {}
      el = el.parentElement;
    }
    return null;
  }

  window.__autoOrderScanRecent = function(hours) {
    var cutoff = (typeof hours === 'number' && hours > 0) ? hours : 24;
    var seen = {};

    function reportRendered() {
      var items = document.querySelectorAll('.msg-item');
      var oldest = -1;
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var animId = it.getAttribute('anim-data-id') || '';
        if (!seen[animId]) {
          seen[animId] = 1;
          reportConvItem(it);
        }
        if (classifyConv(it) !== '1on1') continue;
        var te = it.querySelector('.preview-time');
        var tt = te ? (te.innerText || '').trim() : '';
        var h = parseHoursAgoFromText(tt);
        if (h > oldest) oldest = h;
      }
      return oldest;
    }

    var scroller = findSidebarScrollable();
    var attempts = 0;
    var stableCount = 0;
    var lastH = -1;

    function done(reason) {
      AutoOrderBridge.onDump('scan-recent', '',
        'reason=' + reason + ' attempts=' + attempts + ' cutoff=' + cutoff + 'h', '');
      try { AutoOrderBridge.onScanDone(); } catch (e) {}
    }

    function step() {
      attempts++;
      var oldest = reportRendered();
      if (oldest >= cutoff) { done('cutoff-reached oldest=' + oldest); return; }
      if (attempts > 200) { done('max-attempts'); return; }
      if (!scroller) { done('no-scroller'); return; }
      var prevH = scroller.scrollHeight;
      var prevTop = scroller.scrollTop;
      try {
        scroller.scrollTop = scroller.scrollHeight;
        scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
      } catch (e) {}
      setTimeout(function() {
        if (scroller.scrollHeight === prevH && scroller.scrollTop <= prevTop + 1) {
          stableCount++;
          if (stableCount >= 4) { reportRendered(); done('no-more-content'); return; }
        } else {
          stableCount = 0;
        }
        step();
      }, 600);
    }

    step();
  };

  window.__autoOrderDump = function() {
    AutoOrderBridge.onDump('dump-begin', location.href, '', '');
    var items = document.querySelectorAll('.msg-item');
    var counts = { '1on1': 0, group: 0, 'self-file': 0, other: 0 };
    var oneOnOneIdx = 0;
    items.forEach(function(item) {
      var kind = classifyConv(item);
      counts[kind] = (counts[kind] || 0) + 1;
      if (kind !== '1on1') return;
      var nameInner = item.querySelector('.conv-item-title__name .truncate');
      var nameEl = nameInner || item.querySelector('.conv-item-title__name');
      var name = nameEl ? snippet((nameEl.innerText || '').trim(), 100) : '(no-name)';
      var timeEl = item.querySelector('.preview-time');
      var lastTime = timeEl ? snippet((timeEl.innerText || '').trim(), 30) : '';
      var prevEl = item.querySelector('.z-conv-message__preview-message');
      var preview = prevEl ? snippet((prevEl.innerText || '').trim(), 120) : '';
      var unread = isUnreadItem(item);
      var animId = item.getAttribute('anim-data-id') || '';
      AutoOrderBridge.onDump(
        'conv-1on1#' + oneOnOneIdx, '',
        'name="' + name + '" unread=' + unread +
        ' lastTime="' + lastTime + '" preview="' + preview + '"' +
        ' animId="' + animId + '"', ''
      );
      oneOnOneIdx++;
    });
    AutoOrderBridge.onDump(
      'summary', '',
      'total=' + items.length +
      ' oneOnOne=' + (counts['1on1'] || 0) +
      ' group=' + (counts.group || 0) +
      ' selfFile=' + (counts['self-file'] || 0) +
      ' other=' + (counts.other || 0), ''
    );
    AutoOrderBridge.onDump('dump-end', '', '', '');
  };

  // Search-only helper: type query into Zalo's contact-search, click the matching
  // result, close the search panel. Does NOT scan convs — the caller chains that.
  // mode === 'phone' → ưu tiên click phần tử thứ 2 trong mục "Tin nhắn"
  //                   (.search-message__item), fallback về phần tử đầu nếu chỉ có 1.
  // mode === 'name' (default) → click phần tử đầu [id^="friend-item-"].
  window.__autoOrderSearch = function(query, mode) {
    function findInput() {
      return document.getElementById('contact-search-input');
    }
    function findCloseBtn() {
      var nodes = document.querySelectorAll('[data-translate-inner="STR_CLOSE"]');
      for (var i = 0; i < nodes.length; i++) {
        var btn = nodes[i].closest('.z--btn--v2');
        if (btn) return btn;
      }
      return null;
    }
    function setReactInputValue(input, value) {
      try {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(input, value);
      } catch (e) { input.value = value; }
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    function pickResult() {
      if (mode === 'phone') {
        var msgs = document.querySelectorAll('#searchResultList .search-message__item');
        if (msgs.length >= 2) return { el: msgs[1], id: 'msg#2' };
        if (msgs.length >= 1) return { el: msgs[0], id: 'msg#1' };
        return null;
      }
      var list = document.getElementById('searchResultList');
      if (!list) return null;
      var f = list.querySelector('[id^="friend-item-"]');
      if (f) return { el: f, id: f.id };
      var g = list.querySelector('[id^="group-item-"]');
      if (g) return { el: g, id: g.id };
      var others = list.querySelectorAll(
        '.search-friend__item, .search-group__item, .search-conv__item, .conv-rel, .msg-item'
      );
      for (var i = 0; i < others.length; i++) {
        var r = others[i].getBoundingClientRect();
        if (r.width > 0 && r.height > 0) return { el: others[i], id: others[i].id || ('other#' + i) };
      }
      var msgs2 = list.querySelectorAll('.search-message__item');
      if (msgs2.length) return { el: msgs2[0], id: 'msg#1' };
      return null;
    }
    function done(status, msg) {
      try { AutoOrderBridge.onSearchDone(status, msg || ''); } catch (e) {}
    }

    var input = findInput();
    if (!input) { done('NO_INPUT', ''); return; }

    try { input.focus(); } catch (e) {}
    setReactInputValue(input, query);

    var attempts = 0;
    var maxAttempts = 30;
    function poll() {
      attempts++;
      var pick = pickResult();
      if (pick) {
        var pickId = pick.id;
        try { pick.el.click(); } catch (e) {}
        try {
          var inner = pick.el.querySelector('.conv-item-title__name')
            || pick.el.querySelector('.search-message__item__content')
            || pick.el;
          inner.click();
        } catch (e) {}
        setTimeout(function() {
          var closeBtn = findCloseBtn();
          if (closeBtn) { try { closeBtn.click(); } catch (e) {} }
          setTimeout(function() { done('OK', pickId); }, 400);
        }, 700);
        return;
      }
      if (attempts >= maxAttempts) {
        var closeBtn = findCloseBtn();
        if (closeBtn) { try { closeBtn.click(); } catch (e) {} }
        done('NO_RESULT', '');
        return;
      }
      setTimeout(poll, 200);
    }
    setTimeout(poll, 400);
  };

  // Search SĐT → mở conv → đọc N tin cuối từ phía khách. Một call duy nhất,
  // poll DOM thay vì delay cứng. Trả status: OK / NO_INPUT / NO_RESULT /
  // NO_CONV_OPENED / EMPTY / NO_CONTAINER.
  window.__autoOrderCheckPaymentByPhone = function(phone, limit) {
    var lim = (typeof limit === 'number' && limit > 0) ? limit : 10;
    function done(status, animId, peerName, avatarUrl, msgsJson) {
      try {
        AutoOrderBridge.onPaymentCheck(status, animId || '', peerName || '',
          avatarUrl || '', msgsJson || '[]');
      } catch (e) {}
    }
    function findInput() { return document.getElementById('contact-search-input'); }
    function findCloseBtn() {
      var nodes = document.querySelectorAll('[data-translate-inner="STR_CLOSE"]');
      for (var i = 0; i < nodes.length; i++) {
        var btn = nodes[i].closest('.z--btn--v2');
        if (btn) return btn;
      }
      return null;
    }
    function setReactInputValue(input, value) {
      try {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(input, value);
      } catch (e) { input.value = value; }
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    // Ngưỡng đợi (theo số attempts × 200ms = thời gian).
    var WAIT_MSGTAB_FULL = 25;   // ~5s đợi item thứ 2 trong msg-tab.
    var WAIT_NO_MSGTAB = 30;     // ~6s đợi label STR_TAB_MESSAGE_NUM xuất hiện
                                  // (chỉ áp dụng nếu chưa từng thấy label).

    var sawMsgTab = false;

    function pickFriend(attempts) {
      // Ưu tiên TUYỆT ĐỐI: phần tử thứ 2 NẰM TRONG section STR_TAB_MESSAGE_NUM.
      // Friend-item (kết quả khớp SĐT) thường render sớm hơn label msg-tab,
      // nhưng nếu user search SĐT thì kỳ vọng vào conv chứ không phải contact
      // info. Dùng "sticky flag" sawMsgTab: hễ thấy label rồi thì KHÔNG bao giờ
      // fallback sang friend-item nữa, đợi đến khi msg-tab populate.
      var tabLabel = document.querySelector('[data-translate-inner="STR_TAB_MESSAGE_NUM"]');
      if (tabLabel) sawMsgTab = true;

      if (sawMsgTab) {
        if (tabLabel) {
          var allMsgs = document.querySelectorAll('#searchResultList .search-message__item');
          var msgsInTab = [];
          for (var i = 0; i < allMsgs.length; i++) {
            // 4 = Node.DOCUMENT_POSITION_FOLLOWING
            if (tabLabel.compareDocumentPosition(allMsgs[i]) & 4) {
              msgsInTab.push(allMsgs[i]);
            }
          }
          if (msgsInTab.length >= 2) return { el: msgsInTab[1], kind: 'msg-tab', id: 'msgTab#2' };
          if (msgsInTab.length === 1 && attempts >= WAIT_MSGTAB_FULL) {
            return { el: msgsInTab[0], kind: 'msg-tab', id: 'msgTab#1' };
          }
        }
        // Đã thấy msg-tab nhưng items chưa render đủ → đợi tiếp, KHÔNG fallback.
        return null;
      }

      // Chưa từng thấy msg-tab label. Đợi đủ lâu rồi mới fallback.
      if (attempts < WAIT_NO_MSGTAB) return null;
      // Fallback 1: friend-item (chỉ khi xác định không có msg-tab).
      var first = document.querySelector('#searchResultList [id^="friend-item-"]');
      if (first) return { el: first, kind: 'friend', id: first.id };
      // Fallback 2: bất kỳ search-message__item nào.
      var msgs = document.querySelectorAll('#searchResultList .search-message__item');
      if (msgs.length) return { el: msgs[0], kind: 'message', id: 'msg#1' };
      return null;
    }
    function getOpenedAnimId() {
      var sel = document.querySelector('.conv-rel.selected');
      var item = sel ? (sel.closest && sel.closest('.msg-item')) : null;
      return item ? (item.getAttribute('anim-data-id') || '') : '';
    }
    function clickHard(el) {
      try { el.click(); } catch (e) {}
      try {
        var inner = el.querySelector('.conv-item-title__name')
          || el.querySelector('.search-message__item__content')
          || el;
        inner.click();
      } catch (e) {}
      try {
        ['mousedown','mouseup','click'].forEach(function(t){
          el.dispatchEvent(new MouseEvent(t, { bubbles:true, cancelable:true, view:window }));
        });
      } catch (e) {}
    }

    var input = findInput();
    if (!input) { done('NO_INPUT', '', '', '', '[]'); return; }
    try { input.focus(); } catch (e) {}
    setReactInputValue(input, phone);

    var attempts = 0;
    var maxAttempts = 75; // ~15s (đợi msg-tab load)
    var clicked = false;
    var clickedKind = '';
    var clickedAtAttempt = 0;
    var POST_CLICK_WAIT = 10; // ~2s sau click để conv mở xong rồi mới return OK.

    function poll() {
      attempts++;
      if (clicked) {
        // Đã click xong → chỉ đợi 2s cho conv visually open rồi return OK.
        // Không verify sidebar selection (search msg-tab không update
        // .conv-rel.selected). animId/peerName/avatar best-effort.
        if (attempts - clickedAtAttempt >= POST_CLICK_WAIT) {
          fetchAndDone(getOpenedAnimId());
          return;
        }
        setTimeout(poll, 200);
        return;
      }
      var pick = pickFriend(attempts);
      if (pick) {
        clicked = true;
        clickedKind = pick.kind;
        clickedAtAttempt = attempts;
        clickHard(pick.el);
        setTimeout(function() {
          var btn = findCloseBtn();
          if (btn) { try { btn.click(); } catch (e) {} }
        }, 300);
        setTimeout(poll, 200);
        return;
      }
      if (attempts >= maxAttempts) {
        done('NO_RESULT', '', '', '', '[]');
        return;
      }
      setTimeout(poll, 200);
    }

    function fetchAndDone(animId) {
      // Chỉ lấy peer name + avatar để hiển thị header trong modal nhỏ.
      // Không đọc tin nhắn — user nhìn trực tiếp WebView phía sau modal.
      var sidebarItem = null;
      var items = document.querySelectorAll('.msg-item');
      for (var i = 0; i < items.length; i++) {
        if (items[i].getAttribute('anim-data-id') === animId) { sidebarItem = items[i]; break; }
      }
      var peerName = '';
      var avatarUrl = '';
      if (sidebarItem) {
        var nameInner = sidebarItem.querySelector('.conv-item-title__name .truncate');
        var nameEl = nameInner || sidebarItem.querySelector('.conv-item-title__name');
        peerName = nameEl ? snippet((nameEl.innerText || '').trim(), 100) : '';
        var imgEl = sidebarItem.querySelector('.zavatar img');
        if (imgEl && imgEl.src) avatarUrl = imgEl.src;
      }
      done('OK', animId, peerName, avatarUrl, '[]');
    }

    setTimeout(poll, 500);
  };

  // Đọc N tin cuối cùng KHÔNG phải mình gửi của hội thoại đang mở.
  // Trả về { animId, peerName, avatarUrl, messages: [{text, images:[url]}] }.
  // Phục vụ tính năng "kiểm tra thanh toán" trên màn đơn hàng.
  window.__autoOrderFetchPaymentCheck = function(limit) {
    var lim = (typeof limit === 'number' && limit > 0) ? limit : 10;
    function done(status, animId, peerName, avatarUrl, msgsJson) {
      try {
        AutoOrderBridge.onPaymentCheck(status, animId || '', peerName || '',
          avatarUrl || '', msgsJson || '[]');
      } catch (e) {}
    }
    var selRel = document.querySelector('.conv-rel.selected');
    var sidebarItem = selRel ? (selRel.closest && selRel.closest('.msg-item')) : null;
    var animId = '';
    var peerName = '';
    var avatarUrl = '';
    if (sidebarItem) {
      animId = sidebarItem.getAttribute('anim-data-id') || '';
      var nameInner = sidebarItem.querySelector('.conv-item-title__name .truncate');
      var nameEl = nameInner || sidebarItem.querySelector('.conv-item-title__name');
      peerName = nameEl ? snippet((nameEl.innerText || '').trim(), 100) : '';
      var imgEl = sidebarItem.querySelector('.zavatar img');
      if (imgEl && imgEl.src) avatarUrl = imgEl.src;
    }
    var c = document.getElementById('messageViewScroll') ||
            document.getElementById('messageViewContainer');
    if (!c) { done('NO_CONTAINER', animId, peerName, avatarUrl, '[]'); return; }
    var nodes = c.querySelectorAll('.chat-item');
    if (!nodes.length) { done('EMPTY', animId, peerName, avatarUrl, '[]'); return; }

    var picked = [];
    for (var i = nodes.length - 1; i >= 0 && picked.length < lim; i--) {
      var it = nodes[i];
      var cls = ' ' + (classOf(it) || '') + ' ';
      var isMe = / me /.test(cls) || !!it.querySelector('.chat-body.me, .me');
      if (isMe) continue;
      var textEl = it.querySelector('[data-component=text-container]') ||
                   it.querySelector('.text-message__container');
      var text = textEl ? (textEl.innerText || '').trim() : '';
      var imgs = it.querySelectorAll('.img-msg-v2 img, .photo-message-v2 img, .chat-photo-v2 img');
      var images = [];
      for (var k = 0; k < imgs.length; k++) {
        var src = imgs[k].src || imgs[k].getAttribute('data-src') || '';
        if (src) images.push(src);
      }
      if (!text) {
        var card = extractContactCard(it);
        if (card) text = card;
      }
      if (!text && images.length === 0) {
        if (it.querySelector('.sticker-message')) text = '[sticker]';
        else if (it.querySelector('.video-message')) text = '[video]';
        else if (it.querySelector('.file-message')) text = '[file]';
        else continue;
      }
      var ts = NaN;
      var idEl = it.id ? it : (it.querySelector && it.querySelector('[id^="bb_msg_id_"]'));
      if (idEl && idEl.id) {
        var m = idEl.id.match(/bb_msg_id_(\d+)/);
        if (m) ts = parseInt(m[1], 10);
      }
      picked.push({
        text: snippet(text, 2000),
        images: images,
        time: isNaN(ts) ? 0 : ts
      });
    }
    picked.reverse();
    done('OK', animId, peerName, avatarUrl, JSON.stringify(picked));
  };

  AutoOrderBridge.onDump('init', '', 'Observer installed', '');
})();
"""
