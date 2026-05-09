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
        if (it.querySelector('.img-msg-v2') || it.querySelector('.photo-message-v2')) {
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
      var arr = [];
      var dbgTotal = nodes.length, dbgWithTs = 0, dbgInRange = 0, dbgFromMe = 0;
      var dbgFirstTs = NaN, dbgLastTs = NaN;
      for (var i = 0; i < nodes.length; i++) {
        var it = nodes[i];
        var t = tsOf(it);
        if (isNaN(t)) continue;
        dbgWithTs++;
        if (isNaN(dbgFirstTs)) dbgFirstTs = t;
        dbgLastTs = t;
        if (t < dayStartMs || t > dayEndMs) continue;
        dbgInRange++;
        if (!isMeNode(it)) continue;
        dbgFromMe++;
        var textEl = it.querySelector('[data-component=text-container]') ||
                     it.querySelector('.text-message__container');
        var text = textEl ? (textEl.innerText || '').trim() : '';
        if (!text) {
          if (it.querySelector('.img-msg-v2') || it.querySelector('.photo-message-v2')) {
            text = '[hình ảnh]';
          }
        }
        if (!text) continue;
        arr.push({ from: 'me', text: snippet(text, 2000), time: String(t) });
      }
      try {
        AutoOrderBridge.onDump('checkout-debug', '',
          'total=' + dbgTotal + ' withTs=' + dbgWithTs +
          ' inRange=' + dbgInRange + ' fromMe=' + dbgFromMe +
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

  AutoOrderBridge.onDump('init', '', 'Observer installed', '');
})();
"""
