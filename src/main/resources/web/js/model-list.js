function loadModels() {
    const modelsList = document.getElementById('modelsList');
    fetch('/api/models/list')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                const allModels = data.models || [];
                if (data.success) {
                    const totalCount = (allModels || []).length;
                    const el = document.getElementById('totalModelsCount');
                    if (el) el.textContent = totalCount;
                }
                return fetch('/api/models/loaded')
                    .then(response => response.json())
                    .then(loadedData => {
                        const loadedModels = loadedData.success ? (loadedData.models || []) : [];
                        const loadedModelIds = loadedModels.map(m => m.id);
                        const modelsWithStatus = allModels.map(model => {
                            const isLoaded = loadedModelIds.includes(model.id);
                            const loadedModel = loadedModels.find(m => m.id === model.id);
                            return {
                                ...model,
                                isLoading: !!model.isLoading,
                                isLoaded: isLoaded,
                                status: isLoaded ? (loadedModel ? loadedModel.status : 'loaded') : 'stopped',
                                port: isLoaded && loadedModel ? loadedModel.port : null
                            };
                        });
                        currentModelsData = modelsWithStatus;
                        sortAndRenderModels();
                        if (loadedData.success) {
                            const loadedCount = (loadedData.models || []).length;
                            const el = document.getElementById('loadedModelsCount');
                            if (el) el.textContent = loadedCount;
                        }
                    });
            } else {
                throw new Error(data.error);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            modelsList.innerHTML = `
                        <div class="empty-state">
                            <div class="empty-state-icon"><i class="fas fa-exclamation-triangle"></i></div>
                            <div class="empty-state-title">加载失败</div>
                            <div class="empty-state-text">${error.message || '网络错误'}</div>
                            <button class="btn btn-primary" onclick="loadModels()">重试</button>
                        </div>
                    `;
        });
}

function getModelIcon(architecture) {
    const archName = (architecture || '').toLowerCase().replace(/[^a-z]/g, '');
    const iconMap = {
        'qwen': 'icon/qwen.png',
        'glm': 'icon/glm.png',
        'hunyuan': 'icon/hunyuan.png',
        'mistral': 'icon/mistral3.png',
        'gpt': 'icon/openai.png',
        'seed': 'icon/seed_oss.png',
        'llama': 'icon/llama.png',
        'kimi': 'icon/kimi.png',
        'minimax': 'icon/minimax.png',
        'gemma': 'icon/gemma.png',
    };
    for (const [key, icon] of Object.entries(iconMap)) {
        if (archName.includes(key)) return icon;
    }
    return null;
}

let currentModelsData = [];

function getParamsCount(name) {
    if (!name) return 0;
    name = name.toUpperCase();
    const moeMatch = name.match(/(\d+)X(\d+(?:\.\d+)?)B/);
    if (moeMatch) {
        return parseFloat(moeMatch[1]) * parseFloat(moeMatch[2]);
    }
    const match = name.match(/(\d+(?:\.\d+)?)B/);
    if (match) {
        return parseFloat(match[1]);
    }
    const matchM = name.match(/(\d+(?:\.\d+)?)M/);
    if (matchM) {
        return parseFloat(matchM[1]) / 1000;
    }
    return 0;
}

function sortAndRenderModels() {
    const sortType = document.getElementById('modelSortSelect').value;
    if (!currentModelsData) return;

    const comparator = getModelSortComparator(sortType);
    const all = [...currentModelsData];

    const favourites = [];
    const nonFavourites = [];
    all.forEach(m => {
        if (m && m.favourite) favourites.push(m);
        else nonFavourites.push(m);
    });

    favourites.sort(comparator);
    nonFavourites.sort(comparator);

    renderModelsList([...favourites, ...nonFavourites]);
}

function getModelSortComparator(sortType) {
    return (a, b) => {
        const nameA = (a.alias || a.name || '').toLowerCase();
        const nameB = (b.alias || b.name || '').toLowerCase();
        const sizeA = a.size || 0;
        const sizeB = b.size || 0;
        const paramsA = getParamsCount(a.name);
        const paramsB = getParamsCount(b.name);

        switch (sortType) {
            case 'name_asc': return nameA.localeCompare(nameB);
            case 'name_desc': return nameB.localeCompare(nameA);
            case 'size_asc': return sizeA - sizeB;
            case 'size_desc': return sizeB - sizeA;
            case 'params_asc': return paramsA - paramsB;
            case 'params_desc': return paramsB - paramsA;
            default: return 0;
        }
    };
}

function renderModelsList(models) {
    const modelsList = document.getElementById('modelsList');
    if (!models || models.length === 0) {
        modelsList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon"><i class="fas fa-box-open"></i></div>
                        <div class="empty-state-title">没有模型</div>
                        <div class="empty-state-text">请先在“模型路径配置”中添加模型目录</div>
                        <button class="btn btn-primary" onclick="showModelPathSetting()">去配置</button>
                    </div>
                `;
        return;
    }

    let html = '';
    models.forEach(model => {
        const metadata = model.metadata || {};
        const architecture = metadata.architecture || '未知';
        const isLoading = !!model.isLoading;

        let status = model.status;
        let statusText = '已停止';
        let statusIcon = 'fa-stop-circle';
        let statusClass = 'status-stopped';

        if (isLoading) {
            statusText = '加载中';
            statusIcon = 'fa-spinner fa-spin';
            statusClass = 'status-loading';
        } else if (model.isLoaded) {
            statusText = status === 'running' ? '运行中' : '已加载';
            statusIcon = status === 'running' ? 'fa-play-circle' : 'fa-check-circle';
            statusClass = status === 'running' ? 'status-running' : 'status-loaded';
        }

        const modelIcon = getModelIcon(architecture);
        const displayName = (model.alias && model.alias.trim()) ? model.alias : model.name;
        const isFavourite = !!model.favourite;

        let actionButtons = '';
        if (isLoading) {
            actionButtons = `<button class="btn-icon" disabled><i class="fas fa-spinner fa-spin"></i></button>`;
        } else if (model.isLoaded) {
            if (status === 'running') {
                actionButtons = `
                            <button class="btn-icon danger" onclick="stopModel('${model.id}')" title="停止"><i class="fas fa-stop"></i></button>
                            <button class="btn-icon" onclick="viewModelDetails('${model.id}')" title="详情"><i class="fas fa-info-circle"></i></button>
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="查看测试结果"><i class="fas fa-list"></i></button>
                            <button class="btn-icon" onclick="viewModelConfig('${model.id}')" title="查看配置"><i class="fas fa-cog"></i></button>
                            <button class="btn-icon" onclick="openSlotsModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="缓存管理"><i class="fas fa-database"></i></button>
                        `;
            } else {
                actionButtons = `
                            <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="查看测试结果"><i class="fas fa-list"></i></button>
                        `;
            }
        } else {
            actionButtons = `
                        <button class="btn-icon primary" onclick="loadModel('${model.id}', '${model.name}')" title="加载"><i class="fas fa-play"></i></button>
                        <button class="btn-icon" onclick="viewModelDetails('${model.id}')" title="详情"><i class="fas fa-info-circle"></i></button>
                        <button class="btn-icon" onclick="openModelBenchmarkDialog(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="性能测试"><i class="fas fa-rocket"></i></button>
                        <button class="btn-icon" onclick="openModelBenchmarkList(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(displayName)}'))" title="查看测试结果"><i class="fas fa-list"></i></button>
                        <button class="btn-icon" onclick="viewModelConfig('${model.id}')" title="查看配置"><i class="fas fa-cog"></i></button>
                    `;
        }

        html += `
                    <div class="model-item">
                        <button class="model-fav-btn ${isFavourite ? 'active' : ''}" onclick="toggleFavouriteModel(event, decodeURIComponent('${encodeURIComponent(model.id)}'))" title="${isFavourite ? '取消喜好' : '标记喜好'}">
                            <i class="${isFavourite ? 'fas' : 'far'} fa-star"></i>
                        </button>
                        <div class="model-icon-wrapper">
                            ${modelIcon ? `<img src="${modelIcon}" alt="${architecture}">` : `<i class="fas fa-brain"></i>`}
                        </div>
                        <div class="model-details">
                            <div class="model-name" title="${model.name}" onclick="openAliasModal(decodeURIComponent('${encodeURIComponent(model.id)}'), decodeURIComponent('${encodeURIComponent(model.name)}'), decodeURIComponent('${encodeURIComponent(model.alias || '')}'))">
                                ${displayName}
                                ${model.isMultimodal ? '<span class="vision-badge"><i class="fas fa-image"></i></span>' : ''}
                            </div>
                            <div class="model-meta">
                                <span><i class="fas fa-layer-group"></i> ${architecture}</span>
                                <span><i class="fas fa-hdd"></i> ${formatFileSize(model.size)}</span>
                                ${model.port ? `<span><i class="fas fa-network-wired"></i> ${model.port}</span>` : ''}
                            </div>
                        </div>
                        <div class="model-status-badge ${statusClass}">
                            <i class="fas ${statusIcon}"></i> <span>${statusText}</span>
                        </div>
                        <div class="model-actions">${actionButtons}</div>
                    </div>
                `;
    });
    modelsList.innerHTML = html;
    const input = document.getElementById('modelSearchInput');
    if (input) filterModels(input.value);
}

function toggleFavouriteModel(event, modelId) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }
    const idx = (currentModelsData || []).findIndex(m => m && m.id === modelId);
    if (idx < 0) return;

    const prev = !!currentModelsData[idx].favourite;
    currentModelsData[idx].favourite = !prev;
    sortAndRenderModels();

    fetch('/api/models/favourite', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId })
    })
        .then(r => r.json())
        .then(res => {
            if (!res || !res.success) {
                throw new Error((res && res.error) ? res.error : '设置喜好失败');
            }
            const favourite = !!(res.data && res.data.favourite);
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId);
            if (i >= 0) {
                currentModelsData[i].favourite = favourite;
                sortAndRenderModels();
            }
        })
        .catch(err => {
            const i = (currentModelsData || []).findIndex(m => m && m.id === modelId);
            if (i >= 0) {
                currentModelsData[i].favourite = prev;
                sortAndRenderModels();
            }
            showToast('错误', err && err.message ? err.message : '网络错误', 'error');
        });
}

function refreshModels() {
    showToast('提示', '正在刷新模型列表', 'info');
    fetch('/api/models/refresh')
        .then(response => response.json())
        .then(data => {
            if (data.success) {
                loadModels();
            } else {
                throw new Error(data.error || '刷新模型列表失败');
            }
        })
        .catch(error => {
            showToast('错误', error.message || '网络错误，请稍后重试', 'error');
            loadModels();
        });
}

