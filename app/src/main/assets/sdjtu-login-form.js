(function (path, search, username, password) {
    'use strict';
    const origin = 'https://wwebvpn.sdjtu.edu.cn';
    function atLogin() {
        return window.top === window && location.origin === origin && location.pathname === path &&
            location.search === search && location.href === origin + path + search;
    }
    if (!atLogin()) return 'MANUAL';

    function visible(el) {
        const style = getComputedStyle(el);
        return el.getClientRects().length > 0 && style.visibility === 'visible' &&
            style.display !== 'none' && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0;
    }
    function visibleElements(root, selector) {
        return Array.from(root.querySelectorAll(selector)).filter(visible);
    }
    function challenge() {
        return visibleElements(document, '[role=dialog],.ant-modal,.ant-notification-notice,.ant-message-error,' +
            '[role=alert]:not(:empty),.ant-form-explain:not(:empty),.ant-form-item-explain-error:not(:empty),' +
            'iframe,input[autocomplete="one-time-code"],input[name*=captcha i],img[src*=captcha i],' +
            '[id*=captcha i]:not(input),[class*=captcha i]:not(input)').length > 0;
    }

    const form = document.getElementById('normalLoginForm');
    if (challenge()) return 'MANUAL';
    if (!form || document.readyState === 'loading') return 'WAIT';
    const user = form.querySelector('input#nameInput[name=username][autocomplete=username]');
    const pass = form.querySelector('input[type=password]');
    const button = form.querySelector('button#submitBtn');

    function safeForm() {
        if (!atLogin() || !(form instanceof HTMLFormElement) || !form.isConnected ||
            !user || !pass || !button || user.form !== form || pass.form !== form || button.form !== form ||
            !visible(form) || !visible(user) || !visible(pass) || !visible(button) ||
            user.type !== 'text' || user.disabled || pass.disabled || user.readOnly || pass.readOnly ||
            document.baseURI !== origin + path.slice(0, path.lastIndexOf('/') + 1) ||
            form.getAttribute('action') !== 'login' || form.method !== 'post' ||
            (form.target !== '' && form.target !== '_self') ||
            (!form.target && document.querySelector('base[target]:not([target="_self" i]):not([target=""])')) ||
            button.hasAttribute('formaction') || button.hasAttribute('formmethod') || button.hasAttribute('formtarget')) {
            return false;
        }
        // WebVPN rewrites the form.action getter to the original CAS URL on the verified proxy page.
        if (form.action !== origin + path && form.action !== 'https://cas1.sdjtu.edu.cn/cas/login') return false;
        const tabs = visibleElements(document, '[role=tab][aria-selected=true]');
        const fields = visibleElements(form, 'input,select,textarea');
        return tabs.length === 1 && tabs[0].textContent.trim() === '用户名密码' &&
            fields.length === 2 && fields.includes(user) && fields.includes(pass) &&
            button.textContent.replace(/\s+/g, '') === '登录' &&
            !!form.querySelector('input[type=hidden][name=type][value=UsernamePassword]') &&
            !!form.querySelector('input[type=hidden][name=_eventId][value=submit]') &&
            !!form.querySelector('input[type=hidden][name=execution]') && !challenge();
    }

    const inspectOnly = username === null && password === null;
    // Angular may keep the same document while authenticating. Never press its button twice.
    if (form.dataset.lvlvLoginAttempted) return inspectOnly ? 'SUBMITTED' : 'MANUAL';
    if (!safeForm()) return 'MANUAL';
    if (inspectOnly) return 'READY';
    form.dataset.lvlvLoginAttempted = '1';
    function setField(field, value) {
        field.value = value;
        field.dispatchEvent(new Event('input', {bubbles:true}));
        field.dispatchEvent(new Event('change', {bubbles:true}));
    }
    setField(user, username);
    if (!safeForm()) return 'MANUAL';
    setField(pass, password);
    if (!safeForm() || button.disabled || button.classList.contains('disabled') ||
        button.getAttribute('aria-disabled') === 'true') return 'MANUAL';
    button.click();
    return 'SUBMITTED';
})
