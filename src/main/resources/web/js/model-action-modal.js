function cssEscapeCompat(v) {
    if (v === null || v === undefined) return '';
    const s = String(v);
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(s);
    return s.replace(/["\\#.:()[\]>,+~=*$^|?{}!\s]/g, '\\$&');
}

function getLoadModelModal() {
    return document.getElementById('loadModelModal');
}

function getLoadModelForm(modal) {
    if (modal && modal.querySelector) {
        const f = modal.querySelector('form');
        if (f) return f;
    }
    return document.getElementById('loadModelForm');
}

function findInModal(modal, selector) {
    if (modal && modal.querySelector) {
        const el = modal.querySelector(selector);
        if (el) return el;
    }
    return document.querySelector(selector);
}

function findById(modal, id) {
    const safeId = cssEscapeCompat(id);
    if (modal && modal.querySelector) {
        const el = modal.querySelector('#' + safeId);
        if (el) return el;
    }
    return document.getElementById(id);
}

function findField(modal, nameOrId) {
    if (!nameOrId) return null;
    const byId = findById(modal, nameOrId);
    if (byId) return byId;
    const safeName = cssEscapeCompat(nameOrId);
    return findInModal(modal, `[name="${safeName}"]`);
}

function findFieldByName(modal, name) {
    if (!name) return null;
    const safeName = cssEscapeCompat(name);
    return findInModal(modal, `[name="${safeName}"]`);
}

function getFieldString(modal, keys) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const k = list[i];
        const el = findField(modal, k);
        if (el && 'value' in el) return String(el.value || '');
    }
    return '';
}

function setFieldValue(modal, keys, value) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const k = list[i];
        const el = findField(modal, k);
        if (!el) continue;
        if ('checked' in el && (el.type === 'checkbox' || el.type === 'radio')) {
            el.checked = !!value;
            return true;
        }
        if ('value' in el) {
            el.value = value === null || value === undefined ? '' : String(value);
            return true;
        }
    }
    return false;
}

function setFieldBoolean01(modal, keys, boolValue) {
    const list = Array.isArray(keys) ? keys : [keys];
    for (let i = 0; i < list.length; i++) {
        const el = findField(modal, list[i]);
        if (!el) continue;
        if ('checked' in el && (el.type === 'checkbox' || el.type === 'radio')) {
            el.checked = !!boolValue;
            return true;
        }
        if ('value' in el) {
            el.value = boolValue ? '1' : '0';
            return true;
        }
    }
    return false;
}

function parseIntOrNull(v) {
    const n = parseInt(String(v || ''), 10);
    return Number.isFinite(n) ? n : null;
}

function parseFloatOrNull(v) {
    const n = parseFloat(String(v || ''));
    return Number.isFinite(n) ? n : null;
}

function getParamConfigListSafe() {
    try {
        const cfg = (window && window.paramConfig) ? window.paramConfig : (typeof paramConfig !== 'undefined' ? paramConfig : []);
        return Array.isArray(cfg) ? cfg : [];
    } catch (e) {
        return [];
    }
}

function fieldNameFromFullName(fullName) {
    const v = fullName === null || fullName === undefined ? '' : String(fullName);
    return v.replace(/^--/, '').replace(/^-/, '');
}

function isTruthyLogicValue(value) {
    if (value === null || value === undefined) return false;
    const v = String(value).trim().toLowerCase();
    if (!v) return false;
    return v === '1' || v === 'true' || v === 'on' || v === 'yes';
}

function quoteArgIfNeeded(value) {
    const v = value === null || value === undefined ? '' : String(value);
    if (!v) return '';
    if (!/\s|"/.test(v)) return v;
    return '"' + v.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
}

function splitCmdArgs(cmd) {
    const s = cmd === null || cmd === undefined ? '' : String(cmd);
    const tokens = [];
    let buf = '';
    let inQuotes = false;
    let escape = false;

    for (let i = 0; i < s.length; i++) {
        const ch = s[i];
        if (escape) {
            buf += ch;
            escape = false;
            continue;
        }
        if (ch === '\\') {
            escape = true;
            continue;
        }
        if (ch === '"') {
            inQuotes = !inQuotes;
            continue;
        }
        if (!inQuotes && /\s/.test(ch)) {
            if (buf.length > 0) {
                tokens.push(buf);
                buf = '';
            }
            continue;
        }
        buf += ch;
    }
    if (buf.length > 0) tokens.push(buf);
    return tokens;
}

function buildOptionLookupFromParamConfig(cfgList) {
    const lookup = Object.create(null);
    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName).trim();
        if (fullName) lookup[fullName] = p;
        const abbr = p.abbreviation === null || p.abbreviation === undefined ? '' : String(p.abbreviation).trim();
        if (abbr) lookup[abbr] = p;
    }
    return lookup;
}

function applyCmdToDynamicFields(modal, cmd) {
    const cfgList = getParamConfigListSafe();
    if (!cfgList.length) return;
    const optionLookup = buildOptionLookupFromParamConfig(cfgList);
    const tokens = splitCmdArgs(cmd);
    const consumed = new Array(tokens.length).fill(false);
    const valuesByField = Object.create(null);

    function isKnownOption(token) {
        if (!token) return false;
        return !!optionLookup[token];
    }

    for (let i = 0; i < tokens.length; i++) {
        const raw = tokens[i];
        if (!raw) continue;
        let opt = raw;
        let inlineVal = null;
        const eqIdx = raw.indexOf('=');
        if (eqIdx > 0) {
            const left = raw.slice(0, eqIdx);
            if (isKnownOption(left)) {
                opt = left;
                inlineVal = raw.slice(eqIdx + 1);
            }
        }

        if (!isKnownOption(opt)) continue;
        const p = optionLookup[opt];
        consumed[i] = true;
        const fullName = p && p.fullName ? String(p.fullName) : opt;
        const fieldName = fieldNameFromFullName(fullName);
        if (!fieldName) continue;
        const type = (p && p.type ? String(p.type) : 'STRING').toUpperCase();

        if (type === 'LOGIC') {
            valuesByField[fieldName] = '1';
            continue;
        }

        let v = inlineVal;
        if (v === null) {
            const next = (i + 1) < tokens.length ? tokens[i + 1] : null;
            if (next !== null && next !== undefined && !isKnownOption(next)) {
                v = next;
                consumed[i + 1] = true;
                i++;
            }
        }
        if (v !== null && v !== undefined) valuesByField[fieldName] = String(v);
    }

    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const type = (p.type === null || p.type === undefined) ? 'STRING' : String(p.type);
        if (type.toUpperCase() !== 'LOGIC') continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName);
        const fieldName = fieldNameFromFullName(fullName);
        if (!fieldName) continue;
        if (valuesByField[fieldName] !== '1') valuesByField[fieldName] = '0';
    }

    const entries = Object.keys(valuesByField);
    for (let i = 0; i < entries.length; i++) {
        const k = entries[i];
        setFieldValue(modal, [k, 'param_' + k], valuesByField[k]);
    }

    const extras = [];
    for (let i = 0; i < tokens.length; i++) {
        if (consumed[i]) continue;
        const t = tokens[i];
        if (!t) continue;
        extras.push(quoteArgIfNeeded(t));
    }
    const extraStr = extras.join(' ').trim();
    if (extraStr) setFieldValue(modal, ['extraParams'], extraStr);
}

function extractLaunchConfigFromGetResponse(res, modelId) {
    if (!(res && res.success)) return {};
    const data = res.data;
    if (!data) return {};
    if (data && typeof data === 'object' && data.config && typeof data.config === 'object') {
        return data.config || {};
    }
    if (data && typeof data === 'object') {
        const direct = data[modelId];
        if (direct && typeof direct === 'object') return direct;
    }
    return {};
}

function setModelActionMode(mode) {
    const resolved = mode === 'config' ? 'config' : (mode === 'benchmark' ? 'benchmark' : 'load');
    window.__modelActionMode = resolved;
    const modal = getLoadModelModal();
    const titleText = findById(modal, 'modelActionModalTitleText') || findInModal(modal, '.modal-title span');
    const icon = findById(modal, 'modelActionModalIcon') || findInModal(modal, '.modal-title i');
    const submitBtn = findById(modal, 'modelActionSubmitBtn')
        || findInModal(modal, 'button[onclick*="submitModelAction"]')
        || findInModal(modal, '.modal-footer .btn-primary');
    const dynamicParams = findById(modal, 'dynamicParamsContainer');
    const benchmarkParams = findById(modal, 'benchmarkParamsContainer');
    const mainGpuGroup = findById(modal, 'mainGpuGroup');
    const estimateBtn = findById(modal, 'estimateVramBtn');
    const resetBtn = findById(modal, 'modelActionResetBtn');

    if (dynamicParams) dynamicParams.style.display = resolved === 'benchmark' ? 'none' : '';
    if (benchmarkParams) benchmarkParams.style.display = resolved === 'benchmark' ? '' : 'none';
    if (mainGpuGroup) mainGpuGroup.style.display = '';
    if (estimateBtn) estimateBtn.style.display = resolved === 'benchmark' ? 'none' : '';
    if (resetBtn) resetBtn.style.display = resolved === 'benchmark' ? '' : 'none';

    if (resolved === 'benchmark') {
        const hasBenchmarkFields = !!findInModal(modal, '#benchmarkParamsContainer input, #benchmarkParamsContainer select, #benchmarkParamsContainer textarea');
        if (!hasBenchmarkFields && typeof ensureBenchmarkParamsReady === 'function') {
            try { ensureBenchmarkParamsReady(); } catch (e) {}
        }
    }

    if (resolved === 'config') {
        if (titleText) titleText.textContent = '更新启动参数';
        if (icon) icon.className = 'fas fa-cog';
        if (submitBtn) submitBtn.textContent = '保存';
    } else if (resolved === 'benchmark') {
        if (titleText) titleText.textContent = '模型性能测试';
        if (icon) icon.className = 'fas fa-tachometer-alt';
        if (submitBtn) submitBtn.textContent = '开始测试';
    } else {
        if (titleText) titleText.textContent = '加载模型';
        if (icon) icon.className = 'fas fa-upload';
        if (submitBtn) submitBtn.textContent = '加载模型';
    }
}

function loadModel(modelId, modelName, mode = 'load') {
    const modal = getLoadModelModal();
    if (modal) modal.classList.add('show');

    setModelActionMode(mode);
    setFieldValue(modal, ['modelId'], modelId);
    setFieldValue(modal, ['modelName'], modelName || modelId);
    const hint = findById(modal, 'ctxSizeVramHint');
    if (hint) hint.textContent = '';
    window.__loadModelSelectedDevices = ['All'];
    window.__loadModelSelectionFromConfig = true;
    const deviceChecklistEl = findById(modal, 'deviceChecklist');
    if (deviceChecklistEl) deviceChecklistEl.innerHTML = '<div class="settings-empty">加载中...</div>';
    window.__availableDevices = [];
    window.__availableDeviceCount = 0;
    renderMainGpuSelect([], window.__loadModelSelectedDevices || []);

    const currentModel = (currentModelsData || []).find(m => m && m.id === modelId);
    const isVisionModel = !!(currentModel && (currentModel.isMultimodal || currentModel.mmproj));
    const enableVisionGroup = findById(modal, 'enableVisionGroup');
    if (enableVisionGroup) enableVisionGroup.style.display = isVisionModel ? '' : 'none';

    fetch(`/api/models/config/get?modelId=${encodeURIComponent(modelId)}`)
        .then(r => r.json()).then(data => {
            if (!(data && data.success) && mode === 'config') {
                showToast('错误', (data && data.error) ? data.error : '获取配置失败', 'error');
            }
            const config = extractLaunchConfigFromGetResponse(data, modelId);
            if (config && typeof config === 'object') {
                if (config.cmd !== undefined && config.cmd !== null && String(config.cmd).trim()) {
                    const cmdStr = String(config.cmd);
                    let applied = false;
                    let attempts = 0;
                    const maxAttempts = 60;
                    const tryApply = () => {
                        if (applied) return;
                        attempts++;
                        const cfgList = getParamConfigListSafe();
                        const ready = cfgList && cfgList.length && findInModal(modal, '[name]') && findById(modal, 'extraParams');
                        if (ready) {
                            applied = true;
                            applyCmdToDynamicFields(modal, cmdStr);
                            if (config.extraParams !== undefined && config.extraParams !== null && String(config.extraParams).trim()) {
                                setFieldValue(modal, ['extraParams'], String(config.extraParams));
                            }
                            return;
                        }
                        if (attempts >= maxAttempts) return;
                        setTimeout(tryApply, 60);
                    };
                    tryApply();
                } else if (config.extraParams !== undefined) {
                    setFieldValue(modal, ['extraParams'], config.extraParams || '');
                }

                const enableVisionEl = findField(modal, 'enableVision');
                if (enableVisionEl && 'checked' in enableVisionEl) {
                    enableVisionEl.checked = config.enableVision !== undefined ? !!config.enableVision : true;
                }
                window.__loadModelSelectedDevices = normalizeDeviceSelection(config.device);
                window.__loadModelMainGpu = normalizeMainGpu(config.mg);
                window.__loadModelSelectionFromConfig = true;
            }

            fetch('/api/llamacpp/list').then(r => r.json()).then(listData => {
                const select = findById(modal, 'llamaBinPathSelect') || findFieldByName(modal, 'llamaBinPathSelect');
                const paths = (listData && listData.success && listData.data) ? (listData.data.paths || []) : [];
                if (select) select.innerHTML = paths.map(p => `<option value="${p}">${p}</option>`).join('');

                if (config.llamaBinPath) {
                    if (select) select.value = config.llamaBinPath;
                } else {
                    fetch('/api/setting').then(rs => rs.json()).then(s => {
                        const currentBin = (s && s.success && s.data) ? s.data.llamaBin : null;
                        if (currentBin && select) select.value = currentBin;
                    }).catch(() => {});
                }

                if (select) select.onchange = function() { loadDeviceList(); };
                loadDeviceList();
            }).finally(() => {
                const modal2 = getLoadModelModal();
                if (modal2) modal2.classList.add('show');
            });
        });
}

function buildLoadModelPayload(modal) {
    const modelId = getFieldString(modal, ['modelId']);
    const modelName = getFieldString(modal, ['modelName']);
    const llamaBinPathSelect = getFieldString(modal, ['llamaBinPathSelect']);

    const selectedDevices = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedDevices.length === availableCount;

    const cmdParts = [];

    const cfgList = getParamConfigListSafe().slice().sort((a, b) => (a && a.sort ? a.sort : 0) - (b && b.sort ? b.sort : 0));
    for (let i = 0; i < cfgList.length; i++) {
        const p = cfgList[i];
        if (!p) continue;
        const fullName = p.fullName === null || p.fullName === undefined ? '' : String(p.fullName);
        if (!fullName) continue;
        const type = p.type === null || p.type === undefined ? 'STRING' : String(p.type);
        const fieldName = fieldNameFromFullName(fullName);
        if (!fieldName) continue;

        const el = findFieldByName(modal, fieldName);
        if (!el || !('value' in el)) continue;
        const rawValue = String(el.value || '');

        if (String(type).toUpperCase() === 'LOGIC') {
            if (isTruthyLogicValue(rawValue)) {
                cmdParts.push(fullName);
            }
            continue;
        }

        const trimmed = rawValue.trim();
        if (!trimmed) continue;
        cmdParts.push(fullName, quoteArgIfNeeded(trimmed));
    }

    const extraParams = getFieldString(modal, ['extraParams']).trim();
    if (extraParams) {
        cmdParts.push(extraParams);
    }

    return {
        modelId,
        modelName,
        llamaBinPathSelect,
        device: isAllSelected ? ['All'] : selectedDevices,
        mg: getSelectedMainGpu(),
        cmd: cmdParts.join(' ').trim()
    };
}

function submitModelAction() {
    const mode = window.__modelActionMode === 'config' ? 'config' : (window.__modelActionMode === 'benchmark' ? 'benchmark' : 'load');
    const modal = getLoadModelModal();
    if (mode === 'benchmark') {
        if (typeof submitModelBenchmark === 'function') {
            submitModelBenchmark();
            return;
        }
        showToast('错误', '未找到模型性能测试函数', 'error');
        return;
    }

    let payload = null;
    let modelIdForUi = getFieldString(modal, ['modelId']);
    if (mode === 'config') {
        const base = buildLoadModelPayload(modal);
        modelIdForUi = base && base.modelId ? base.modelId : modelIdForUi;
        const cfg = {
            llamaBinPath: base && base.llamaBinPathSelect ? base.llamaBinPathSelect : '',
            mg: base && base.mg !== undefined ? base.mg : -1,
            cmd: base && base.cmd ? base.cmd : '',
            device: base && Array.isArray(base.device) ? base.device : ['All']
        };
        payload = {};
        payload[modelIdForUi] = cfg;
    } else {
        payload = buildLoadModelPayload(modal);
        modelIdForUi = payload && payload.modelId ? payload.modelId : modelIdForUi;
    }

    const submitBtn = findById(modal, 'modelActionSubmitBtn')
        || findInModal(modal, 'button[onclick*="submitModelAction"]')
        || findInModal(modal, '.modal-footer .btn-primary');
    if (!modelIdForUi) {
        showToast('错误', '缺少必需的modelId参数', 'error');
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = mode === 'config' ? '保存' : '加载模型';
        }
        return;
    }
    if (mode !== 'config') {
        const llamaBinPathSelect = payload && payload.llamaBinPathSelect ? String(payload.llamaBinPathSelect).trim() : '';
        const cmd = payload && payload.cmd ? String(payload.cmd).trim() : '';
        if (!llamaBinPathSelect) {
            showToast('错误', '未提供llamaBinPath', 'error');
            return;
        }
        if (!cmd) {
            showToast('错误', '缺少必需的cmd参数', 'error');
            return;
        }
        payload.llamaBinPathSelect = llamaBinPathSelect;
        payload.cmd = cmd;
    }
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = mode === 'config'
            ? '<i class="fas fa-spinner fa-spin"></i> 保存中...'
            : '<i class="fas fa-spinner fa-spin"></i> 处理中...';
    }

    const url = mode === 'config' ? '/api/models/config/set' : '/api/models/load';
    fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (res.success) {
            if (mode === 'config') {
                showToast('成功', '启动参数已保存', 'success');
                closeModal('loadModelModal');
            } else {
                if (res.data && res.data.async) {
                    showToast('模型加载中', '正在后台加载...', 'info');
                    showModelLoadingState(modelIdForUi);
                    window.pendingModelLoad = { modelId: modelIdForUi };
                    loadModels();
                } else {
                    if (res.data && res.data.processOnly) {
                        showToast('成功', '参数已接收（未加载模型）', 'success');
                    } else {
                        showToast('成功', '模型加载成功', 'success');
                    }
                    closeModal('loadModelModal');
                    loadModels();
                }
            }
        } else {
            showToast('错误', res.error || (mode === 'config' ? '保存失败' : '加载失败'), 'error');
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = mode === 'config' ? '保存' : '加载模型';
            }
        }
    }).catch(() => {
        showToast('错误', '网络请求失败', 'error');
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = mode === 'config' ? '保存' : '加载模型';
        }
    });
}

function submitLoadModel() { submitModelAction(); }

function estimateVramAction() {
    const modal = getLoadModelModal();
    const modelId = getFieldString(modal, ['modelId']);
    if (!modelId) {
        showToast('错误', '请先选择模型', 'error');
        return;
    }
    const ctxSize = parseInt(getFieldString(modal, ['ctxSize', 'ctx-size', 'param_ctx-size']) || '2048', 10);
    const batchSize = parseInt(getFieldString(modal, ['batchSize', 'batch-size', 'param_batch-size']) || '512', 10);
    const ubatch = parseInt(getFieldString(modal, ['ubatchSize', 'ubatch-size', 'param_ubatch-size']) || '0', 10);
    let flashAttention = true;
    const flashEl = findField(modal, 'flashAttention') || findFieldByName(modal, 'flash-attn') || findById(modal, 'param_flash-attn');
    if (flashEl) {
        if ('checked' in flashEl && flashEl.type === 'checkbox') flashAttention = !!flashEl.checked;
        else {
            const v = String(flashEl.value || '').trim().toLowerCase();
            flashAttention = !(v === 'off' || v === '0' || v === 'false');
        }
    }
    const enableVisionEl = findField(modal, 'enableVision');
    const payload = {
        modelId,
        ctxSize,
        batchSize: isNaN(batchSize) || batchSize <= 0 ? null : batchSize,
        ubatchSize: isNaN(ubatch) || ubatch <= 0 ? null : ubatch,
        flashAttention,
        enableVision: enableVisionEl ? enableVisionEl.checked : true
    };
    fetch('/api/models/vram/estimate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
    }).then(r => r.json()).then(res => {
        if (res && res.success) {
            const bytes = res.data && res.data.bytes ? res.data.bytes : null;
            if (bytes) {
                const toMiB = (val) => (val / (1024 * 1024)).toFixed(1);
                const total = toMiB(bytes.totalVramRequired || 0);
                const weights = toMiB(bytes.modelWeights || 0);
                const kv = toMiB(bytes.kvCache || 0);
                const overhead = toMiB(bytes.runtimeOverhead || 0);

                const text = `预计显存：总计 ${total} MiB（权重 ${weights}，KV ${kv}，其他 ${overhead}）`;
                const hint = findById(modal, 'ctxSizeVramHint');
                if (hint) hint.textContent = text;
            } else {
                showToast('错误', '返回数据格式不正确', 'error');
            }
        } else {
            showToast('错误', (res && res.error) ? res.error : '估算失败', 'error');
        }
    }).catch(() => {
        showToast('错误', '网络请求失败', 'error');
    });
}

function estimateVram() { estimateVramAction(); }

function viewModelConfig(modelId) {
    const currentModel = (currentModelsData || []).find(m => m && m.id === modelId);
    loadModel(modelId, currentModel ? currentModel.name : modelId, 'config');
}

function normalizeDeviceSelection(device) {
    if (Array.isArray(device)) {
        const list = device
            .map(v => (v === null || v === undefined) ? '' : String(v))
            .map(v => v.trim())
            .filter(v => v.length > 0);
        const lower = list.map(v => v.toLowerCase());
        if (lower.includes('all') || lower.includes('-1')) return ['All'];
        return lower;
    }
    if (device === null || device === undefined || device === '') return [];
    const v = String(device).trim();
    if (!v) return [];
    const lower = v.toLowerCase();
    if (lower === 'all' || lower === '-1') return ['All'];
    return [lower];
}

function normalizeMainGpu(v) {
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : -1;
}

function getSelectedMainGpu() {
    const modal = getLoadModelModal();
    const el = findById(modal, 'mainGpuSelect');
    if (!el) return -1;
    const n = parseInt(el.value, 10);
    return Number.isFinite(n) ? n : -1;
}

function renderMainGpuSelect(devices, selectedKeys) {
    const modal = getLoadModelModal();
    const select = findById(modal, 'mainGpuSelect');
    if (!select) return;
    const desired = normalizeMainGpu(window.__loadModelMainGpu);
    let effectiveDevices = Array.isArray(devices) ? devices.slice() : [];
    const keys = Array.isArray(selectedKeys) ? selectedKeys : null;
    if (keys && keys.length > 0 && !keys.includes('All') && !keys.includes('-1')) {
        const filtered = [];
        const normalized = keys.map(v => String(v).trim().toLowerCase()).filter(v => v.length > 0 && v !== 'all' && v !== '-1');
        for (let i = 0; i < effectiveDevices.length; i++) {
            if (deviceMatchesSelection(effectiveDevices[i], normalized)) filtered.push(effectiveDevices[i]);
        }
        if (filtered.length > 0) effectiveDevices = filtered;
    }
    const safe = (Array.isArray(effectiveDevices) && desired >= 0 && desired < effectiveDevices.length) ? desired : -1;
    const options = ['<option value="-1">默认</option>'];
    if (Array.isArray(effectiveDevices)) {
        for (let i = 0; i < effectiveDevices.length; i++) {
            options.push(`<option value="${i}">${escapeHtml(effectiveDevices[i])}</option>`);
        }
    }
    select.innerHTML = options.join('');
    select.value = String(safe);
}

function deviceKeyFromLabel(label) {
    if (label === null || label === undefined) return '';
    const s = String(label).trim();
    const match = s.match(/^([^\s:\-]+)/);
    return match ? match[1].toLowerCase() : s.toLowerCase();
}

function deviceMatchesSelection(deviceLabel, selectedEntries) {
    const label = (deviceLabel === null || deviceLabel === undefined) ? '' : String(deviceLabel).trim();
    const labelLower = label.toLowerCase();
    const key = deviceKeyFromLabel(label);
    const entries = Array.isArray(selectedEntries) ? selectedEntries : [];
    for (let i = 0; i < entries.length; i++) {
        const raw = entries[i];
        if (raw === null || raw === undefined) continue;
        const s = String(raw).trim().toLowerCase();
        if (!s || s === 'all' || s === '-1') continue;
        if (s === key) return true;
        if (labelLower.startsWith(s)) return true;
        if (key && s.startsWith(key)) return true;
    }
    return false;
}

function getSelectedDevicesFromChecklist() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    if (!list) return [];
    const values = Array.from(list.querySelectorAll('input[type="checkbox"][data-device-key]:checked'))
        .map(el => el.getAttribute('data-device-key'))
        .map(v => {
            if (v === null || v === undefined) return '';
            const trimmed = String(v).trim();
            return trimmed.split(':')[0];
        })
        .filter(v => v.length > 0 && v !== 'All' && v !== '-1');
    values.sort((a, b) => {
        const ai = parseInt(a, 10);
        const bi = parseInt(b, 10);
        if (Number.isFinite(ai) && Number.isFinite(bi)) return ai - bi;
        return a.localeCompare(b);
    });
    return values;
}

function updateSelectedDevicesCacheFromChecklist() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    if (!list) return;
    const hasInputs = !!list.querySelector('input[type="checkbox"][data-device-key]');
    if (!hasInputs) return;
    const selectedKeys = getSelectedDevicesFromChecklist();
    const availableCount = window.__availableDeviceCount;
    const isAllSelected = Number.isFinite(availableCount) && availableCount > 0 && selectedKeys.length === availableCount;
    window.__loadModelSelectedDevices = isAllSelected ? ['All'] : selectedKeys;
}

function syncMainGpuSelectWithChecklist() {
    const modal = getLoadModelModal();
    const mainGpuEl = findById(modal, 'mainGpuSelect');
    if (mainGpuEl) window.__loadModelMainGpu = getSelectedMainGpu();
    updateSelectedDevicesCacheFromChecklist();
    renderMainGpuSelect(window.__availableDevices || [], window.__loadModelSelectedDevices || []);
    window.__loadModelSelectionFromConfig = false;
}

function loadDeviceList() {
    const modal = getLoadModelModal();
    const list = findById(modal, 'deviceChecklist');
    const allowReadFromChecklist = !window.__loadModelSelectionFromConfig;
    if (allowReadFromChecklist && list && list.querySelector('input[type="checkbox"][data-device-key]')) {
        updateSelectedDevicesCacheFromChecklist();
    }
    const mainGpuEl = findById(modal, 'mainGpuSelect');
    if (mainGpuEl && mainGpuEl.options && mainGpuEl.options.length > 1) {
        window.__loadModelMainGpu = getSelectedMainGpu();
    }
    const llamaSelect = findById(modal, 'llamaBinPathSelect') || findFieldByName(modal, 'llamaBinPathSelect');
    const llamaBinPath = llamaSelect ? llamaSelect.value : '';

    if (!llamaBinPath) {
        if (list) list.innerHTML = '<div class="settings-empty">请先选择 Llama.cpp 版本</div>';
        renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        return;
    }

    fetch(`/api/model/device/list?llamaBinPath=${encodeURIComponent(llamaBinPath)}`)
        .then(response => response.json())
        .then(data => {
            if (!list) return;
            if (!(data && data.success && data.data && Array.isArray(data.data.devices))) {
                list.innerHTML = '<div class="settings-empty">获取设备列表失败</div>';
                renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
                return;
            }
            const devices = data.data.devices;
            window.__availableDevices = devices;
            window.__availableDeviceCount = devices.length;
            const selected = window.__loadModelSelectedDevices || [];
            const defaultAll = selected.includes('All') || selected.includes('-1') || selected.length === 0;
            const items = devices.map((device) => {
                const key = deviceKeyFromLabel(device);
                const checked = (defaultAll || deviceMatchesSelection(device, selected)) ? 'checked' : '';
                return `<label style="display:flex; align-items:flex-start; gap:8px; padding:6px 6px; border-radius:8px; cursor:pointer;">
                    <input type="checkbox" ${checked} data-device-key="${escapeHtml(key)}" style="margin-top: 2px;">
                    <span style="font-size: 0.9rem; color: var(--text-primary);">${escapeHtml(device)}</span>
                </label>`;
            });
            list.innerHTML = items.length ? items.join('') : '<div class="settings-empty">未发现可用设备</div>';

            if (!window.__deviceChecklistChangeBound) {
                window.__deviceChecklistChangeBound = true;
                list.addEventListener('change', (e) => {
                    const t = e && e.target ? e.target : null;
                    if (!t) return;
                    if (t.matches && t.matches('input[type="checkbox"][data-device-key]')) {
                        syncMainGpuSelectWithChecklist();
                    }
                });
            }

            syncMainGpuSelectWithChecklist();
        })
        .catch(error => {
            if (list) list.innerHTML = `<div class="settings-empty">获取设备列表失败：${escapeHtml(error && error.message ? error.message : '')}</div>`;
            renderMainGpuSelect([], window.__loadModelSelectedDevices || []);
        });
}

function escapeHtml(str) {
    return String(str).replace(/[&<>"']/g, function(m) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]); });
}
