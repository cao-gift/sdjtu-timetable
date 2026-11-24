// Chromium runs against local responses for every request, including WebVPN and the two result frames.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright-core');

const src = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(src, file), 'utf8');
const portal = read('main/assets/sdjtu-portal.js');
const reader = read('main/assets/sdjtu-exams.js');
const forms = Object.fromEntries(['final','early'].map(kind =>
    [kind, read('test/resources/fixtures/qiangzhi/sdjtu-exam-' + kind + '.html')]));
const origin = 'https://wwebvpn.sdjtu.edu.cn';
const root = '/https/' + '723b'.repeat(16) + '/jsxsd/';
const home = origin + root + 'framework/xsMain.jsp';
const currentTerm = '2031-2032-1';
const html = body => '<!doctype html><meta charset="utf-8"><body>' + body;
const menu = html('<div id="LeftMenu1_divChildMenu"><h4>考试报名</h4><h4>我的考试</h4>' +
    '<a target="work" href="/jsxsd/xsks/xsksap_list">考试安排查询</a>' +
    '<a target="work" href="/jsxsd/xsks/stks_list">随堂考试查询</a></div>');
const headers = '<tr><th>课程编号</th><th>课程名称</th><th>考试时间</th>' +
    '<th>考试地点</th><th>座位号</th><th>考试类别</th></tr>';
const table = (name, category = '期末', seat = '09') => '<table id="dataList">' + headers + '<tr>' +
    ['DEMO1',name,'2031-12-24 09:00-11:00','示例楼101',seat,category].map(v => '<td>' + v + '</td>').join('') +
    '</tr></table>';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function scenario(browser, kind, options = {}) {
    const page = await browser.newPage();
    const requests = [];
    let releaseResult;
    const held = options.hold ? new Promise(resolve => {releaseResult = resolve;}) : null;
    await page.route('**/*', async route => {
        const req = route.request(), url = new URL(req.url());
        requests.push({path:url.pathname,method:req.method(),fields:new URLSearchParams(req.postData() || ''),url});
        let body;
        if (url.origin === origin && url.pathname.startsWith(root)) {
            switch (url.pathname.slice(root.length)) {
                case 'framework/xsMain.jsp':
                    body = html('<iframe name="menu" src="menu"></iframe><iframe name="work" src="desk"></iframe>');
                    break;
                case 'framework/menu': body = menu; break;
                case 'framework/desk': body = html('学生个人中心'); break;
                case 'xsks/xsksap_list':
                    body = forms.final;
                    if (options.scriptedForm) {
                        body = body.replace('action="/jsxsd/xsks/final_result"', 'action="javascript:void(0)"')
                            .replace('<input type="submit" value="查 询">',
                                '<input type="button" value="查 询" onclick="this.form.action=\'/jsxsd/xsks/final_result\';this.form.submit()">');
                    }
                    break;
                case 'xsks/stks_list':
                    body = options.missingType ? forms.early.replace('>提前期末考试<','>普通考试<') : forms.early;
                    break;
                case 'xsks/previous': body = html(table('上次查询遗留课程')); break;
                case 'xsks/' + kind + '_result':
                    if (held) await held;
                    body = html(options.result === undefined ? table('示例课程', kind === 'final' ? '期末' : '提前期末考试') : options.result);
                    break;
                case 'xsks/next':
                    body = html(table('第二页课程', '期末', '15'));
                    break;
                case 'login': body = html('<label>密码<input type="password"></label>'); break;
            }
        }
        await route.fulfill({status:body === undefined ? 404 : 200, contentType:'text/html; charset=utf-8', body:body || ''});
    });
    const mode = kind === 'final' ? 'examFinal' : 'examEarly';
    const request = {sync:true,examKind:kind,submitted:false};
    if (options.term) request.term = options.term;
    async function tick() {
        return page.evaluate('(' + portal + ')(' + JSON.stringify(mode) + ',true,false,' + reader + ',' + JSON.stringify(request) + ')');
    }
    async function until(stages) {
        let result;
        for (let i = 0; i < 110; i++) {
            result = await tick();
            if (result.stage === 'submitted') {
                assert.equal(request.submitted,false,'a single exam form submission is allowed per exam branch');
                request.submitted = true;
                request.context = result.data;
            } else if (result.stage === 'examPage') {
                request.context = result.data;
            }
            if (stages.includes(result.stage)) return result;
            assert.ok(!['done','error','login','choose'].includes(result.stage), JSON.stringify(result));
            await sleep(45);
        }
        assert.fail(mode+' waiting for '+stages+'; last response '+JSON.stringify(result)+'; routes '+requests.map(r=>r.path));
    }
    await page.goto(options.login ? origin + root + 'login' : home);
    return {page, requests, until, tick, releaseResult, posts:() => requests.filter(r=>r.method === 'POST')};
}

(async () => {
    const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || undefined});
    try {
        for (const kind of ['final','early']) {
            const s = await scenario(browser, kind, {term:kind === 'early' ? currentTerm : undefined, hold:true});
            const submitted = await s.until(['submitted']);
            assert.equal(submitted.data.term, currentTerm);
            assert.equal(s.posts().length,0);
            // Both the previous iframe and its recognizable result table remain visible until the POST returns.
            for (let i=0;i<4;i++) {
                assert.ok(!['done','error','login'].includes((await s.tick()).stage));
                await sleep(40);
            }
            assert.equal(s.posts().length,1);
            const post = s.posts()[0];
            assert.equal(post.path, root+'xsks/'+kind+'_result');
            assert.equal(post.fields.get('pageMode'),'student');
            assert.equal(post.fields.get(kind === 'final' ? 'xqlb' : 'kslx'), kind === 'final' ? 'q' : 'b');
            assert.equal(post.fields.get(kind === 'final' ? 'xnxq01id' : 'xnxq'), kind === 'final' ? 'current-F' : 'current-E');
            assert.ok(!s.requests.some(r => /print/.test(r.path)));
            s.releaseResult();
            const {data} = await s.until(['done']);
            assert.equal(data.term,currentTerm);
            assert.equal(data.exams.length,1);
            const row = data.exams[0];
            assert.equal(row.name,'示例课程');
            assert.equal(row.code,'DEMO1');
            assert.match(row.time,/2031-12-24.*09:00.*11:00/);
            assert.equal(row.room,'示例楼101');
            assert.equal(row.seat,'09');
            assert.ok(!JSON.stringify(data).includes('期末'),'backend response must not contain an exam category');
            assert.equal(s.posts().length,1);
            await s.page.close();
        }
        const empty = await scenario(browser,'early',{term:currentTerm,
            result:'<table id="dataList">'+headers+'<tr><td colspan="6">暂无数据</td></tr></table>'});
        assert.deepEqual((await empty.until(['done'])).data.exams,[]);
        assert.equal(empty.posts().length,1);
        await empty.page.close();

        const paginated = await scenario(browser,'final',{result:table('第一页课程')+
            '<div class="pagination">第1页 共2页 <a href="next">下一页</a></div>'});
        assert.deepEqual((await paginated.until(['done'])).data.exams.map(r=>r.name),['第一页课程','第二页课程']);
        assert.equal(paginated.posts().length,1);
        await paginated.page.close();

        const incomplete = await scenario(browser,'final',{result:table('未完整取得')+
            '<div class="pagination">第1页 共2页 <button disabled>下一页</button></div>'});
        await incomplete.until(['error']);
        assert.equal(incomplete.posts().length,1);
        await incomplete.page.close();

        for (const options of [{term:'2028-2029-2'},{missingType:true,term:currentTerm}]) {
            const invalid = await scenario(browser,'early',options);
            await invalid.until(['error']);
            assert.equal(invalid.posts().length,0);
            await invalid.page.close();
        }

        const scripted = await scenario(browser,'final',{scriptedForm:true});
        assert.equal((await scripted.until(['done'])).data.exams[0].name,'示例课程');
        assert.equal(scripted.posts().length,1);
        assert.equal(scripted.posts()[0].path,root+'xsks/final_result');
        await scripted.page.close();

        const invalidResults = [
            '',
            '<table><tr><th>课程名称</th><th>成绩</th></tr><tr><td>另一个表</td><td>90</td></tr></table>',
            '<title>学期理论课表</title><table><tr><th>课程名称</th><th>课程代码</th><th>教学周</th></tr>' +
                '<tr><td>示例日常课程</td><td>ABC01</td><td>1~16</td></tr></table>',
            '<h2>教务公告</h2><div class="sidebar"><span>暂无数据</span></div><p>信息查询</p>',
        ];
        for (const result of invalidResults) {
            const malformed = await scenario(browser,'final',{result});
            await malformed.until(['submitted']);
            for (let i=0; i<8; i++) {
                assert.notEqual((await malformed.tick()).stage,'done','an unrecognized or blank result cannot clear a cached schedule');
                await sleep(35);
            }
            assert.equal(malformed.posts().length,1);
            await malformed.page.close();
        }
        const login = await scenario(browser,'final',{login:true});
        await login.until(['login']);
        assert.equal(login.posts().length,0);
        console.log('PASS exams: both real form filters and term, one POST each, WebVPN frames, stale response guard, normalized rows, explicit empty, pagination, missing choices, blank/malformed results and login.');
    } finally {
        await browser.close();
    }
})().catch(error => {console.error(error); process.exitCode=1;});
