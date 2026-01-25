(function () {
    const byId = (id) => document.getElementById(id);

    function toast(title, msg, type) {
        if (typeof window.showToast === 'function') window.showToast(title, msg, type);
    }

    async function shutdownService() {
        if (!confirm('确定要停止服务吗？')) return;
        try {
            const resp = await fetch('/api/shutdown', { method: 'POST' });
            const data = await resp.json();
            if (data && data.success) {
                document.body.innerHTML = '<div style="width:100%; height:100vh; display:flex; align-items:center; justify-content:center; padding: 1.5rem;"><h1 style="margin:0; font-size: 1.5rem;">服务已停止</h1></div>';
                return;
            }
            toast('错误', (data && data.error) ? data.error : '停止失败', 'error');
        } catch (e) {
            toast('错误', '网络请求失败', 'error');
        }
    }

    function go(page) {
        if (window.MobilePage && typeof window.MobilePage.show === 'function') window.MobilePage.show(page);
    }

    function bind() {
        const llamaBtn = byId('mobileSettingsLlamaCppBtn');
        const pathBtn = byId('mobileSettingsModelPathBtn');
        const consoleBtn = byId('mobileSettingsConsoleBtn');
        const mcpBtn = byId('mobileSettingsMcpBtn');
        const shutdownBtn = byId('mobileSettingsShutdownBtn');

        if (llamaBtn) llamaBtn.addEventListener('click', function () { go('llamacpp'); });
        if (pathBtn) pathBtn.addEventListener('click', function () { go('modelpaths'); });
        if (consoleBtn) consoleBtn.addEventListener('click', function () {
            if (typeof window.openConsoleModal === 'function') window.openConsoleModal();
        });
        if (mcpBtn) mcpBtn.addEventListener('click', function () {
            window.open('tools/mcp-manager.html', '_blank');
        });
        if (shutdownBtn) shutdownBtn.addEventListener('click', shutdownService);
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.MobileSettings = { shutdownService };
})();
