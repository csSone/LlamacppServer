function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function normalizeUrlLikeText(text) {
  let t = String(text == null ? '' : text);
  t = t.replace(/^(https?):\\\/\\\//i, '$1://');
  t = t.replace(/\\u002f/ig, '/');
  t = t.replace(/\\\//g, '/');
  return t;
}

function stripOneTrailingBoundaryChar(text) {
  const s = String(text == null ? '' : text);
  if (!s) return { prefix: s, suffix: '', stripped: false };
  const last = s.slice(-1);
  if (last && last.charCodeAt(0) > 127) return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  const simpleBoundary = `"'<> \t\r\n，。；：！？、）》】」’”`;
  if (simpleBoundary.includes(last)) return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  if (last === ')') {
    const opens = (s.match(/\(/g) || []).length;
    const closes = (s.match(/\)/g) || []).length;
    if (closes > opens) return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  }
  if (last === ']') {
    const opens = (s.match(/\[/g) || []).length;
    const closes = (s.match(/\]/g) || []).length;
    if (closes > opens) return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  }
  if (last === '}') {
    const opens = (s.match(/\{/g) || []).length;
    const closes = (s.match(/\}/g) || []).length;
    if (closes > opens) return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  }
  if (last === ',' || last === '.' || last === ';' || last === ':' || last === '!' || last === '?') {
    return { prefix: s.slice(0, -1), suffix: last, stripped: true };
  }
  return { prefix: s, suffix: '', stripped: false };
}

function cleanupHrefCandidate(value) {
  let v = String(value == null ? '' : value).trim();
  if (!v) return '';
  v = normalizeUrlLikeText(v);
  let suffix = '';
  for (let i = 0; i < 50; i++) {
    const step = stripOneTrailingBoundaryChar(v);
    if (!step.stripped) break;
    v = step.prefix;
    suffix = step.suffix + suffix;
  }
  if (isSafeUrl(v)) return v;
  for (let i = 0; i < 120 && v; i++) {
    const last = v.slice(-1);
    v = v.slice(0, -1);
    suffix = last + suffix;
    if (isSafeUrl(v)) return v;
  }
  return '';
}

function fixAutolinkElement(el) {
  if (!el) return;
  const hrefAttr = el.getAttribute('href') || '';
  const text = el.textContent == null ? '' : String(el.textContent);
  if (!hrefAttr || !text) return;

  const hrefNorm = normalizeUrlLikeText(hrefAttr).trim();
  const textNorm = normalizeUrlLikeText(text).trim();
  const looksLikeAutolink = textNorm === hrefNorm || textNorm.startsWith(hrefNorm) || hrefNorm.startsWith(textNorm);
  if (!looksLikeAutolink) return;

  let prefix = text;
  for (let i = 0; i < 80; i++) {
    const step = stripOneTrailingBoundaryChar(prefix);
    if (!step.stripped) break;
    prefix = step.prefix;
  }

  for (let i = 0; i < 160 && prefix; i++) {
    const candidate = normalizeUrlLikeText(prefix).trim();
    if (isSafeUrl(candidate)) break;
    prefix = prefix.slice(0, -1);
  }

  const fixedHref = normalizeUrlLikeText(prefix).trim();
  if (!isSafeUrl(fixedHref)) return;

  const fixedDisplay = fixedHref;
  const trailingText = text.slice(prefix.length) || '';

  el.setAttribute('href', fixedHref);
  while (el.firstChild) el.removeChild(el.firstChild);
  el.appendChild(el.ownerDocument.createTextNode(fixedDisplay));
  if (trailingText) {
    const parent = el.parentNode;
    if (parent) parent.insertBefore(el.ownerDocument.createTextNode(trailingText), el.nextSibling);
  }
}

function isSafeUrl(value) {
  const href = String(value == null ? '' : value).trim();
  if (!href) return false;
  if (href.startsWith('#')) return true;
  try {
    const u = new URL(href, window.location.href);
    const p = u.protocol;
    return p === 'http:' || p === 'https:' || p === 'mailto:' || p === 'tel:';
  } catch (e) {
    return false;
  }
}

function mergeIsolatedBracesIntoCodeBlocks(root, doc) {
  if (!root || !doc) return;

  function isIgnorableTextNode(n) {
    return n && n.nodeType === Node.TEXT_NODE && !String(n.textContent || '').trim();
  }

  function prevNonEmptySibling(n) {
    let p = n ? n.previousSibling : null;
    while (p && isIgnorableTextNode(p)) p = p.previousSibling;
    return p;
  }

  function nextNonEmptySibling(n) {
    let p = n ? n.nextSibling : null;
    while (p && isIgnorableTextNode(p)) p = p.nextSibling;
    return p;
  }

  function readIsolatedBraceText(n) {
    if (!n) return '';
    if (n.nodeType === Node.TEXT_NODE) return String(n.textContent || '').trim();
    if (n.nodeType === Node.ELEMENT_NODE) return String(n.textContent || '').trim();
    return '';
  }

  function removeNode(n) {
    try {
      if (n && n.parentNode) n.parentNode.removeChild(n);
    } catch (e) { }
  }

  function findAdjacentIsolatedBraceNode(fromNode, braceChar, dir) {
    let cur = fromNode;
    while (cur && cur !== root) {
      const sib = dir === 'next' ? nextNonEmptySibling(cur) : prevNonEmptySibling(cur);
      if (sib) {
        const t = readIsolatedBraceText(sib);
        if (t === braceChar) return sib;
        return null;
      }
      cur = cur.parentNode;
    }
    return null;
  }

  const pres = Array.from(root.querySelectorAll('pre'));
  for (const pre of pres) {
    const code = pre.querySelector('code') || null;
    if (!code) continue;

    const rawText = String(code.textContent || '');

    const prev = findAdjacentIsolatedBraceNode(pre, '{', 'prev');
    const prevText = readIsolatedBraceText(prev);
    if (prevText === '{') {
      code.textContent = '{\n' + rawText;
      removeNode(prev);
    }

    const rawText2 = String(code.textContent || '');

    const next = findAdjacentIsolatedBraceNode(pre, '}', 'next');
    const nextText = readIsolatedBraceText(next);
    if (nextText === '}') {
      const joiner = rawText2.endsWith('\n') ? '' : '\n';
      code.textContent = rawText2 + joiner + '}';
      removeNode(next);
    }
  }
}

function mergeAdjacentParagraphLinesIntoCodeBlocks(root, doc) {
  if (!root || !doc) return;

  function isIgnorableTextNode(n) {
    return n && n.nodeType === Node.TEXT_NODE && !String(n.textContent || '').trim();
  }

  function prevNonEmptySibling(n) {
    let p = n ? n.previousSibling : null;
    while (p && isIgnorableTextNode(p)) p = p.previousSibling;
    return p;
  }

  function nextNonEmptySibling(n) {
    let p = n ? n.nextSibling : null;
    while (p && isIgnorableTextNode(p)) p = p.nextSibling;
    return p;
  }

  function paragraphLines(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return null;
    const tag = (el.tagName || '').toLowerCase();
    if (tag !== 'p') return null;
    const out = [];
    let cur = '';
    for (const child of Array.from(el.childNodes || [])) {
      if (child.nodeType === Node.TEXT_NODE) {
        cur += String(child.textContent || '');
        continue;
      }
      if (child.nodeType === Node.ELEMENT_NODE && (child.tagName || '').toLowerCase() === 'br') {
        out.push(cur);
        cur = '';
        continue;
      }
      return null;
    }
    out.push(cur);
    return out.map(s => String(s || '').replace(/\r/g, ''));
  }

  function setParagraphLines(el, lines) {
    if (!el) return;
    while (el.firstChild) el.removeChild(el.firstChild);
    const ls = Array.isArray(lines) ? lines : [];
    for (let i = 0; i < ls.length; i++) {
      if (i > 0) el.appendChild(doc.createElement('br'));
      el.appendChild(doc.createTextNode(String(ls[i] == null ? '' : ls[i])));
    }
  }

  function trimLine(s) {
    return String(s == null ? '' : s).trim();
  }

  function isLikelyCodeLine(s) {
    const t = trimLine(s);
    if (!t) return false;
    if (/^[@#]/.test(t)) return true;
    if (/[;=]/.test(t)) return true;
    if (/\w+\s*\(.*\)/.test(t)) return true;
    if (/\b(class|interface|enum|record)\b/.test(t)) return true;
    if (/\b(public|private|protected|static|final|abstract|void|int|long|double|float|boolean|char|byte|short|String)\b/.test(t)) return true;
    return false;
  }

  function isStandaloneClosingTokenLine(s) {
    const t = trimLine(s);
    if (!t) return false;
    return /^[\]\)\}]+;?$/.test(t);
  }

  function moveTrailingCodeLinesIntoPre(pre, code, p) {
    const lines = paragraphLines(p);
    if (!lines) return false;
    let i = lines.length - 1;
    while (i >= 0 && !trimLine(lines[i])) i--;
    if (i < 0) return false;

    const moved = [];
    for (; i >= 0; i--) {
      const t = trimLine(lines[i]);
      if (!t) {
        if (moved.length) moved.unshift('');
        continue;
      }
      if (!isLikelyCodeLine(t)) break;
      moved.unshift(lines[i]);
    }
    if (!moved.length) return false;

    const remain = lines.slice(0, i + 1);
    while (remain.length && !trimLine(remain[remain.length - 1])) remain.pop();
    if (remain.length) {
      setParagraphLines(p, remain);
    } else {
      if (p.parentNode) p.parentNode.removeChild(p);
    }
    const raw = String(code.textContent || '');
    code.textContent = moved.join('\n') + '\n' + raw;
    return true;
  }

  function moveLeadingClosingLinesIntoPre(pre, code, p) {
    const lines = paragraphLines(p);
    if (!lines) return false;
    let i = 0;
    while (i < lines.length && !trimLine(lines[i])) i++;
    if (i >= lines.length) return false;

    const moved = [];
    for (; i < lines.length; i++) {
      const t = trimLine(lines[i]);
      if (!t) {
        if (moved.length) moved.push('');
        continue;
      }
      if (!isStandaloneClosingTokenLine(t)) break;
      moved.push(lines[i]);
    }
    if (!moved.length) return false;

    const remain = lines.slice(i);
    while (remain.length && !trimLine(remain[0])) remain.shift();
    if (remain.length) {
      setParagraphLines(p, remain);
    } else {
      if (p.parentNode) p.parentNode.removeChild(p);
    }
    const raw = String(code.textContent || '');
    const suffix = moved.join('\n');
    const joiner = raw.endsWith('\n') ? '' : '\n';
    code.textContent = raw + joiner + suffix;
    return true;
  }

  const pres = Array.from(root.querySelectorAll('pre'));
  for (const pre of pres) {
    const code = pre.querySelector('code') || null;
    if (!code) continue;

    const prev = prevNonEmptySibling(pre);
    if (prev && prev.nodeType === Node.ELEMENT_NODE && (prev.tagName || '').toLowerCase() === 'p') {
      moveTrailingCodeLinesIntoPre(pre, code, prev);
    }

    const next = nextNonEmptySibling(pre);
    if (next && next.nodeType === Node.ELEMENT_NODE && (next.tagName || '').toLowerCase() === 'p') {
      moveLeadingClosingLinesIntoPre(pre, code, next);
    }
  }
}

function sanitizeMarkdownHtml(html) {
  const allowedTags = new Set([
    'div',
    'p', 'br', 'strong', 'em', 'del',
    'code', 'pre',
    'blockquote',
    'ul', 'ol', 'li',
    'a', 'hr',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'img', 'span'
  ]);

  const allowedAttrsByTag = {
    a: new Set(['href', 'title', 'target', 'rel']),
    img: new Set(['src', 'alt', 'title']),
    th: new Set(['align']),
    td: new Set(['align']),
    code: new Set(['class']),
    pre: new Set(['class']),
    span: new Set(['class'])
  };

  const doc = new DOMParser().parseFromString('<div>' + String(html || '') + '</div>', 'text/html');
  const root = doc.body && doc.body.firstChild ? doc.body.firstChild : null;
  if (!root) return '';

  mergeIsolatedBracesIntoCodeBlocks(root, doc);
  mergeAdjacentParagraphLinesIntoCodeBlocks(root, doc);

  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null);
  const nodes = [];
  let n = walker.currentNode;
  while (n) {
    nodes.push(n);
    n = walker.nextNode();
  }

  for (const el of nodes) {
    const tag = (el.tagName || '').toLowerCase();
    if (!allowedTags.has(tag)) {
      const parent = el.parentNode;
      if (!parent) continue;
      while (el.firstChild) parent.insertBefore(el.firstChild, el);
      parent.removeChild(el);
      continue;
    }

    const allowedAttrs = allowedAttrsByTag[tag] || null;
    for (const attr of Array.from(el.attributes || [])) {
      const name = (attr.name || '').toLowerCase();
      if (!allowedAttrs || !allowedAttrs.has(name)) {
        el.removeAttribute(attr.name);
        continue;
      }
      if ((tag === 'a' && name === 'href') || (tag === 'img' && name === 'src')) {
        const raw = el.getAttribute(attr.name) || '';
        const fixed = cleanupHrefCandidate(raw);
        if (!fixed) {
          el.removeAttribute(attr.name);
        } else {
          el.setAttribute(attr.name, fixed);
        }
      }
    }

    if (tag === 'a') {
      el.setAttribute('rel', 'noopener noreferrer');
      if (!el.getAttribute('target')) el.setAttribute('target', '_blank');
      fixAutolinkElement(el);
    }
  }

  return root.innerHTML;
}

function markdownToSafeHtml(text) {
  const input = (text == null ? '' : String(text));
  if (!window.marked || typeof window.marked.parse !== 'function') return escapeHtml(input);
  let raw = '';
  try {
    raw = window.marked.parse(input, { gfm: true, breaks: true, mangle: false, headerIds: false });
  } catch (e) {
    return escapeHtml(input);
  }
  return sanitizeMarkdownHtml(raw);
}

function wrapHtmlFragment(fragment) {
  const raw = String(fragment == null ? '' : fragment).trim();
  if (!raw) return '';
  if (/<html[\s>]/i.test(raw)) return raw;
  return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><style>html,body{margin:0;padding:0;font-family:system-ui,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#fff;color:#111}</style></head><body>' + raw + '</body></html>';
}

function extractRunnableHtml(text) {
  const input = String(text == null ? '' : text);
  const trimmed = input.trim();
  if (!trimmed) return '';
  if (/^\s*<!doctype html[\s>]/i.test(trimmed) || /^\s*<html[\s>]/i.test(trimmed)) {
    return wrapHtmlFragment(trimmed);
  }
  const fence = /```+([a-zA-Z0-9_-]*)\s*([\s\S]*?)```+/g;
  let match = null;
  while ((match = fence.exec(input)) !== null) {
    const lang = String(match[1] || '').toLowerCase();
    if (!lang || !(lang === 'html' || lang === 'htm' || lang === 'xhtml')) continue;
    const body = String(match[2] || '').trim();
    if (body) return wrapHtmlFragment(body);
  }
  return '';
}

let markdownRaf = 0;
let lastMarkdownFlushAt = 0;
const pendingMarkdownRenders = new Map();
const pendingHljsTimers = new WeakMap();

function scheduleHighlight(el, text) {
  if (!el) return;
  if (!window.hljs || typeof window.hljs.highlightElement !== 'function') return;
  const t = (text == null ? '' : String(text));
  if (!(t.includes('```') || t.includes('`'))) return;
  const prev = pendingHljsTimers.get(el);
  if (prev) clearTimeout(prev);
  const timer = setTimeout(() => {
    pendingHljsTimers.delete(el);
    const blocks = el.querySelectorAll('pre code');
    for (const b of blocks) window.hljs.highlightElement(b);
  }, 350);
  pendingHljsTimers.set(el, timer);
}

function renderMessageContentNow(el, text) {
  if (!el) return;
  const t = (text == null ? '' : String(text));
  if (!window.marked || typeof window.marked.parse !== 'function') {
    el.classList.add('plain');
    el.textContent = t;
    return;
  }
  el.classList.remove('plain');
  el.innerHTML = markdownToSafeHtml(t);
  scheduleHighlight(el, t);
}

function flushMarkdown(ts) {
  markdownRaf = 0;
  const now = typeof ts === 'number' ? ts : (typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now());
  if (now - lastMarkdownFlushAt < 50) {
    markdownRaf = requestAnimationFrame(flushMarkdown);
    return;
  }
  lastMarkdownFlushAt = now;
  for (const [node, value] of pendingMarkdownRenders.entries()) {
    renderMessageContentNow(node, value);
  }
  pendingMarkdownRenders.clear();
}

function requestRenderMessageContent(el, text) {
  if (!el) return;
  pendingMarkdownRenders.set(el, text);
  if (markdownRaf) return;
  markdownRaf = requestAnimationFrame(flushMarkdown);
}

