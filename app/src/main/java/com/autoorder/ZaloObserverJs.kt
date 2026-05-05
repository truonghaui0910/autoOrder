package com.autoorder

internal const val ZALO_OBSERVER_JS = """
(function() {
  if (window.__autoOrderInstalled) return;
  window.__autoOrderInstalled = true;

  try {
    var vp = document.querySelector('meta[name=viewport]');
    if (!vp) {
      vp = document.createElement('meta');
      vp.name = 'viewport';
      document.head.appendChild(vp);
    }
    vp.setAttribute('content', 'width=1400, initial-scale=0.25, user-scalable=yes');
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
