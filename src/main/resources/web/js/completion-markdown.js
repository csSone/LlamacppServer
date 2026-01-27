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

