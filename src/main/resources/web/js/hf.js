let hfHits = [];
let hfGguf = [];
let hfGgufGroups = [];
let hfSelected = null;
let hfTreeError = null;
let hfSearchQuery = '';
let hfSearchBase = 'mirror';
let hfNextStartPage = 0;
let hfMaxPagesPerFetch = 1;
let hfLoadingHits = false;
const HF_SEARCH_PAGE_SIZE = 30;

if (typeof window.showToast !== 'function') {
    window.showToast = function(title, msg, type = 'info') {
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const id = 'toast-' + Date.now();
        const icon = type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle';
        const html = `
            <div class="toast ${type}" id="${id}">
                <div class="toast-icon"><i class="fas ${icon}"></i></div>
                <div class="toast-content"><div class="toast-title">${title}</div><div class="toast-message">${msg}</div></div>
                <button class="toast-close" onclick="document.getElementById('${id}').remove()">&times;</button>
            </div>`;
        container.insertAdjacentHTML('beforeend', html);
        setTimeout(() => { const el = document.getElementById(id); if (el) el.remove(); }, 5000);
    };
}

async function copyToClipboard(text) {
    const value = text == null ? '' : String(text);
    if (!value) return false;
    if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
            await navigator.clipboard.writeText(value);
            return true;
        } catch (e) {
        }
    }
    try {
        const ta = document.createElement('textarea');
        ta.value = value;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
    } catch (e) {
        return false;
    }
}

function formatFileSize(size) {
    const n = Number(size);
    if (!isFinite(n) || n <= 0) return '';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + ' MB';
    return (n / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
}

function renderHits() {
    const container = document.getElementById('hfHitsList');
    if (!container) return;
    if (!hfHits || hfHits.length === 0) {
        container.innerHTML = `<div class="empty-state">未找到结果</div>`;
        setHitsFooterVisible(false);
        return;
    }
    container.innerHTML = hfHits.map(hit => {
        const downloads = hit.downloads != null ? `<span class="badge"><i class="fas fa-download"></i> ${hit.downloads}</span>` : '';
        const likes = hit.likes != null ? `<span class="badge"><i class="fas fa-thumbs-up"></i> ${hit.likes}</span>` : '';
        const params = hit.parameters ? `<span class="badge"><i class="fas fa-sliders-h"></i> ${hit.parameters}</span>` : '';
        const tag = hit.pipelineTag ? `<span class="badge"><i class="fas fa-tag"></i> ${hit.pipelineTag}</span>` : '';
        const mod = hit.lastModified ? `<span class="badge"><i class="fas fa-clock"></i> ${hit.lastModified}</span>` : '';
        return `
            <div class="list-item">
                <div style="min-width: 0; flex: 1;">
                    <div class="list-item-title mono" onclick="selectRepoAndOpen('${escapeHtmlAttr(hit.repoId)}')">${escapeHtml(hit.repoId)}</div>
                    <div class="list-item-meta">
                        ${downloads}
                        ${likes}
                        ${params}
                        ${tag}
                        ${mod}
                    </div>
                </div>
                <div style="flex-shrink: 0; display: flex; gap: 0.5rem; align-items: center;">
                    <button class="btn btn-secondary btn-sm" onclick="openModelPage('${escapeHtmlAttr(hit.modelUrl)}')">
                        <i class="fas fa-external-link-alt"></i>
                        打开
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function setHitsFooterVisible(visible) {
    const footer = document.getElementById('hfHitsFooter');
    if (!footer) return;
    footer.style.display = visible ? '' : 'none';
}

function setLoadMoreState(enabled, text) {
    const btn = document.getElementById('hfLoadMoreBtn');
    if (!btn) return;
    btn.disabled = !enabled;
    btn.textContent = text || '加载更多';
}

function setLoadMoreVisible(visible) {
    const btn = document.getElementById('hfLoadMoreBtn');
    if (!btn) return;
    btn.style.display = visible ? '' : 'none';
}

function mergeHits(existing, incoming) {
    const list1 = Array.isArray(existing) ? existing : [];
    const list2 = Array.isArray(incoming) ? incoming : [];
    const seen = new Set();
    const out = [];
    for (const h of list1) {
        const id = h && h.repoId != null ? String(h.repoId) : '';
        if (!id || seen.has(id)) continue;
        seen.add(id);
        out.push(h);
    }
    for (const h of list2) {
        const id = h && h.repoId != null ? String(h.repoId) : '';
        if (!id || seen.has(id)) continue;
        seen.add(id);
        out.push(h);
    }
    return out;
}

async function fetchHitsPage(query, base, limit, startPage, maxPages) {
    const url = `/api/hf/search?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`
        + `&startPage=${encodeURIComponent(String(startPage))}&maxPages=${encodeURIComponent(String(maxPages))}`
        + `&base=${encodeURIComponent(base)}`;
    const resp = await fetch(url);
    const data = await resp.json();
    if (!data || data.success !== true) {
        throw new Error((data && data.error) ? data.error : '搜索失败');
    }
    const hits = data.data && data.data.hits ? data.data.hits : [];
    return Array.isArray(hits) ? hits : [];
}

function renderGguf() {
    const container = document.getElementById('hfGgufList');
    if (!container) return;
    if (!hfSelected) {
        container.innerHTML = `<div class="empty-state">选择一个模型以列出 GGUF 文件</div>`;
        return;
    }
    if (!hfGgufGroups || hfGgufGroups.length === 0) {
        container.innerHTML = `<div class="empty-state">未找到 GGUF 文件</div>`;
        return;
    }
    container.innerHTML = hfGgufGroups.map((group, idx) => {
        const sizeText = group.totalSize != null ? formatFileSize(group.totalSize) : '';
        const sizeBadge = sizeText ? `<span class="badge"><i class="fas fa-hdd"></i> ${sizeText}</span>` : '';
        const lfsBadge = group.hasLfs ? `<span class="badge"><i class="fas fa-database"></i> LFS</span>` : '';
        const shardBadge = group.isSplit ? `<span class="badge"><i class="fas fa-th-large"></i> 分片 ${group.partCount}/${group.partTotal}</span>` : '';
        return `
            <div class="list-item">
                <div style="min-width: 0; flex: 1;">
                    <div class="list-item-title mono file-path" onclick="copyGgufGroupLinks(${idx})">${escapeHtml(group.displayPath || '')}</div>
                    <div class="list-item-meta">
                        ${sizeBadge}
                        ${lfsBadge}
                        ${shardBadge}
                    </div>
                </div>
                <div class="file-actions">
                    <button class="btn btn-secondary btn-sm" onclick="copyGgufGroupLinks(${idx})">
                        <i class="fas fa-copy"></i>
                        复制链接
                    </button>
                    <button class="btn btn-primary btn-sm" onclick="downloadModel(${idx})">
                        <i class="fas fa-download"></i>
                        创建下载
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function getSplitInfoFromPath(path) {
    const p = path == null ? '' : String(path);
    const m = p.match(/-(\d{5})-?of-(\d{5})\.gguf$/i);
    if (!m) return null;
    const partIndex = Number(m[1]);
    const partTotal = Number(m[2]);
    if (!isFinite(partIndex) || !isFinite(partTotal)) return null;
    const key = p.replace(/-(\d{5})-?of-(\d{5})\.gguf$/i, '');
    const displayPath = key + '.gguf';
    return { key, displayPath, partIndex, partTotal };
}

function groupGgufFiles(files) {
    const list = Array.isArray(files) ? files : [];
    const groups = new Map();
    const singles = [];
    for (const f of list) {
        const path = f && f.path != null ? String(f.path) : '';
        const info = getSplitInfoFromPath(path);
        if (!info) {
            singles.push({
                isSplit: false,
                key: path,
                displayPath: path,
                files: [f],
                partCount: 1,
                partTotal: 1
            });
            continue;
        }
        const existing = groups.get(info.key);
        if (!existing) {
            groups.set(info.key, {
                isSplit: true,
                key: info.key,
                displayPath: info.displayPath,
                files: [{ file: f, partIndex: info.partIndex }],
                partTotal: info.partTotal
            });
        } else {
            existing.files.push({ file: f, partIndex: info.partIndex });
            if (isFinite(info.partTotal) && info.partTotal > existing.partTotal) existing.partTotal = info.partTotal;
        }
    }

    const merged = [];
    for (const g of groups.values()) {
        g.files.sort((a, b) => (a.partIndex || 0) - (b.partIndex || 0));
        const orderedFiles = g.files.map(x => x.file);
        merged.push({
            isSplit: true,
            key: g.key,
            displayPath: g.displayPath,
            files: orderedFiles,
            partCount: orderedFiles.length,
            partTotal: g.partTotal || orderedFiles.length
        });
    }

    const all = singles.concat(merged).map(g => {
        let totalSize = null;
        let hasAnySize = false;
        let hasLfs = false;
        for (const f of g.files) {
            if (!f) continue;
            if (f.lfsOid) hasLfs = true;
            const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
            if (isFinite(s) && s > 0) {
                totalSize = (totalSize || 0) + s;
                hasAnySize = true;
            }
        }
        return {
            ...g,
            totalSize: hasAnySize ? totalSize : null,
            hasLfs
        };
    });

    all.sort((a, b) => String(a.displayPath || '').localeCompare(String(b.displayPath || ''), 'zh-CN'));
    return all;
}

async function copyGgufGroupLinks(groupIndex) {
    const g = hfGgufGroups && hfGgufGroups[groupIndex] ? hfGgufGroups[groupIndex] : null;
    if (!g) return;
    const links = (g.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl) : '').filter(Boolean);
    if (!links.length) {
        showToast('提示', '没有可复制的下载链接', 'info');
        return;
    }
    const ok = await copyToClipboard(links.join('\n'));
    if (ok) showToast('已复制', `已复制 ${links.length} 条链接`, 'success');
    else showToast('复制失败', '无法写入剪贴板', 'error');
}

function escapeHtml(str) {
    const s = str == null ? '' : String(str);
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function escapeHtmlAttr(str) {
    return escapeHtml(str).replace(/`/g, '&#96;');
}

function openModelPage(url) {
    const u = url == null ? '' : String(url).trim();
    if (!u) return;
    window.open(u, '_blank', 'noopener');
}

function getFileNameFromPath(path) {
    const p = path == null ? '' : String(path);
    const idx = p.lastIndexOf('/');
    const name = idx >= 0 ? p.substring(idx + 1) : p;
    return name.trim();
}

function parseRepoId(repoId) {
    const s = repoId == null ? '' : String(repoId).trim();
    const idx = s.indexOf('/');
    if (idx <= 0 || idx === s.length - 1) return null;
    return { author: s.substring(0, idx), modelId: s.substring(idx + 1) };
}

async function downloadModel(groupIndex) {
    const g = hfGgufGroups && hfGgufGroups[groupIndex] ? hfGgufGroups[groupIndex] : null;
    if (!g) return;
    const repo = parseRepoId(hfSelected);
    if (!repo) {
        showToast('错误', 'RepoId 无效，无法解析 author/modelId', 'error');
        return;
    }
    const downloadUrl = (g.files || [])
        .map(f => f && f.downloadUrl ? String(f.downloadUrl).trim() : '')
        .filter(Boolean);
    if (!downloadUrl.length) {
        showToast('提示', '下载链接为空', 'info');
        return;
    }
    const fileName = getFileNameFromPath(g.displayPath || g.key || (g.files && g.files[0] ? g.files[0].path : ''));
    const payload = { author: repo.author, modelId: repo.modelId, downloadUrl };
    if (fileName) payload.name = fileName;

    try {
        const resp = await fetch('/api/downloads/model/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        if (!resp.ok) {
            const text = await resp.text();
            throw new Error(text || `请求失败(${resp.status})`);
        }
        const data = await resp.json();
        if (!data || data.success !== true) {
            throw new Error((data && data.error) ? data.error : '创建下载任务失败');
        }
        const count = Array.isArray(data.tasks) ? data.tasks.length : downloadUrl.length;
        showToast('成功', `已创建 ${count} 个下载任务`, 'success');
    } catch (e) {
        showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
    }
}

async function copyLink(url) {
    const ok = await copyToClipboard(url);
    if (ok) showToast('已复制', '下载链接已复制到剪贴板', 'success');
    else showToast('复制失败', '无法写入剪贴板', 'error');
}

async function copyAllGgufLinks() {
    if (!hfGguf || hfGguf.length === 0) {
        showToast('提示', '当前没有可复制的链接', 'info');
        return;
    }
    const text = hfGguf.map(f => f && f.downloadUrl ? String(f.downloadUrl) : '').filter(Boolean).join('\n');
    const ok = await copyToClipboard(text);
    if (ok) showToast('已复制', `已复制 ${hfGguf.length} 条链接`, 'success');
    else showToast('复制失败', '无法写入剪贴板', 'error');
}

async function hfSearch() {
    const input = document.getElementById('hfSearchInput');
    const limitEl = document.getElementById('hfLimit');
    const baseEl = document.getElementById('hfBaseSelect');
    const query = input ? String(input.value || '').trim() : '';
    if (!query) {
        showToast('提示', '请输入搜索关键字', 'info');
        return;
    }
    const limit = limitEl ? String(limitEl.value || '30') : '30';
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    hfSearchQuery = query;
    hfSearchBase = base;
    const n = Number(limit);
    const safeLimit = isFinite(n) && n > 0 ? Math.min(200, Math.floor(n)) : 30;
    hfMaxPagesPerFetch = Math.max(1, Math.ceil(safeLimit / HF_SEARCH_PAGE_SIZE));
    hfNextStartPage = 0;
    hfHits = [];
    document.getElementById('hfHitsList').innerHTML = `<div class="empty-state">正在搜索...</div>`;
    setHitsFooterVisible(false);
    setLoadMoreVisible(false);
    try {
        hfLoadingHits = true;
        setLoadMoreState(false, '正在加载...');
        const newHits = await fetchHitsPage(hfSearchQuery, hfSearchBase, safeLimit, hfNextStartPage, hfMaxPagesPerFetch);
        hfHits = mergeHits(hfHits, newHits);
        hfNextStartPage += hfMaxPagesPerFetch;
        renderHits();
        if (newHits.length < safeLimit) {
            setHitsFooterVisible(false);
        } else {
            setLoadMoreVisible(true);
            setHitsFooterVisible(true);
            setLoadMoreState(true, '加载更多');
        }
    } catch (e) {
        hfHits = [];
        document.getElementById('hfHitsList').innerHTML = `<div class="empty-state">搜索失败</div>`;
        setHitsFooterVisible(false);
        showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
    } finally {
        hfLoadingHits = false;
    }
}

async function hfLoadMore() {
    if (hfLoadingHits) return;
    const query = hfSearchQuery == null ? '' : String(hfSearchQuery).trim();
    if (!query) return;
    const limitEl = document.getElementById('hfLimit');
    const n = Number(limitEl ? String(limitEl.value || '30') : '30');
    const safeLimit = isFinite(n) && n > 0 ? Math.min(200, Math.floor(n)) : 30;
    hfMaxPagesPerFetch = Math.max(1, Math.ceil(safeLimit / HF_SEARCH_PAGE_SIZE));

    setHitsFooterVisible(false);
    setLoadMoreVisible(false);
    setLoadMoreState(false, '正在加载...');
    try {
        hfLoadingHits = true;
        const before = hfHits.length;
        const newHits = await fetchHitsPage(query, hfSearchBase, safeLimit, hfNextStartPage, hfMaxPagesPerFetch);
        hfHits = mergeHits(hfHits, newHits);
        hfNextStartPage += hfMaxPagesPerFetch;
        renderHits();
        const added = hfHits.length - before;
        if (added <= 0 || newHits.length < safeLimit) {
            setHitsFooterVisible(false);
            if (added <= 0) {
                showToast('提示', '没有更多结果了', 'info');
            }
            return;
        }
        setLoadMoreVisible(true);
        setHitsFooterVisible(true);
        setLoadMoreState(true, '加载更多');
    } catch (e) {
        setLoadMoreVisible(true);
        setHitsFooterVisible(true);
        setLoadMoreState(true, '加载更多');
        showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
    } finally {
        hfLoadingHits = false;
    }
}

async function selectRepo(repoId) {
    const baseEl = document.getElementById('hfBaseSelect');
    const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
    const id = repoId == null ? '' : String(repoId).trim();
    if (!id) return;
    hfSelected = id;
    hfGguf = [];
    hfTreeError = null;
    const repoLabel = document.getElementById('hfGgufModalRepo');
    if (repoLabel) repoLabel.textContent = hfSelected;
    document.getElementById('hfGgufList').innerHTML = `<div class="empty-state">正在解析 GGUF 文件...</div>`;
    try {
        const resp = await fetch(`/api/hf/gguf?model=${encodeURIComponent(hfSelected)}&base=${encodeURIComponent(base)}`);
        const data = await resp.json();
        if (!data || data.success !== true) {
            throw new Error((data && data.error) ? data.error : '解析失败');
        }
        const result = data.data || {};
        hfTreeError = result.treeError || null;
        hfGguf = result.ggufFiles || [];
        hfGgufGroups = groupGgufFiles(hfGguf);
        renderGguf();
        if (hfTreeError) showToast('提示', hfTreeError, 'info');
    } catch (e) {
        hfGguf = [];
        hfGgufGroups = [];
        document.getElementById('hfGgufList').innerHTML = `<div class="empty-state">解析失败</div>`;
        showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
    }
}

if (typeof window.openModal !== 'function') {
    window.openModal = function(id) {
        const el = document.getElementById(id);
        if (el) el.classList.add('show');
    };
}

if (typeof window.closeModal !== 'function') {
    window.closeModal = function(id) {
        const el = document.getElementById(id);
        if (el) el.classList.remove('show');
    };
}

function selectRepoAndOpen(repoId) {
    openModal('hfGgufModal');
    selectRepo(repoId);
}

if (typeof window.shutdownService !== 'function') {
    window.shutdownService = function() {
        if (!confirm('确定要停止服务吗？')) return;
        fetch('/api/shutdown', { method: 'POST' })
            .then(r => r.json())
            .then(data => {
                if (data && data.success) {
                    showToast('成功', '服务正在停止', 'success');
                } else {
                    showToast('错误', (data && data.error) ? data.error : '停止服务失败', 'error');
                }
            })
            .catch(() => showToast('错误', '网络请求失败', 'error'));
    };
}

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('hfSearchInput');
    const baseEl = document.getElementById('hfBaseSelect');
    if (input) {
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                hfSearch();
            }
        });
    }
    const params = new URLSearchParams(window.location.search || '');
    const q = params.get('q');
    const base = params.get('base');
    if (q) {
        input.value = q;
    }
    if (baseEl && base) {
        const b = String(base).trim().toLowerCase();
        if (b === 'official' || b === 'huggingface' || b === 'huggingface.co') baseEl.value = 'official';
        if (b === 'mirror' || b === 'hf-mirror' || b === 'hf-mirror.com') baseEl.value = 'mirror';
    }
    if (q) {
        hfSearch();
    }
});

window.addEventListener('click', (e) => {
    const t = e && e.target ? e.target : null;
    if (t && t.classList && t.classList.contains('modal')) t.classList.remove('show');
});

document.addEventListener('keydown', (e) => {
    if (e && e.key === 'Escape') closeModal('hfGgufModal');
});
