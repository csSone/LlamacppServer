let websocket = null;
let reconnectAttempts = 0;
const wsDecoder = new TextDecoder('utf-8');
let reconnectTimer = null;

// 重连配置（指数退避策略）
const wsConfig = {
    maxReconnectAttempts: 10,      // 最大重试次数
    baseReconnectInterval: 1000,   // 基础间隔 1 秒
    maxReconnectInterval: 30000,   // 最大间隔 30 秒
    enableJitter: true             // 启用抖动避免雷群效应
};

function triggerModelListLoad() {
    if (typeof loadModels !== 'function') return;
    if (window.I18N) {
        loadModels();
        return;
    }
    let done = false;
    const handler = () => {
        if (done) return;
        done = true;
        window.removeEventListener('i18n:ready', handler);
        loadModels();
    };
    window.addEventListener('i18n:ready', handler);
}

function initWebSocket() {
    // 如果处于离线状态，等待网络恢复
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        console.log('Network is offline, waiting for connection...');
        window.addEventListener('online', function onlineHandler() {
            window.removeEventListener('online', onlineHandler);
            initWebSocket();
        }, { once: true });
        return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    try {
        websocket = new WebSocket(wsUrl);
        websocket.onopen = function(event) {
            console.log('WebSocket Connected');
            reconnectAttempts = 0;
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            websocket.send(JSON.stringify({ type: 'connect', message: 'Connected', timestamp: new Date().toISOString() }));
            triggerModelListLoad();
        };
        websocket.onmessage = function(event) {
            handleWebSocketMessage(event.data);
        };
        websocket.onclose = function(event) {
            console.log('WebSocket Closed');
            reconnectAttempts++;

            // 检查是否超过最大重试次数
            if (reconnectAttempts > wsConfig.maxReconnectAttempts) {
                console.error('WebSocket reconnect limit reached');
                if (typeof showToast === 'function') {
                    showToast('Connection Failed', 'Please refresh the page to reconnect', 'error');
                }
                return;
            }

            // 指数退避计算：从 1 秒开始，指数增长，最大 30 秒
            const rawInterval = wsConfig.baseReconnectInterval * Math.pow(2, Math.max(0, reconnectAttempts - 1));
            let interval = Math.min(rawInterval, wsConfig.maxReconnectInterval);

            // 添加抖动 (±50%) 避免雷群效应
            if (wsConfig.enableJitter) {
                interval = Math.floor(interval * (0.5 + Math.random() * 0.5));
            }

            console.log(`WebSocket reconnecting in ${interval}ms (attempt ${reconnectAttempts}/${wsConfig.maxReconnectAttempts})`);

            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
            }
            reconnectTimer = setTimeout(initWebSocket, interval);
        };
        websocket.onerror = function(error) { console.error('WebSocket Error:', error); };
    } catch (error) { console.error('WebSocket Init Failed:', error); }
}

// 页面卸载时清理资源
window.addEventListener('beforeunload', function() {
    if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
    }
    if (websocket) {
        websocket.close();
        websocket = null;
    }
});

function handleWebSocketMessage(message) {
    try {
        const data = JSON.parse(message);
        if (data.type) {
            switch (data.type) {
                case 'modelLoadStart': handleModelLoadStartEvent(data); break;
                case 'modelLoad': handleModelLoadEvent(data); break;
                case 'modelStop': handleModelStopEvent(data); break;
                case 'notification': showToast(data.title || '通知', data.message || '', data.level || 'info'); break;
                case 'model_status': handleModelStatusUpdate(data); break;
                case 'model_slots': handleModelSlotsUpdate(data); break;
                case 'console':
                    {
                        const consoleMain = document.getElementById('main-console');
                        if (consoleMain && consoleMain.style && consoleMain.style.display !== 'none') {
                            let text = '';
                            if (typeof data.line64 === 'string') {
                                const bin = atob(data.line64);
                                const bytes = new Uint8Array(bin.length);
                                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                                text = wsDecoder.decode(bytes);
                            } else if (typeof data.line === 'string') {
                                text = data.line;
                            }
                            if (text && typeof appendLogLine === 'function') appendLogLine(text);
                        }
                    }
                    break;
            }
        }
    } catch (error) {}
}

function applyModelPatch(modelId, patch) {
    try {
        if (!modelId) return;
        if (!Array.isArray(currentModelsData)) return;
        const i = currentModelsData.findIndex(m => m && m.id === modelId);
        if (i < 0) return;
        const prev = currentModelsData[i] || {};
        currentModelsData[i] = Object.assign({}, prev, patch || {});
        if (typeof sortAndRenderModels === 'function') sortAndRenderModels();
        const loadedCountEl = document.getElementById('loadedModelsCount');
        if (loadedCountEl) {
            const loadedCount = currentModelsData.filter(m => m && m.isLoaded).length;
            loadedCountEl.textContent = loadedCount;
        }
    } catch (e) {}
}

function handleModelLoadStartEvent(data) {
    if (!data || !data.modelId) return;
    if (typeof showModelLoadingState === 'function') showModelLoadingState(data.modelId);
    applyModelPatch(data.modelId, { isLoading: true, isLoaded: false, status: 'stopped', port: data.port ?? null, slots: [] });
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, []);
    }
}

function handleModelStatusUpdate(data) {
    if (data.modelId && data.status) {
        applyModelPatch(data.modelId, { status: data.status });
    }
}

function handleModelLoadEvent(data) {
    if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId);
    const action = data.success ? '成功' : '失败';
    showToast('模型加载', `模型 ${data.modelId} 加载${action}`, data.success ? 'success' : 'error');

    if (window.pendingModelLoad && window.pendingModelLoad.modelId === data.modelId) {
        //closeModal('loadModelModal');
        window.pendingModelLoad = null;
    }
    if (data.success) {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: true, status: 'running', port: data.port ?? null, slots: [] });
    } else {
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] });
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, []);
    }
}

function handleModelStopEvent(data) {
    showToast('模型停止', `模型 ${data.modelId} 停止${data.success ? '成功' : '失败'}`, data.success ? 'success' : 'error');
    if (data.success) {
        if (typeof removeModelLoadingState === 'function') removeModelLoadingState(data.modelId);
        applyModelPatch(data.modelId, { isLoading: false, isLoaded: false, status: 'stopped', port: null, slots: [] });
        if (typeof updateModelSlotsDom === 'function') {
            updateModelSlotsDom(data.modelId, []);
        }
    }
}

function handleModelSlotsUpdate(data) {
    if (!data || !data.modelId) return;
    const slots = Array.isArray(data.slots) ? data.slots : [];
    const i = Array.isArray(currentModelsData) ? currentModelsData.findIndex(m => m && m.id === data.modelId) : -1;
    if (i >= 0) {
        currentModelsData[i].slots = slots;
    }
    if (typeof updateModelSlotsDom === 'function') {
        updateModelSlotsDom(data.modelId, slots);
    }
}

