// The browser intercepts every request. All credentials and authentication responses below are synthetic.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/assets/sdjtu-login-form.js'), 'utf8');
const host = 'https://wwebvpn.sdjtu.edu.cn';
const loginPath = '/https/77726476706e69737468656265737421f3f652cd69236c5a6a1dc7a99c406d36e9/cas/login';
const search = '?service=' + encodeURIComponent(host + '/login?cas_login=true');
const login = host + loginPath + search;
const directory = host + loginPath.slice(0, loginPath.lastIndexOf('/') + 1);
const username = '2024000000';
const password = "demo-only-'\"\\-\u2028-\u2029-</script>";

const fixture = `<!doctype html><meta charset="utf-8"><base href="${directory}">
    <button role="tab" aria-selected="true">用户名密码</button><button role="tab">刷脸认证</button>
    <form hidden id="autoLoginForm" action="login?autoAuth=true" method="post">
        <input type="hidden" name="username"><input type="hidden" name="password">
    </form>
    <form id="normalLoginForm" action="login" method="POST">
        <input id="nameInput" name="username" autocomplete="username">
        <input type="password" autocomplete="new-password">
        <input type="hidden" name="type" value="UsernamePassword">
        <input type="hidden" name="_eventId" value="submit">
        <input type="hidden" name="execution" value="demo">
        <input type="hidden" name="captcha_code">
        <button id="submitBtn" class="disabled">登 录</button>
    </form>
    <script>
        const form = document.getElementById('normalLoginForm');
        const model = {};
        form.addEventListener('input', e => {
            model[e.target.type] = e.target.value;
            form.querySelector('button').classList.toggle('disabled', !(model.text && model.password));
        });
        form.addEventListener('submit', e => {
            e.preventDefault();
            fetch('login', {method:'POST', body:new URLSearchParams({username:model.text, password:model.password})});
        });
    </script>`;

function run(page, user = null, pass = null) {
    const args = [loginPath, search, user, pass].map(v => JSON.stringify(v)
        .replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029'));
    return page.evaluate(script + '(' + args.join(',') + ')');
}

(async () => {
    const browser = await chromium.launch({executablePath:process.env.CHROME_PATH, headless:true});
    try {
        const context = await browser.newContext({serviceWorkers:'block'});
        const requests = [];
        await context.route('**/*', async route => {
            const req = route.request();
            if (req.method() === 'POST') {
                requests.push({url:req.url(), data:new URLSearchParams(req.postData())});
                return route.fulfill({status:200,body:''});
            }
            const body = req.url() === host + '/parent' ? '<iframe src="' + login + '"></iframe>' : fixture;
            await route.fulfill({contentType:'text/html; charset=utf-8',body});
        });
        const page = await context.newPage();
        await page.goto(login);
        // The gateway rewrites the getter; the raw form action on the CAS document is relative.
        await page.evaluate(() => Object.defineProperty(document.getElementById('normalLoginForm'),
            'action', {value:'https://cas1.sdjtu.edu.cn/cas/login'}));
        assert.equal(await run(page), 'READY');
        const sent = page.waitForResponse(res => res.request().method() === 'POST');
        assert.equal(await run(page, username, password), 'SUBMITTED');
        await sent;
        assert.equal(requests.length, 1);
        assert.equal(requests[0].url, host + loginPath);
        assert.deepEqual(Object.fromEntries(requests[0].data), {username,password});
        assert.equal(await run(page), 'SUBMITTED', 'wait for a slow authentication response');
        assert.equal(await run(page, username, password), 'MANUAL', 'never submit twice');
        assert.equal(await page.locator('#autoLoginForm [name=password]').inputValue(), '');
        await page.evaluate(() => document.body.insertAdjacentHTML('beforeend', '<p role="alert">账号或密码错误</p>'));
        assert.equal(await run(page), 'MANUAL');

        for (const change of [
            () => normalLoginForm.setAttribute('action', 'https://other.example.invalid/login'),
            () => normalLoginForm.setAttribute('method', 'get'),
            () => normalLoginForm.setAttribute('target', '_blank'),
            () => document.querySelector('base').href = 'https://other.example.invalid/',
            () => document.querySelector('base').target = '_blank',
            () => submitBtn.setAttribute('formaction', '/different'),
            () => document.querySelector('[role=tab]').textContent = '刷脸认证',
            () => normalLoginForm.insertAdjacentHTML('beforeend', '<input name="captcha_code">'),
            () => document.body.insertAdjacentHTML('beforeend', '<div role="dialog">请完成人脸验证</div>'),
            () => document.body.insertAdjacentHTML('beforeend', '<input autocomplete="one-time-code">'),
            () => normalLoginForm.querySelector('[name=type]').value = 'Other'
        ]) {
            await page.goto(login);
            await page.evaluate(change);
            assert.equal(await run(page, username, password), 'MANUAL');
            assert.equal(await page.locator('#nameInput').inputValue(), '');
            assert.equal(await page.locator('input[type=password]').inputValue(), '');
        }

        // The document navigated after the native URL check but before evaluateJavascript ran.
        for (const url of [host + '/dashboard', login + '#x', login + '&next=x',
                login.replace('f3f652cd', '00000000'), login.replace(host, 'https://other.example.invalid')]) {
            await page.goto(url);
            assert.equal(await run(page, username, password), 'MANUAL');
            assert.equal(await page.locator('#nameInput').inputValue(), '');
        }
        await page.goto(host + '/parent');
        const frame = page.frame({url:login});
        assert.ok(frame);
        assert.equal(await run(frame, username, password), 'MANUAL');
        assert.equal(await frame.locator('#nameInput').inputValue(), '');

        // If input handlers change the destination, stop before filling the password.
        await page.goto(login);
        await page.evaluate(() => nameInput.addEventListener('input', () => normalLoginForm.action = '/elsewhere'));
        assert.equal(await run(page, username, password), 'MANUAL');
        assert.equal(await page.locator('input[type=password]').inputValue(), '');
        await page.evaluate(() => normalLoginForm.remove());
        assert.equal(await run(page), 'WAIT');
        assert.equal(requests.length, 1);
        console.log('PASS SDJTU CAS form, manual verification, redirects and single submission');
    } finally {
        await browser.close();
    }
})().catch(err => { console.error(err); process.exitCode = 1; });
