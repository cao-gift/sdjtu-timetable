// All navigation is served locally through Playwright routes. These classic menu shapes
// exercise the official leaf labels without storing live school URLs or personal records.
const assert = require('node:assert/strict');

module.exports = async function legacyPortal(browser, portal) {
    const page = await browser.newPage();
    const origin = 'https://wwebvpn.sdjtu.edu.cn';
    const root = '/fixture/proxy/jsxsd/';
    const main = root+'framework/xsMain.jsp';
    const wrap = s => '<!doctype html><meta charset="utf-8"><body>'+s;
    const demo = {
        [main]:'<!doctype html><html><frameset cols="35%,65%"><frame name="menu" src="left.jsp">'+
            '<frame name="work" src="desk.jsp"></frameset></html>',
        [root+'framework/left.jsp']:wrap('<div id="LeftMenu1_divChildMenu"><h4>培养方案</h4>'+
            '<a href="/jsxsd/pyfa/topyfamx" target="work">培养方案明细</a>'+
            '<ul style="display:none"><li><a href="/jsxsd/pyfa/pyfa_query?view=menu" target="work">培养方案执行计划</a></li>'+
            '<li title="教室课表查询"><a href="/jsxsd/kbcx/kbxx_classroom" target="work">教室课表查询</a></li></ul></div>'),
        [root+'framework/desk.jsp']:wrap('<h2>学生桌面</h2>'),
        '/portal':wrap('<iframe src="https://student.sdjtu.edu.cn/jsxsd/framework/main.jsp"></iframe>'),
        '/login':wrap('<input type="password"><a href="forbidden">教务系统</a>')
    };
    const requests = [];
    try {
        await page.route('**/*', r => {
            requests.push(r.request().url());
            const path = new URL(r.request().url()).pathname;
            return r.fulfill({status:path==='/jsxsd/framework/oldMenu.jsp'?404:200,
                contentType:'text/html;charset=utf-8',body:demo[path] || wrap('示例页面')});
        });
        const inspect = type => page.evaluate('('+portal+')('+JSON.stringify(type)+',true,false,null,{sync:true})');
        for (const [type, path] of [['plan','pyfa/pyfa_query?view=menu'],['rooms','kbcx/kbxx_classroom']]) {
            await page.goto(origin+main);
            await page.frameLocator('frame[name=menu]').locator('h4').waitFor();
            const navigated = page.waitForEvent('framenavigated', {predicate:f=>f.name()==='work' && f.url()===origin+root+path,timeout:4500});
            const result = await inspect(type);
            await navigated;
            assert.equal(result.base,origin+root);
            assert.equal(page.url(),origin+main);
        }
        assert.equal(requests.some(u=>u.includes('/pyfa/topyfamx')),false);
        assert.equal(requests.some(u=>u.startsWith(origin+'/jsxsd/')),false);
        // A root-relative frame src can survive WebVPN rewriting. Correct it once, only on
        // the proxy path, and preserve named frames that have already navigated elsewhere.
        const proxy = '/https/0123456789abcdef0123456789abcdef/jsxsd/';
        const framePage = proxy+'framework/xsMain.jsp';
        demo[framePage] = '<frameset cols="40%,60%"><frame name="menu" src="/jsxsd/framework/oldMenu.jsp?menu=1">'+
            '<frame name="work" src="/jsxsd/framework/desk.jsp"></frameset>';
        demo[proxy+'framework/oldMenu.jsp'] = demo[root+'framework/left.jsp'];
        await page.goto(origin+framePage);
        const bareSrc = await page.frameLocator('frame[name=menu]').locator('body').innerText();
        assert.match(bareSrc,/示例页面/);
        await page.frame({name:'work'}).goto(origin+proxy+'pyfa/already-selected');
        const fixedUrl = origin+proxy+'framework/oldMenu.jsp?menu=1';
        for (const [nav, capture] of [[false,false],[true,true]]) {
            await page.evaluate('('+portal+')("plan",'+nav+',false,null,{sync:true,capture:'+capture+'})');
            assert.equal(requests.includes(fixedUrl),false);
            assert.equal(page.frame({name:'menu'}).url(), origin+'/jsxsd/framework/oldMenu.jsp?menu=1');
        }
        const loadedMenu = page.waitForEvent('framenavigated', {predicate:f=>f.url()===fixedUrl,timeout:4500});
        assert.equal((await inspect('plan')).stage,'openingFrame');
        await loadedMenu;
        assert.equal(page.frame({name:'work'}).url(),origin+proxy+'pyfa/already-selected');
        const loadedPlan = page.waitForEvent('framenavigated',{predicate:f=>
            f.url()===origin+proxy+'pyfa/pyfa_query?view=menu', timeout:4500});
        await inspect('plan');
        await loadedPlan;
        assert.equal(requests.filter(u=>u===fixedUrl).length,1);
        assert.equal(requests.includes(origin+proxy+'framework/desk.jsp'),false);
        await page.goto(origin+'/portal');
        const relocated = page.waitForURL('https://student.sdjtu.edu.cn/jsxsd/framework/main.jsp');
        await inspect('rooms');
        await relocated;
        await page.goto(origin+'/login');
        assert.equal((await inspect('plan')).stage,'login');
        assert.equal(requests.some(u=>u.endsWith('forbidden')),false);
        // A mobile resource tile can rely entirely on a framework click listener. Its
        // displayed intranet URL is for identification. The actual click goes through SSO.
        const sso = '/https/0123456789abcdef0123456789abcdef/sso.jsp';
        const dashboard = wrap('<h2>研究生、本专科生教务应用</h2>' +
            '<section id="resource"><div><h3>教务系统</h3><span>http://192.0.2.12/sso.jsp</span></div></section>' +
            '<script>resource.addEventListener("click", () => location.assign("'+sso+'"))</script>');
        demo['/resource'] = dashboard;
        demo[sso] = wrap('<script>location.replace("'+main+'")</script>');
        demo['/other'] = dashboard.replace('192.0.2.12/sso.jsp', '192.0.2.12/unrelated.jsp');
        demo['/challenge'] = dashboard.replace('<h2>', '<input type=password><h2>');
        for (const [path, stage] of [['/other','waiting'],['/challenge','login']]) {
            await page.goto(origin+path);
            assert.equal((await inspect('portal')).stage,stage);
            assert.equal(page.url(),origin+path);
        }
        await page.goto(origin+'/resource');
        const schoolHome = page.waitForURL(origin+main,{timeout:5000});
        assert.equal((await inspect('portal')).stage,'academic');
        await schoolHome;
        await page.frameLocator('frame[name=menu]').locator('h4').waitFor();
        const authenticated = await page.evaluate('('+portal+')("portal",false,false,null,{sync:true})');
        assert.equal(authenticated.base,origin+root);
        assert.equal(requests.filter(u=>u===origin+sso).length,1);
        assert.equal(requests.some(u=>u.startsWith('http:')),false);
        assert.equal(requests.some(u=>u.includes('192.0.2.12')),false);
        console.log('PASS: mobile resource tile, portal SSO, legacy academic root, ignored unrelated tile and login challenge.');
        console.log('PASS: old frameset, exact hidden-menu leaf, target frame, WebVPN prefix on frame sources and menu links, same-school cross-origin handoff, login guard.');
    } finally {await page.close();}
};
