// An old Qiangzhi execution-plan form can POST inside a named frame.
// Run with Playwright Core installed and CHROME_PATH pointing to Chromium.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright-core');

const script = fs.readFileSync(path.join(__dirname, '../../main/assets/sdjtu-plan.js'), 'utf8');
const origin = 'https://wwebvpn.sdjtu.edu.cn';
const root = '/webvpn/academic/jsxsd/';
const pageOf = body => '<!doctype html><meta charset="utf-8">' + body;
const shell = pageOf('<div id="LeftMenu1_divChildMenu"><h4>培养方案</h4>' +
    '<a href="../pyfa/pyfa_query">培养方案执行计划</a><a href="../pyfa/topyfamx">培养方案明细</a></div>' +
    '<iframe name="work" src="../pyfa/pyfa_query"></iframe>');
const form = pageOf('<div class="Nsb_r_title">培养方案执行计划</div>' +
    '<form method="post" target="detail" action="/jsxsd/pyfa/pyfa_query">' +
    '<select name="xnxq01id"><option value="t1">第一学期</option><option value="">全部学期</option>' +
    '<option value="t2">第二学期</option></select><input type=hidden name=mode value=demo>' +
    '<input type="submit" value="查 询"></form><iframe name="detail"></iframe>');
const headers = ['序号','开课学期','课程编号','课程名称','开课单位','学分','总学时','考核方式','课程属性','是否考试'];
const tableHead = '<tr>' + headers.map(h => '<td>'+h+'</td>').join('') + '</tr>';
const row = (term,id,name) => '<tr>'+['1',term,id,name,'示例学院','2.5','40','考查','必修','否']
    .map(v=>'<td>'+v+'</td>').join('')+'</tr>';

(async()=>{
    const b = await chromium.launch({executablePath:process.env.CHROME_PATH || undefined});
    try {
        const page = await b.newPage();
        page.setDefaultTimeout(5000);
        let posts=[];
        await page.route('**/*',route=>{
            const req=route.request(), url=new URL(req.url());
            assert.equal(url.origin,origin);
            if (req.method()==='POST') {
                assert.equal(url.pathname,root+'pyfa/pyfa_query');
                const fields=new URLSearchParams(req.postData());
                assert.equal(fields.get('mode'),'demo');
                if (fields.get('xnxq01id') === 't2') {
                    assert.equal(url.searchParams.get('from'),'button');
                    assert.equal(fields.get('operation'),'term');
                }
                posts.push(fields.get('xnxq01id'));
                const early = fields.get('xnxq01id') === 't2' ? '' : row('第一学期','D01','示例规划');
                const body = pageOf('<script>history.replaceState(null,"","'+root+'report/show")</script>' +
                    '<table id="dataList">' + tableHead + early + row('第二学期','D02','示例运输') + '</table>');
                return route.fulfill({body,contentType:'text/html; charset=utf-8'});
            }
            let content='';
            if(url.pathname.endsWith('framework/xsMain.jsp')) content=shell;
            if(url.pathname.endsWith('pyfa/pyfa_query')) content=form;
            if(url.pathname.endsWith('/pyfa/topyfamx')) {
                content=pageOf('<title>培养方案明细</title><table><tr><th>课程/环节名称</th><th>课程编码</th><th>学分</th></tr>' +
                    '<tr><td colspan=3>第一学期</td></tr><tr><td>示例课程一</td><td>01</td><td>2</td></tr>' +
                    '<tr><td colspan=3>第二学期</td></tr><tr><td>示例课程二</td><td>02</td><td>3</td></tr></table>');
            }
            if(url.pathname.endsWith('/pyfa/nav'))content=pageOf('<title>培养方案</title><div id="LeftMenu1_divChildMenu">' +
                '<a href="pyfa_query">培养方案执行计划</a><a href="topyfamx">培养方案明细</a></div>');
            if(url.pathname.endsWith('/pyfa/choices')) content=pageOf('<title>培养方案</title><table>' +
                '<tr><td>外校</td><td><a href="https://other.example/other/jsxsd/pyfa/topyfamx">明细</a></td></tr>' +
                '<tr><td>示例专业</td><td><a target="details" href="/jsxsd/pyfa/topyfamx?track=demo">明细</a></td></tr>' +
                '</table><iframe name="details"></iframe>');
            return route.fulfill({body:content,contentType:'text/html; charset=utf-8'});
        });
        const tick = req => page.evaluate('('+script+')(true,false,'+JSON.stringify(req)+')');
        for (const key of ['', '开课学期 · 第二学期']) {
            posts=[];
            const req={sync:true,planChoice:key};
            await page.goto(origin+root+'framework/xsMain.jsp');
            const queryForm=page.frameLocator('iframe[name=work]').locator('form');
            await queryForm.waitFor();
            if (key) await queryForm.evaluate(form=>{
                form.querySelector('input[type=submit]').remove();
                form.id='planQuery';
                form.action='default';
                form.target='_self';
                form.insertAdjacentHTML('afterend','<button form="planQuery" type="submit" name="operation" value="term"' +
                    ' formaction="/jsxsd/pyfa/pyfa_query?from=button" formtarget="detail">查询</button>');
            });
            assert.equal((await tick({sync:true,capture:true})).stage,"waiting");
            const start=await tick(req);
            assert.equal(start.stage,'submitted');
            assert.equal(start.entryPage,origin+root+'pyfa/pyfa_query');
            assert.equal(start.data.planChoices.length,3);
            req.submitted=true; req.context=start.data;
            assert.equal((await tick(req)).stage,'loading');
            await page.frameLocator('iframe[name=work]').frameLocator('[name=detail]').locator('table').waitFor();
            const done=await tick(req);
            assert.equal(done.stage,'done');
            assert.deepEqual(posts,key?['t2']:['']);
            assert.equal(done.data.planChoice,key || '开课学期 · 全部学期');
            assert.deepEqual(done.data.courses.map(c=>c.term), key?['第二学期']:['第一学期','第二学期']);
            assert.equal(done.data.courses[0].credit,'2.5');
        }
        await page.goto(origin+root+'pyfa/topyfamx?track=demo');
        const specific=await tick({sync:true,capture:true});
        assert.equal(specific.stage,'done');
        assert.equal(specific.entryPage,'');
        assert.deepEqual(specific.data.courses.map(c=>c.term),['第一学期','第二学期']);
        await page.goto(origin+root+'pyfa/nav');
        assert.equal((await tick({sync:true})).stage,'waiting');
        await page.goto(origin+root+'pyfa/choices');
        const choice=await tick({sync:true});
        assert.equal(choice.stage,'submitted');
        assert.deepEqual(choice.data.planChoices,[{value:'示例专业',label:'示例专业'}]);
        const req={sync:true,submitted:true,context:choice.data};
        assert.equal((await tick(req)).stage,'loading');
        await page.frameLocator('[name=details]').locator('table').waitFor();
        assert.equal(page.frame({name:'details'}).url(),origin+root+'pyfa/topyfamx?track=demo');
        assert.equal((await tick(req)).data.courses.length,2);
        await page.goto(origin+root+'pyfa/pyfa_query');
        await page.locator('input[type=submit]').evaluate(el=>el.formAction='https://other.example/jsxsd/pyfa/pyfa_query');
        assert.equal((await tick({sync:true})).stage,'waiting');
        console.log('PASS legacy execution plan, named frames, external submit button, hidden fields, proxied form and detail links, same-origin checks, single POST, term filtering and private-entry exclusion.');
    } finally {await b.close();}
})().catch(e=>{console.error(e); process.exitCode=1;});
