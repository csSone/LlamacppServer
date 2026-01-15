(function() {
    const isDownloadHtml = /(^|\/)download\.html$/i.test(String(window.location.pathname || ''));

    const state = {
        started: false,
        downloads: [],
        websocket: null,
        reconnectAttempts: 0,
        maxReconnectAttempts: 5,
        reconnectInterval: 5000,
        speedByTaskId: {},
        hfModalHits: [],
        hfModalSelected: null,
        hfModalGgufFiles: [],
        hfModalTreeError: null,
        hfModalGgufGroups: [],
        hfModalMmprojGroups: []
    };

    function closeModal(id) {
        if (typeof window.closeModal === 'function') {
            window.closeModal(id);
            return;
        }
        const el = document.getElementById(id);
        if (el) el.classList.remove('show');
        if (id === 'createDownloadModal') {
            const form = document.getElementById('createDownloadForm');
            if (form) form.reset();
        } else if (id === 'settingsModal') {
            const form = document.getElementById('settingsForm');
            if (form) form.reset();
        }
    }

    function showToast(title, msg, type = 'info') {
        if (typeof window.showToast === 'function') {
            window.showToast(title, msg, type);
            return;
        }
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const id = 'toast-' + Date.now();
        const html = `
            <div class="toast ${type}" id="${id}">
                <div class="toast-icon"><i class="fas ${type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle'}"></i></div>
                <div class="toast-content"><div class="toast-title">${title}</div><div class="toast-message">${msg}</div></div>
                <button class="toast-close" onclick="document.getElementById('${id}').remove()">&times;</button>
            </div>`;
        container.insertAdjacentHTML('beforeend', html);
        setTimeout(() => { const el = document.getElementById(id); if (el) el.remove(); }, 5000);
    }

    function formatFileSize(size) {
        if (size < 1024) return size + ' B';
        if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
        if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB';
        return (size / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
    }

    function formatSpeed(bytesPerSecond) {
        const n = typeof bytesPerSecond === 'number' && Number.isFinite(bytesPerSecond) ? bytesPerSecond : 0;
        if (n <= 0) return '-';
        return `${formatFileSize(Math.round(n))}/s`;
    }

    function normalizeTimestampMs(value) {
        const n = typeof value === 'number' && Number.isFinite(value) ? value : Number(value);
        if (!Number.isFinite(n)) return Date.now();
        return n < 1e12 ? n * 1000 : n;
    }

    function escapeHtml(str) {
        const s = str == null ? '' : String(str);
        return s
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function joinPath(base, name) {
        const b = base == null ? '' : String(base);
        const n = name == null ? '' : String(name);
        if (!b) return n;
        if (!n) return b;
        const sep = b.includes('\\') ? '\\' : '/';
        if (b.endsWith(sep)) return b + n;
        return b + sep + n;
    }

    function initDownloadWebSocket() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws`;

        try {
            state.websocket = new WebSocket(wsUrl);
            state.websocket.onopen = function() {
                state.reconnectAttempts = 0;
                state.websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
            };
            state.websocket.onmessage = function(event) {
                handleWebSocketMessage(event.data);
            };
            state.websocket.onclose = function() {
                if (state.started && state.reconnectAttempts < state.maxReconnectAttempts) {
                    state.reconnectAttempts++;
                    setTimeout(initDownloadWebSocket, state.reconnectInterval);
                }
            };
            state.websocket.onerror = function(error) {
                console.error('WebSocket Error:', error);
            };
        } catch (error) {
            console.error('WebSocket Init Failed:', error);
        }
    }

    function handleWebSocketMessage(message) {
        try {
            const data = JSON.parse(message);
            if (!data || !data.type) return;
            switch (data.type) {
                case 'notification':
                    showToast(data.title || '通知', data.message || '', data.level || 'info');
                    break;
                case 'download_update':
                    if (data.taskId) {
                        updateDownloadItem(data.taskId, data);
                        if (data.state) {
                            if (data.state === 'COMPLETED') {
                                showToast('下载完成', `文件 ${data.fileName || data.taskId} 下载完成`, 'success');
                            } else if (data.state === 'FAILED') {
                                showToast('下载失败', `文件 ${data.fileName || data.taskId} 下载失败: ${data.errorMessage || '未知错误'}`, 'error');
                            }
                        }
                    }
                    break;
                case 'download_progress':
                    if (data.taskId) updateDownloadProgress(data.taskId, data);
                    break;
            }
        } catch (error) {
            console.error('处理WebSocket消息失败:', error);
        }
    }

    function refreshDownloads() {
        const downloadsList = document.getElementById('downloadsList');
        if (!downloadsList) return;
        downloadsList.innerHTML = `<div class="loading-spinner"><div class="spinner"></div></div>`;

        fetch('/api/downloads/list')
            .then(response => response.json())
            .then(data => {
                if (data && data.success) {
                    state.downloads = data.downloads || [];
                    renderDownloadsList();
                    updateStats();
                } else {
                    throw new Error((data && data.error) ? data.error : '获取下载列表失败');
                }
            })
            .catch(error => {
                console.error('Error:', error);
                downloadsList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                        <div class="empty-state-title">加载失败</div>
                        <div class="empty-state-text">${error.message || '网络错误'}</div>
                        <button class="btn btn-primary" onclick="DownloadManager.refreshDownloads()">重试</button>
                    </div>
                `;
            });
    }

    function renderDownloadsList() {
        const downloadsList = document.getElementById('downloadsList');
        if (!downloadsList) return;
        const downloads = state.downloads || [];
        if (downloads.length === 0) {
            downloadsList.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon"><i class="fas fa-download"></i></div>
                    <div class="empty-state-title">没有下载任务</div>
                    <div class="empty-state-text">点击"创建下载任务"按钮添加新的下载任务</div>
                    <button class="btn btn-primary" onclick="DownloadManager.openCreateDownloadModal()">创建下载任务</button>
                </div>
            `;
            return;
        }

        const getDownloadCreatedAtMs = (download) => {
            const value = download && download.createdAt != null ? download.createdAt : null;
            if (value === null) return 0;
            if (typeof value === 'number' && Number.isFinite(value)) return value < 1e12 ? value * 1000 : value;
            if (typeof value === 'string') {
                const trimmed = value.trim();
                if (!trimmed) return 0;
                if (/^\d+$/.test(trimmed)) {
                    const num = Number(trimmed);
                    if (!Number.isFinite(num)) return 0;
                    return num < 1e12 ? num * 1000 : num;
                }
                const ms = new Date(trimmed).getTime();
                return Number.isFinite(ms) ? ms : 0;
            }
            const ms = new Date(value).getTime();
            return Number.isFinite(ms) ? ms : 0;
        };

        const sortedDownloads = [...downloads].sort((a, b) => {
            const diff = getDownloadCreatedAtMs(b) - getDownloadCreatedAtMs(a);
            if (diff !== 0) return diff;
            return String(b && b.taskId ? b.taskId : '').localeCompare(String(a && a.taskId ? a.taskId : ''));
        });

        let html = '';
        sortedDownloads.forEach(download => {
            const status = download && download.state ? download.state : 'IDLE';
            let statusText = '正在开始……';
            let statusIcon = 'fa-question-circle';
            let statusClass = 'status-idle';

            switch (status) {
                case 'DOWNLOADING':
                    statusText = '下载中'; statusIcon = 'fa-spinner fa-spin'; statusClass = 'status-downloading';
                    break;
                case 'IDLE':
                    statusText = '等待中'; statusIcon = 'fa-clock'; statusClass = 'status-idle';
                    break;
                case 'COMPLETED':
                    statusText = '已完成'; statusIcon = 'fa-check-circle'; statusClass = 'status-completed';
                    break;
                case 'FAILED':
                    statusText = '失败'; statusIcon = 'fa-exclamation-circle'; statusClass = 'status-failed';
                    break;
                case 'PAUSED':
                    statusText = '已暂停'; statusIcon = 'fa-pause-circle'; statusClass = 'status-paused';
                    break;
            }

            let actionButtons = '';
            const taskId = download && download.taskId ? String(download.taskId) : '';
            if (taskId) {
                if (status === 'DOWNLOADING') {
                    actionButtons = `
                        <button class="btn-icon" onclick="DownloadManager.pauseDownload('${taskId}')" title="暂停">
                            <i class="fas fa-pause"></i>
                        </button>
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="删除">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                } else if (status === 'IDLE' || status === 'FAILED' || status === 'PAUSED') {
                    actionButtons = `
                        <button class="btn-icon primary" onclick="DownloadManager.resumeDownload('${taskId}')" title="恢复">
                            <i class="fas fa-play"></i>
                        </button>
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="删除">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                } else if (status === 'COMPLETED') {
                    actionButtons = `
                        <button class="btn-icon danger" onclick="DownloadManager.deleteDownload('${taskId}')" title="删除">
                            <i class="fas fa-trash"></i>
                        </button>
                    `;
                }
            }

            const totalBytes = download && download.totalBytes ? Number(download.totalBytes) : 0;
            const downloadedBytes = download && download.downloadedBytes ? Number(download.downloadedBytes) : 0;
            const progressRatio = totalBytes > 0 ? (downloadedBytes / totalBytes) : 0;
            const progressPercent = Math.round(progressRatio * 100);
            const speedInfo = taskId && state.speedByTaskId ? state.speedByTaskId[taskId] : null;
            const speedText = status === 'DOWNLOADING' ? formatSpeed(speedInfo && typeof speedInfo.speedBps === 'number' ? speedInfo.speedBps : 0) : '-';

            const fileNameText = download && download.fileName ? String(download.fileName) : '未知文件';
            const targetPathText = download && download.targetPath ? String(download.targetPath) : '';
            const urlText = download && download.url ? String(download.url) : '';
            const fullPathText = joinPath(targetPathText, download && download.fileName ? String(download.fileName) : '');

            html += `
                <div class="download-item" id="download-${taskId}">
                    <div class="download-icon-wrapper">
                        <i class="fas fa-file-download"></i>
                    </div>
                    <div class="download-details">
                        <div class="download-name" title="${escapeHtml((download && (download.fileName || download.url)) ? String(download.fileName || download.url) : '')}">
                            ${escapeHtml(fileNameText)}
                        </div>
                        <div class="download-meta">
                            ${totalBytes ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                        </div>
                        ${(fullPathText || urlText) ? `
                            <div class="download-meta" style="margin-top: 0.25rem; flex-direction: column; gap: 0.25rem; align-items: flex-start;">
                                ${fullPathText ? `<span style="width: 100%; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(fullPathText)}"><i class="fas fa-folder-open"></i> ${escapeHtml(fullPathText)}</span>` : ''}
                                ${urlText ? `<span style="width: 100%; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(urlText)}"><i class="fas fa-link"></i> ${escapeHtml(urlText)}</span>` : ''}
                            </div>
                        ` : ''}
                        ${(status === 'DOWNLOADING' || status === 'PAUSED' || status === 'IDLE') ? `
                            <div class="progress-bar-container">
                                <div class="progress-bar">
                                    <div class="progress-fill" style="width: ${progressPercent}%"></div>
                                </div>
                                <div class="download-meta" style="margin-top: 0.25rem;">
                                    <span><i class="fas fa-percentage"></i> ${progressPercent}%</span>
                                    <span><i class="fas fa-gauge-high"></i> ${speedText}</span>
                                    ${downloadedBytes ? `<span><i class="fas fa-download"></i> ${formatFileSize(downloadedBytes)}</span>` : ''}
                                    ${totalBytes ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                                </div>
                            </div>
                        ` : ''}
                    </div>
                    <div class="download-status-badge ${statusClass}">
                        <i class="fas ${statusIcon}"></i> <span>${statusText}</span>
                    </div>
                    <div class="download-actions">${actionButtons}</div>
                </div>
            `;
        });

        downloadsList.innerHTML = html;
    }

    function updateDownloadItem(taskId, data) {
        const idx = (state.downloads || []).findIndex(d => d && d.taskId === taskId);
        if (idx < 0) return;
        state.downloads[idx] = { ...state.downloads[idx], ...data };
        renderDownloadsList();
        updateStats();
    }

    function updateDownloadProgress(taskId, data) {
        const idx = (state.downloads || []).findIndex(d => d && d.taskId === taskId);
        if (idx < 0) return;

        const nowMs = normalizeTimestampMs(data && data.timestamp);
        const downloadedBytesNumber = data && data.downloadedBytes > 0 ? Number(data.downloadedBytes) : 0;
        const prevSpeed = state.speedByTaskId && state.speedByTaskId[taskId] ? state.speedByTaskId[taskId] : null;
        if (!state.speedByTaskId) state.speedByTaskId = {};
        if (prevSpeed && typeof prevSpeed.atMs === 'number' && typeof prevSpeed.bytes === 'number') {
            const dtMs = nowMs - prevSpeed.atMs;
            const deltaBytes = downloadedBytesNumber - prevSpeed.bytes;
            if (dtMs > 0 && deltaBytes >= 0) {
                state.speedByTaskId[taskId] = { atMs: nowMs, bytes: downloadedBytesNumber, speedBps: (deltaBytes * 1000) / dtMs };
            } else {
                state.speedByTaskId[taskId] = { atMs: nowMs, bytes: downloadedBytesNumber, speedBps: prevSpeed.speedBps };
            }
        } else {
            state.speedByTaskId[taskId] = { atMs: nowMs, bytes: downloadedBytesNumber, speedBps: 0 };
        }

        state.downloads[idx] = {
            ...state.downloads[idx],
            downloadedBytes: data.downloadedBytes,
            totalBytes: data.totalBytes,
            partsCompleted: data.partsCompleted,
            partsTotal: data.partsTotal,
            progressRatio: data.progressRatio
        };

        const downloadElement = document.getElementById(`download-${taskId}`);
        if (downloadElement) {
            const totalBytes = data.totalBytes > 0 ? Number(data.totalBytes) : 0;
            const downloadedBytes = downloadedBytesNumber;
            const progressRatio = totalBytes > 0 ? (downloadedBytes / totalBytes) : 0;
            const progressPercent = Math.round(progressRatio * 100);
            const current = state.downloads[idx] || {};
            const speedText = current && current.state === 'DOWNLOADING'
                ? formatSpeed(state.speedByTaskId && state.speedByTaskId[taskId] ? state.speedByTaskId[taskId].speedBps : 0)
                : '-';

            let progressBarContainer = downloadElement.querySelector('.progress-bar-container');
            if (!progressBarContainer) {
                const detailsDiv = downloadElement.querySelector('.download-details');
                progressBarContainer = document.createElement('div');
                progressBarContainer.className = 'progress-bar-container';
                if (detailsDiv) detailsDiv.appendChild(progressBarContainer);
            }

            let progressBar = progressBarContainer ? progressBarContainer.querySelector('.progress-bar') : null;
            if (!progressBar && progressBarContainer) {
                progressBar = document.createElement('div');
                progressBar.className = 'progress-bar';
                progressBarContainer.appendChild(progressBar);
            }

            let progressFill = progressBar ? progressBar.querySelector('.progress-fill') : null;
            if (!progressFill && progressBar) {
                progressFill = document.createElement('div');
                progressFill.className = 'progress-fill';
                progressBar.appendChild(progressFill);
            }

            if (progressFill) progressFill.style.width = `${progressPercent}%`;

            let progressMeta = progressBarContainer ? progressBarContainer.querySelector('.download-meta') : null;
            if (!progressMeta && progressBarContainer) {
                progressMeta = document.createElement('div');
                progressMeta.className = 'download-meta';
                progressMeta.style.marginTop = '0.25rem';
                progressBarContainer.appendChild(progressMeta);
            }

            if (progressMeta) {
                progressMeta.innerHTML = `
                    <span><i class="fas fa-percentage"></i> ${progressPercent}%</span>
                    <span><i class="fas fa-gauge-high"></i> ${speedText}</span>
                    <span><i class="fas fa-download"></i> ${formatFileSize(downloadedBytes)}</span>
                    ${totalBytes > 0 ? `<span><i class="fas fa-hdd"></i> ${formatFileSize(totalBytes)}</span>` : ''}
                `;
            }
        }

        updateStats();
    }

    function updateStats() {
        const downloads = state.downloads || [];
        const activeCount = downloads.filter(d => d && d.state === 'DOWNLOADING').length;
        const pendingCount = downloads.filter(d => d && d.state === 'IDLE').length;
        const completedCount = downloads.filter(d => d && d.state === 'COMPLETED').length;
        const totalCount = downloads.length;

        const activeEl = document.getElementById('activeDownloadsCount');
        const pendingEl = document.getElementById('pendingDownloadsCount');
        const completedEl = document.getElementById('completedDownloadsCount');
        const totalEl = document.getElementById('totalDownloadsCount');
        if (activeEl) activeEl.textContent = String(activeCount);
        if (pendingEl) pendingEl.textContent = String(pendingCount);
        if (completedEl) completedEl.textContent = String(completedCount);
        if (totalEl) totalEl.textContent = String(totalCount);
    }

    function openHfCrawlerModal() {
        const modal = document.getElementById('hfCrawlerModal');
        if (!modal) return;
        modal.classList.add('show');
        const queryInput = document.getElementById('hfQueryInput');
        if (queryInput) queryInput.focus();
    }

    function hfEscapeHtml(str) {
        const s = str == null ? '' : String(str);
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function hfEscapeAttr(str) {
        return hfEscapeHtml(str).replace(/`/g, '&#96;');
    }

    function hfSetText(id, text) {
        const el = document.getElementById(id);
        if (!el) return;
        el.textContent = text == null || String(text).trim() === '' ? '-' : String(text);
    }

    async function hfCopyToClipboard(text) {
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

    function hfRenderSearchResults() {
        const container = document.getElementById('hfSearchResults');
        if (!container) return;
        const countLabel = document.getElementById('hfHitsCountLabel');
        if (countLabel) countLabel.textContent = String(state.hfModalHits.length || 0);

        if (!state.hfModalHits || state.hfModalHits.length === 0) {
            container.innerHTML = `<div class="empty-state">未找到结果</div>`;
            return;
        }

        container.innerHTML = state.hfModalHits.map(hit => {
            const downloads = hit.downloads != null ? `<span class="hf-badge"><i class="fas fa-download"></i> ${hit.downloads}</span>` : '';
            const likes = hit.likes != null ? `<span class="hf-badge"><i class="fas fa-thumbs-up"></i> ${hit.likes}</span>` : '';
            const params = hit.parameters ? `<span class="hf-badge"><i class="fas fa-sliders-h"></i> ${hfEscapeHtml(hit.parameters)}</span>` : '';
            const tag = hit.pipelineTag ? `<span class="hf-badge"><i class="fas fa-tag"></i> ${hfEscapeHtml(hit.pipelineTag)}</span>` : '';
            const mod = hit.lastModified ? `<span class="hf-badge"><i class="fas fa-clock"></i> ${hfEscapeHtml(hit.lastModified)}</span>` : '';
            return `
                <div class="hf-item">
                    <div style="min-width: 0; flex: 1;">
                        <div class="hf-item-title mono" onclick="DownloadManager.hfSelectRepo('${hfEscapeAttr(hit.repoId)}')">${hfEscapeHtml(hit.repoId)}</div>
                        <div class="hf-item-meta">
                            ${downloads}
                            ${likes}
                            ${params}
                            ${tag}
                            ${mod}
                        </div>
                    </div>
                    <div class="hf-actions">
                        <button class="btn btn-secondary btn-sm" onclick="DownloadManager.hfOpenModelPage('${hfEscapeAttr(hit.modelUrl)}')">
                            <i class="fas fa-external-link-alt"></i>
                            打开
                        </button>
                        <button class="btn btn-primary btn-sm" onclick="DownloadManager.hfSelectRepo('${hfEscapeAttr(hit.repoId)}')">
                            <i class="fas fa-list"></i>
                            GGUF
                        </button>
                    </div>
                </div>
            `;
        }).join('');
    }

    function hfGetSplitInfoFromPath(path) {
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

    function hfGroupGgufFiles(files) {
        const list = Array.isArray(files) ? files : [];
        const groups = new Map();
        const singles = [];

        for (const f of list) {
            const path = f && f.path != null ? String(f.path) : '';
            const info = hfGetSplitInfoFromPath(path);
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

    async function hfCopyGroupLinks(groupIndex) {
        const g = state.hfModalGgufGroups && state.hfModalGgufGroups[groupIndex] ? state.hfModalGgufGroups[groupIndex] : null;
        if (!g) return;
        const links = (g.files || []).map(f => f && f.downloadUrl ? String(f.downloadUrl) : '').filter(Boolean);
        if (!links.length) {
            showToast('提示', '没有可复制的下载链接', 'info');
            return;
        }
        const ok = await hfCopyToClipboard(links.join('\n'));
        if (ok) showToast('已复制', `已复制 ${links.length} 条链接`, 'success');
        else showToast('复制失败', '无法写入剪贴板', 'error');
    }

    async function getDownloadPath() {
        return fetch('/api/downloads/path/get')
            .then(response => response.json())
            .then(data => {
                return (data && data.path) ? data.path : '';
            })
            .catch(error => {
                console.error('获取下载路径失败:', error);
                return '';
            });
    }

    function hfSuggestFileName(path) {
        const p = path == null ? '' : String(path);
        const idx = p.lastIndexOf('/');
        const name = idx >= 0 ? p.substring(idx + 1) : p;
        return name.trim();
    }

    function hfIsMmprojFilePath(path) {
        const name = hfSuggestFileName(path);
        if (!name) return false;
        const lower = name.toLowerCase();
        return lower.endsWith('.gguf') && lower.includes('mmproj');
    }

    function hfIsMmprojGroup(group) {
        if (!group) return false;
        const p = (group.displayPath || group.key || '');
        return hfIsMmprojFilePath(p);
    }

    function hfGetGroupTotalSize(group) {
        if (!group) return 0;
        const n = group.totalSize != null ? Number(group.totalSize) : NaN;
        if (Number.isFinite(n) && n > 0) return n;
        let total = 0;
        for (const f of (group.files || [])) {
            if (!f) continue;
            const s = f.size != null ? Number(f.size) : (f.lfsSize != null ? Number(f.lfsSize) : NaN);
            if (Number.isFinite(s) && s > 0) total += s;
        }
        return total;
    }

    function hfPickBestMmprojGroup(groups) {
        const list = Array.isArray(groups) ? groups : [];
        let best = null;
        let bestSize = -1;
        for (const g of list) {
            if (!hfIsMmprojGroup(g)) continue;
            const size = hfGetGroupTotalSize(g);
            if (size > bestSize) {
                best = g;
                bestSize = size;
            }
        }
        return best;
    }

    async function hfCreateDownloadsBatch(files) {
        const list = Array.isArray(files) ? files : [];
        if (!list.length) {
            showToast('提示', '没有可创建的下载任务', 'info');
            return;
        }
        const downloadPath = await getDownloadPath();
        if (!downloadPath) {
            showToast('错误', '未设置下载路径', 'error');
            return;
        }

        let okCount = 0;
        let failCount = 0;
        for (const f of list) {
            const url = f && f.downloadUrl ? String(f.downloadUrl).trim() : '';
            const p = f && f.path ? String(f.path) : '';
            if (!url) {
                failCount++;
                continue;
            }
            const payload = { url, path: downloadPath };
            const fileName = hfSuggestFileName(p);
            if (fileName) payload.fileName = fileName;
            try {
                const resp = await fetch('/api/downloads/create', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await resp.json().catch(() => null);
                if (data && data.success) okCount++;
                else failCount++;
            } catch (e) {
                failCount++;
            }
        }

        if (okCount > 0 && failCount === 0) showToast('成功', `已创建 ${okCount} 个下载任务`, 'success');
        else if (okCount > 0) showToast('提示', `已创建 ${okCount} 个任务，失败 ${failCount} 个`, 'info');
        else showToast('错误', '创建下载任务失败', 'error');

        closeModal('hfCrawlerModal');
        refreshDownloads();
    }

    async function hfCreateDownloadFromGroup(groupIndex) {
        const g = state.hfModalGgufGroups && state.hfModalGgufGroups[groupIndex] ? state.hfModalGgufGroups[groupIndex] : null;
        if (!g) return;
        const bestMmproj = hfPickBestMmprojGroup(state.hfModalMmprojGroups);
        const out = [];
        for (const f of (g.files || [])) {
            if (f && f.downloadUrl) out.push(f);
        }
        if (bestMmproj && bestMmproj.files && bestMmproj.files.length) {
            for (const f of (bestMmproj.files || [])) {
                if (f && f.downloadUrl) out.push(f);
            }
        }
        const seen = new Set();
        const unique = [];
        for (const f of out) {
            const url = f && f.downloadUrl ? String(f.downloadUrl).trim() : '';
            if (!url || seen.has(url)) continue;
            seen.add(url);
            unique.push(f);
        }
        await hfCreateDownloadsBatch(unique);
    }

    function hfRenderGgufResults() {
        const container = document.getElementById('hfGgufResults');
        if (!container) return;
        const allGroups = hfGroupGgufFiles(state.hfModalGgufFiles);
        state.hfModalMmprojGroups = allGroups.filter(hfIsMmprojGroup);
        state.hfModalGgufGroups = allGroups.filter(g => !hfIsMmprojGroup(g));
        const label = document.getElementById('hfSelectedLabel');
        if (label) {
            const parts = [];
            if (state.hfModalSelected) parts.push(state.hfModalSelected);
            const visibleFiles = (state.hfModalGgufFiles || []).filter(f => f && !hfIsMmprojFilePath(f.path));
            if (visibleFiles && visibleFiles.length != null) parts.push(`files=${visibleFiles.length}`);
            if (state.hfModalGgufGroups && state.hfModalGgufGroups.length != null) parts.push(`items=${state.hfModalGgufGroups.length}`);
            if (state.hfModalTreeError) parts.push('warn');
            label.textContent = parts.length ? parts.join(' ') : '-';
        }

        if (!state.hfModalSelected) {
            container.innerHTML = `<div class="empty-state">选择一个模型或粘贴 RepoId 解析</div>`;
            return;
        }
        if (!state.hfModalGgufGroups || state.hfModalGgufGroups.length === 0) {
            container.innerHTML = `<div class="empty-state">未找到 GGUF 文件</div>`;
            return;
        }

        container.innerHTML = state.hfModalGgufGroups.map((group, idx) => {
            const sizeText = group.totalSize != null ? formatFileSize(group.totalSize) : '';
            const sizeBadge = sizeText ? `<span class="hf-badge"><i class="fas fa-hdd"></i> ${sizeText}</span>` : '';
            const lfsBadge = group.hasLfs ? `<span class="hf-badge"><i class="fas fa-database"></i> LFS</span>` : '';
            const shardBadge = group.isSplit ? `<span class="hf-badge"><i class="fas fa-th-large"></i> 分片 ${group.partCount}/${group.partTotal}</span>` : '';
            const path = group.displayPath || '';
            return `
                <div class="hf-item">
                    <div style="min-width: 0; flex: 1;">
                        <div class="hf-item-title mono" onclick="DownloadManager.hfCopyGroupLinks(${idx})">${hfEscapeHtml(path)}</div>
                        <div class="hf-item-meta">
                            ${sizeBadge}
                            ${lfsBadge}
                            ${shardBadge}
                        </div>
                    </div>
                    <div class="hf-actions">
                        <button class="btn btn-secondary btn-sm" onclick="DownloadManager.hfCopyGroupLinks(${idx})">
                            <i class="fas fa-copy"></i>
                            复制链接
                        </button>
                        <button class="btn btn-primary btn-sm" onclick="DownloadManager.hfCreateDownloadFromGroup(${idx})">
                            <i class="fas fa-plus"></i>
                            创建任务
                        </button>
                    </div>
                </div>
            `;
        }).join('');
    }

    function hfOpenModelPage(url) {
        const u = url == null ? '' : String(url).trim();
        if (!u) return;
        window.open(u, '_blank', 'noopener');
    }

    function hfCreateDownload(downloadUrl, ggufPath) {
        const url = downloadUrl == null ? '' : String(downloadUrl).trim();
        if (!url) {
            showToast('错误', '下载链接为空', 'error');
            return;
        }
        const fileName = hfSuggestFileName(ggufPath);
        const urlInput = document.getElementById('downloadUrl');
        const nameInput = document.getElementById('downloadFileName');
        if (urlInput) urlInput.value = url;
        if (nameInput && fileName) nameInput.value = fileName;
        closeModal('hfCrawlerModal');
        openCreateDownloadModal();
    }

    async function hfSearchFromModal() {
        const queryEl = document.getElementById('hfQueryInput');
        const limitEl = document.getElementById('hfLimitSelect');
        const baseEl = document.getElementById('hfBaseSelectModal');
        const query = queryEl ? String(queryEl.value || '').trim() : '';
        if (!query) {
            showToast('提示', '请输入搜索关键字', 'info');
            return;
        }
        const limit = limitEl ? String(limitEl.value || '50') : '50';
        const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
        const container = document.getElementById('hfSearchResults');
        if (container) container.innerHTML = `<div class="empty-state">正在搜索...</div>`;
        state.hfModalHits = [];
        try {
            const resp = await fetch(`/api/hf/search?query=${encodeURIComponent(query)}&limit=${encodeURIComponent(limit)}&base=${encodeURIComponent(base)}`);
            const data = await resp.json();
            if (!data || data.success !== true) {
                throw new Error((data && data.error) ? data.error : '搜索失败');
            }
            state.hfModalHits = data.data && data.data.hits ? data.data.hits : [];
            hfRenderSearchResults();
        } catch (e) {
            state.hfModalHits = [];
            hfRenderSearchResults();
            showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
        }
    }

    async function hfSelectRepo(repoId) {
        const id = repoId == null ? '' : String(repoId).trim();
        const repoEl = document.getElementById('hfRepoInput');
        if (repoEl) repoEl.value = id;
        await hfLoadGguf(id);
    }

    async function hfParseRepoFromModal() {
        const repoEl = document.getElementById('hfRepoInput');
        const input = repoEl ? String(repoEl.value || '').trim() : '';
        if (!input) {
            showToast('提示', '请输入 RepoId 或 URL', 'info');
            return;
        }
        await hfLoadGguf(input);
    }

    async function hfLoadGguf(input) {
        const baseEl = document.getElementById('hfBaseSelectModal');
        const base = baseEl ? String(baseEl.value || 'mirror') : 'mirror';
        const id = input == null ? '' : String(input).trim();
        if (!id) return;
        state.hfModalSelected = id;
        state.hfModalGgufFiles = [];
        state.hfModalTreeError = null;
        hfSetText('hfSelectedLabel', state.hfModalSelected);
        const container = document.getElementById('hfGgufResults');
        if (container) container.innerHTML = `<div class="empty-state">正在解析 GGUF 文件...</div>`;
        try {
            const resp = await fetch(`/api/hf/gguf?model=${encodeURIComponent(id)}&base=${encodeURIComponent(base)}`);
            const data = await resp.json();
            if (!data || data.success !== true) {
                throw new Error((data && data.error) ? data.error : '解析失败');
            }
            const result = data.data || {};
            state.hfModalSelected = result.repoId || id;
            state.hfModalTreeError = result.treeError || null;
            state.hfModalGgufFiles = result.ggufFiles || [];
            if (state.hfModalTreeError) showToast('提示', state.hfModalTreeError, 'info');
            hfRenderGgufResults();
        } catch (e) {
            state.hfModalGgufFiles = [];
            hfRenderGgufResults();
            showToast('错误', e && e.message ? e.message : '网络请求失败', 'error');
        }
    }

    function openCreateDownloadModal() {
        getDownloadPath().then(path => {
            if (path) {
                const el = document.getElementById('downloadPath');
                if (el) el.value = path;
            }
        });
        const modal = document.getElementById('createDownloadModal');
        if (modal) modal.classList.add('show');
    }

    function submitCreateDownload() {
        const urlEl = document.getElementById('downloadUrl');
        const pathEl = document.getElementById('downloadPath');
        const fileNameEl = document.getElementById('downloadFileName');
        const url = urlEl ? String(urlEl.value || '').trim() : '';
        const path = pathEl ? String(pathEl.value || '').trim() : '';
        const fileName = fileNameEl ? String(fileNameEl.value || '').trim() : '';

        if (!url) {
            showToast('错误', '请输入下载URL', 'error');
            return;
        }
        if (!path) {
            showToast('错误', '请输入保存路径', 'error');
            return;
        }

        const payload = { url, path };
        if (fileName) payload.fileName = fileName;

        fetch('/api/downloads/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) {
                showToast('成功', '下载任务创建成功', 'success');
                closeModal('createDownloadModal');
                refreshDownloads();
            } else {
                showToast('错误', (data && data.error) ? data.error : '创建下载任务失败', 'error');
            }
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
    }

    function pauseDownload(taskId) {
        fetch('/api/downloads/pause', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) showToast('成功', '下载任务已暂停', 'success');
            else showToast('错误', (data && data.error) ? data.error : '暂停任务失败', 'error');
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
    }

    function resumeDownload(taskId) {
        fetch('/api/downloads/resume', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) showToast('成功', '下载任务已恢复', 'success');
            else showToast('错误', (data && data.error) ? data.error : '恢复任务失败', 'error');
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
    }

    function deleteDownload(taskId) {
        if (!confirm('确定要删除这个下载任务吗？')) return;

        fetch('/api/downloads/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ taskId })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.success) {
                showToast('成功', '下载任务已删除', 'success');
                refreshDownloads();
            } else {
                showToast('错误', (data && data.error) ? data.error : '删除任务失败', 'error');
            }
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
    }

    function openSettingsModal() {
        getDownloadPath().then(path => {
            if (path) {
                const el = document.getElementById('defaultDownloadPath');
                if (el) el.value = path;
            }
        });
        const modal = document.getElementById('settingsModal');
        if (modal) modal.classList.add('show');
    }

    function saveSettings() {
        const el = document.getElementById('defaultDownloadPath');
        const path = el ? String(el.value || '').trim() : '';
        if (!path) {
            showToast('错误', '请输入下载路径', 'error');
            return;
        }

        fetch('/api/downloads/path/set', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        })
        .then(response => response.json())
        .then(data => {
            if (data && data.path) {
                showToast('成功', '下载路径设置成功', 'success');
                closeModal('settingsModal');
            } else {
                showToast('错误', '设置下载路径失败', 'error');
            }
        })
        .catch(() => {
            showToast('错误', '网络请求失败', 'error');
        });
    }

    function start() {
        if (state.started) return;
        state.started = true;
        refreshDownloads();
        initDownloadWebSocket();
    }

    function stop() {
        state.started = false;
        state.reconnectAttempts = 0;
        try {
            if (state.websocket) state.websocket.close();
        } catch (e) {
        }
        state.websocket = null;
    }

    window.DownloadManager = {
        start,
        stop,
        refreshDownloads,
        openCreateDownloadModal,
        submitCreateDownload,
        pauseDownload,
        resumeDownload,
        deleteDownload,
        openSettingsModal,
        saveSettings,
        openHfCrawlerModal,
        hfSearchFromModal,
        hfParseRepoFromModal,
        hfSelectRepo,
        hfLoadGguf,
        hfCopyGroupLinks,
        hfCreateDownloadFromGroup,
        hfOpenModelPage
    };

    if (isDownloadHtml) {
        window.refreshDownloads = refreshDownloads;
        window.openCreateDownloadModal = openCreateDownloadModal;
        window.submitCreateDownload = submitCreateDownload;
        window.pauseDownload = pauseDownload;
        window.resumeDownload = resumeDownload;
        window.deleteDownload = deleteDownload;
        window.openSettingsModal = openSettingsModal;
        window.saveSettings = saveSettings;
        window.openHfCrawlerModal = openHfCrawlerModal;
        window.hfSearchFromModal = hfSearchFromModal;
        window.hfParseRepoFromModal = hfParseRepoFromModal;
        window.hfSelectRepo = hfSelectRepo;
        window.hfLoadGguf = hfLoadGguf;
        window.hfCopyGroupLinks = hfCopyGroupLinks;
        window.hfCreateDownloadFromGroup = hfCreateDownloadFromGroup;
        window.hfOpenModelPage = hfOpenModelPage;
        window.getDownloadPath = getDownloadPath;
        window.closeModal = closeModal;
        window.showToast = showToast;

        window.addEventListener('click', function(e) {
            if (e && e.target && e.target.classList && e.target.classList.contains('modal')) closeModal(e.target.id);
        });

        document.addEventListener('DOMContentLoaded', function() {
            start();
        });
    }
})();

