// Run with Playwright Core installed and CHROME_PATH pointing to a Chromium executable.
// All requests, including the synthetic WebVPN pages, are intercepted inside the browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');

const src = path.resolve(__dirname, '../../');
const asset = name => fs.readFileSync(path.join(src, 'main/assets', name), 'utf8');
const fixture = name => fs.readFileSync(path.join(src, 'test/resources/fixtures/qiangzhi', name), 'utf8');
const portal = asset('sdjtu-portal.js');
const readers = {rooms:asset('sdjtu-rooms.js'),plan:asset('sdjtu-plan.js')};
const host = 'https://wwebvpn.sdjtu.edu.cn';
const base = '/fixture/jsxsd/';
const html = body => '<!doctype html><meta charset="utf-8"><body>' + body;
const pages = {
    '/fingerprint':html('<input type=password><a href="/forbidden">教务系统</a>'),
    '/home':html('<a href="'+base+'framework/main">综合教务</a>'),
    [base+'framework/main']:html('<button onclick="this.hidden=true;menu.hidden=false">培养管理</button>' +
        '<div id="menu" hidden><button onclick="this.hidden=true;r.hidden=false">我的课表</button>' +
        '<button onclick="this.hidden=true;p.hidden=false">培养方案</button></div>' +
        '<a id="r" hidden href="'+base+'query" target="work">教室课表查询</a>' +
        '<a id="p" hidden href="'+base+'pyfa/list" target="work">我的培养方案</a>' +
        '<iframe name="work"></iframe>'),
    [base+'query']:fixture('sdjtu-rooms.html'),
    [base+'result']:fixture('sdjtu-rooms-grid.html'),
    [base+'pyfa/list']:html('<h2>培养方案查询</h2><a href="detail">示例专业</a>'),
    [base+'pyfa/detail']:fixture('sdjtu-plan.html'),
    [base+'status']:html('<table><tr><th>教室</th><th>使用状态</th></tr><tr><td>A101</td><td>空闲</td></tr>' +
        '<tr><td>A102</td><td>已占用</td></tr><tr><td>A103</td><td>待确认</td></tr></table>'),
    [base+'other']:html('<table id="kbtable"><tr><th>学生</th><th>星期一</th></tr><tr><td>张三</td><td></td></tr></table>')
};

// Android carries the acknowledged submission and its data across WebView documents.
function nativeSync(page, feature, options = {}) {
    const request = {...options, sync:true, submitted:false};
    let submissions = 0;
    async function tick() {
        const result = await page.evaluate('('+portal+')('+JSON.stringify(feature)+',true,false,' +
            readers[feature]+','+JSON.stringify(request)+')');
        if (result.stage === 'submitted') {
            submissions++;
            request.submitted = true;
            request.context = result.data;
        }
        return result;
    }
    async function until(stage) {
        const deadline = Date.now() + 5000;
        let last;
        do {
            try { last = await tick(); }
            catch (error) {
                if (!/Execution context was destroyed/.test(error.message)) throw error;
                await page.waitForLoadState('domcontentloaded');
            }
            if (last && last.stage === stage) return last;
            if (last) assert.ok(!['done','error','login','choose'].includes(last.stage), JSON.stringify(last));
            await new Promise(resolve => setTimeout(resolve, 45));
        } while (Date.now() < deadline);
        assert.fail(`Waiting for ${feature}/${stage}, last response ${JSON.stringify(last)}`);
    }
    return {tick, until, submissions:() => submissions};
}

function roomQuery(page, campus, week, building = '', options = {}) {
    return nativeSync(page, 'rooms', {...options, roomAction:'query', roomConditions:{campus, week, building}});
}

async function roomConditionRegressions(page, requests) {
    const formUrl = host + base + 'required-conditions';
    pages[base+'required-conditions'] = fixture('sdjtu-room-conditions.html');
    const start = requests.length;
    const posted = () => requests.slice(start).filter(r => r.method === 'POST');
    await page.goto(formUrl);

    // A cached 1.3.6 request and the school's preselected defaults cannot authorize a new query.
    const first = nativeSync(page, 'rooms', {fields:{xqbh:'north', zc:'from-b', jxlbh:'north-A'}});
    const initial = (await first.until('choose')).data.roomConditions;
    assert.deepEqual(initial.campuses.map(o => o.value), ['north','south']);
    assert.deepEqual(initial.weeks.map(o => o.value), ['1','2']);
    assert.equal(first.submissions(), 0);
    assert.equal(posted().length, 0);
    await page.goto(host + base + 'result');
    assert.notEqual((await nativeSync(page, 'rooms').tick()).stage, 'done');
    assert.equal((await nativeSync(page, 'rooms', {capture:true}).until('done')).data.rooms.length, 4);
    await page.goto(formUrl);

    // Updating the choices can run the school's dependent control handler without querying rooms.
    const list = nativeSync(page, 'rooms', {roomAction:'options',roomConditions:{campus:'south',week:'2',building:''}});
    const options = (await list.until('choose')).data.roomConditions;
    assert.equal(options.campus, 'south');
    assert.equal(options.week, '2');
    assert.equal(options.building, '');
    assert.deepEqual(options.buildings.map(o => o.value), ['south-A','south-B']);
    assert.equal(list.submissions(), 0);
    assert.equal(posted().length, 0);

    // Some JSPs call the campus field xq and leave the preselected campus's buildings unloaded.
    pages[base+'lazy-campus'] = html('<title>教室课表查询</title><form action="result" method="post" target="work">'+
        '<label>校区<select name="xq"><option value="north">北区</option></select></label>'+
        '<label>周次<select name="zc"><option value="1">第1周</option></select></label>'+
        '<label>教学楼<select name="jxlbh"><option value="">全部教学楼</option></select></label>'+
        '<button>查询</button></form><iframe name="work"></iframe><script>let f=document.forms[0];window.changed=0;'+
        'f.elements.xq.onchange=()=>{window.changed++;setTimeout(()=>f.elements.jxlbh.add(new Option("北区甲楼","A")),400)}</script>');
    await page.goto(host+base+'lazy-campus');
    const prepared=nativeSync(page,'rooms',{roomAction:'options',roomConditions:{campus:'north'}});
    assert.deepEqual((await prepared.until('choose')).data.roomConditions.buildings,[{value:'A',label:'北区甲楼'}]);
    assert.equal(await page.evaluate(()=>window.changed),1);
    await page.goto(host+base+'lazy-campus');
    await page.evaluate('('+readers.rooms+')(true,false)');
    const visible=page.locator('[data-lvlv-room-reader]');
    await visible.getByLabel('校区',{exact:true}).selectOption('north');
    await visible.getByLabel('所在教学楼').locator('option[value=A]').waitFor({state:'attached'});
    assert.equal(await page.evaluate(()=>window.changed),1);
    assert.equal(posted().length,0);

    for (const [campus, week] of [['','2'], ['ALL','2'], ['north',''], ['north','0'], ['north','3']]) {
        await page.goto(formUrl);
        const invalid = roomQuery(page, campus, week);
        await invalid.until('choose');
        assert.equal(invalid.submissions(), 0, campus + '/' + week);
        assert.equal(posted().length, 0);
    }
    // The week selector has non-numeric school IDs, different at each end of the range.
    // The south campus offers an explicit school-wide choice; north only lists real buildings.
    for (const [campus, building, expectedBuilding] of [['south','','ANY'], ['north','',''], ['south','south-B','south-B']]) {
        await page.goto(formUrl);
        const count = posted().length;
        const sync = roomQuery(page, campus, '2', building);
        const done = (await sync.until('done')).data;
        assert.equal(sync.submissions(), 1);
        assert.equal(done.query.week, 2);
        assert.deepEqual(done.choices.weeks, [2]);
        assert.equal(posted().length, count + 1);
        const req = posted()[count];
        assert.equal(req.url, host + base + 'result');
        const fields = new URLSearchParams(req.data);
        assert.equal(fields.get('xqbh'), campus);
        assert.equal(fields.get('zc'), 'from-b');
        assert.equal(fields.get('zc2'), 'to-b');
        assert.equal(fields.get('jxlbh'), expectedBuilding);
        assert.equal(fields.get('term'), '2026-2027-1');
        assert.equal((await sync.tick()).stage, 'done');
        assert.equal(posted().length, count + 1);
    }
    // A stalled school handler must not offer the previous campus's buildings after the wait expires.
    const previousPosts=posted().length;
    await page.goto(formUrl);
    await page.evaluate(()=>{ document.forms[0].elements.xqbh.onchange=()=>{}; });
    const stalled=roomQuery(page, 'south','2','north-A');
    assert.equal((await stalled.tick()).stage,'loading');
    await page.evaluate(()=>{const now=Date.now; Date.now=()=>now()+9000;});
    const retry=await stalled.until('choose');
    assert.deepEqual(retry.data.roomConditions.buildings,[]);
    assert.equal(posted().length,previousPosts);
    await roomQuery(page,'south','2').until('done');
    const last=new URLSearchParams(posted().at(-1).data);
    assert.equal(last.get('xqbh'),'south');
    assert.equal(last.get('jxlbh'),'');
    assert.equal(posted().length,previousPosts+1);

    pages[base+'numeric-campus']=pages[base+'lazy-campus'].replace('value="north"','value="1"')
        .replace('<label>周次','<label>星期<select name="xq1"><option value="2">星期二</option></select></label><label>周次');
    await page.goto(host+base+'numeric-campus');
    assert.equal((await roomQuery(page,'1','1').until('done')).data.query.day,2);
    await page.goto(host+base+'numeric-campus');
    await page.evaluate('('+readers.rooms+')(true,false)');
    await visible.getByLabel('校区',{exact:true}).selectOption('1');
    await visible.getByLabel('周次',{exact:true}).selectOption('1');
    await visible.getByRole('button',{name:'查询教室'}).click();
    await visible.getByText('空教室查询结果',{exact:true}).waitFor();
    assert.equal(await visible.locator('section').getByLabel('星期',{exact:true}).inputValue(),'2');
}

async function syncRegressions(page, requests) {
    const form = fixture('sdjtu-rooms.html');
    const plan = fixture('sdjtu-plan.html');
    const states = {'示例101':'busy','示例102':'busy','示例103':'free','示例104':'unknown'};
    for (const target of ['roomResults','_self']) {
        const at = requests.length;
        pages[base+'room-sync'] = form.replace('target="roomResults"', `target="${target}"`);
        await page.goto(host+base+'room-sync');
        const sync = roomQuery(page, 'south', '1', '', {fields:{jsmc:'示例'}});
        const acknowledged = await sync.until('submitted');
        assert.equal(acknowledged.data.query.day, 2);
        const {data} = await sync.until('done');
        assert.deepEqual(Object.fromEntries(data.rooms.map(r => [r.name,r.state])), states);
        assert.equal(data.query.day, 2);
        assert.deepEqual(data.choices.weeks, [1]);
        assert.equal(data.fields.find(f => f.name === 'xqbh').value, 'south');
        assert.equal((await sync.tick()).stage, 'done');
        const posted = requests.slice(at).filter(r => r.url === host+base+'result' && r.method === 'POST');
        assert.equal(sync.submissions(), 1);
        assert.equal(posted.length, 1, target);
        const params = new URLSearchParams(posted[0].data);
        assert.equal(params.get('jsmc'), '示例');
        assert.equal(params.get('xqbh'), 'south');
        assert.equal(params.get('zc'), '1');
        assert.equal(params.get('zc2'), '1');
        assert.equal(params.get('jxlbh'), '');
        assert.equal(params.get('queryMode'), 'fixture');
    }

    // The address bar stays at xsMain.jsp while the JSP submits to its named results frame.
    pages[base+'framework/xsMain.jsp'] = '<html><frameset cols="100%"><frame name="center" src="../kbcx/kbxx_classroom"></frameset></html>';
    pages[base+'kbcx/kbxx_classroom'] = fixture('sdjtu-legacy-room-query.html');
    pages[base+'kbcx/kbxx_classroom_ifr'] = fixture('sdjtu-legacy-room-grid.html');
    await page.goto(host+base+'framework/xsMain.jsp');
    const old = roomQuery(page, 'test', '1');
    const from = requests.length;
    const ack = await old.until('submitted');
    assert.equal(ack.data.query.day,2);
    const oldResult = (await old.until('done')).data;
    assert.equal(page.url(),host+base+'framework/xsMain.jsp');
    assert.equal(old.submissions(),1);
    const posts=requests.slice(from).filter(r=>r.method==='POST');
    assert.equal(posts.length,1);
    assert.equal(posts[0].url,host+base+'kbcx/kbxx_classroom_ifr');
    assert.equal(new URLSearchParams(posts[0].data).get('xnxq01id'),'2026-2027-1');
    assert.deepEqual(Object.fromEntries(oldResult.rooms.map(r=>[r.name,r.state])),
        {'示例101':'free','示例102':'busy','示例103':'unknown','示例104':'unknown'});
    assert.deepEqual(oldResult.choices.weeks,[1]);

    // Capturing the user's visible result never re-posts the form or takes unsubmitted filters as fact.
    const captureStart = requests.length;
    const captured = await nativeSync(page,'rooms',{capture:true}).until('done');
    assert.equal(captured.data.fields.length,0);
    assert.match(captured.data.summary,/当前显示/);
    assert.equal(requests.slice(captureStart).filter(r=>r.method==='POST').length,0);
    await page.goto(host+base+'kbcx/kbxx_classroom');
    await page.locator('form').evaluate(f=>f.insertAdjacentHTML('beforeend','<p>暂无数据</p>'));
    const beforeCapture = requests.length;
    const onlyForm = await nativeSync(page,'rooms',{capture:true}).tick();
    assert.equal(onlyForm.entryPage,host+base+'kbcx/kbxx_classroom');
    assert.notEqual(onlyForm.stage,'done');
    assert.notEqual(onlyForm.stage,'submitted');
    assert.equal(requests.slice(beforeCapture).filter(r=>r.method==='POST').length,0);

    // WebVPN can leave the upstream root-relative URL on the form or on its submit button.
    const proxyBase='/https/0123456789abcdef0123456789abcdef/jsxsd/';
    const resultPath='kbcx/kbxx_classroom_ifr';
    const entry=proxyBase+'kbcx/kbxx_classroom';
    pages[proxyBase+resultPath]=fixture('sdjtu-legacy-room-grid.html');
    for (const override of [false,true]) {
        const upstream='/jsxsd/'+resultPath+'?view=room';
        let formHtml=fixture('sdjtu-legacy-room-query.html').replace('action="kbxx_classroom_ifr"',
            'action="'+(override?'unused':upstream)+'"');
        if (override) formHtml=formHtml.replace(/<input type="button"[^>]+>/,
            '<button form="Form1" type="submit" formaction="'+upstream+'">查询</button>');
        pages[entry]=formHtml;
        await page.goto(host+entry);
        const sync=roomQuery(page, 'test', '1');
        await sync.until('submitted');
        const sent=page.waitForRequest(req=>req.method()==='POST');
        await sync.tick();
        const post=await sent;
        assert.equal(post.url(),host+proxyBase+resultPath+'?view=room');
        assert.equal(new URLSearchParams(post.postData()).get('xnxq01id'),'2026-2027-1');
        assert.equal((await sync.until('done')).data.rooms.length,4);
        assert.equal(sync.submissions(),1);
    }

    // The school may leave the old building selected while it loads the new campus's list.
    async function openCampusForm(delay) {
        await page.goto(host+base+'query');
        await page.locator('form').evaluate((el,delay) => {
            const campus = el.elements.xqbh, building = el.elements.jxlbh;
            building.options[0].value = 'north-A';
            campus.onchange = () => setTimeout(() => {
                building.replaceChildren(new Option('南区乙楼', 'south-B'));
            }, delay);
        },delay);
    }
    await openCampusForm(3100);
    const campusStart = requests.length;
    const campusPosts = () => requests.slice(campusStart).filter(r => r.url === host+base+'result' && r.method === 'POST');
    const stale = roomQuery(page, 'south', '1', 'north-A');
    const choice = await stale.until('choose');
    assert.equal(stale.submissions(), 0);
    assert.deepEqual(campusPosts(), []);
    assert.deepEqual(choice.data.roomConditions.buildings,
        [{label:'南区乙楼',value:'south-B'}]);
    // Android starts a new WebView after the user confirms the school's updated choices.
    await openCampusForm(900);
    const selected = roomQuery(page, 'south', '1', 'south-B');
    await selected.until('done');
    assert.equal(selected.submissions(), 1);
    assert.equal(campusPosts().length, 1);
    const newCampus = new URLSearchParams(campusPosts()[0].data);
    assert.equal(newCampus.get('xqbh'), 'south');
    assert.equal(newCampus.get('jxlbh'), 'south-B');

    // Some school pages refresh the existing table after an asynchronous POST.
    const oldGrid = fixture('sdjtu-rooms-grid.html').match(/<table[\s\S]*<\/table>/)[0].replaceAll('示例','上次');
    pages[base+'room-in-place'] = form.replace(/<iframe[^>]*><\/iframe>/, oldGrid);
    await page.goto(host+base+'room-in-place');
    await page.locator('form').evaluate(el => el.addEventListener('submit', async event => {
        event.preventDefault();
        const response = await fetch(el.action, {method:'POST', body:new URLSearchParams(new FormData(el))});
        const updated = new DOMParser().parseFromString(await response.text(), 'text/html');
        await new Promise(resolve => { window.showRoomResults = resolve; });
        document.querySelector('#kbtable').replaceChildren(...updated.querySelector('#kbtable').children);
    }));
    const at = requests.length;
    const inPlace = roomQuery(page, 'north', '1');
    const {data:conditions} = await inPlace.until('submitted');
    assert.equal((await inPlace.tick()).stage, 'loading');
    await page.waitForFunction(() => typeof window.showRoomResults === 'function');
    assert.equal((await inPlace.tick()).stage, 'loading');
    assert.equal(await page.getByText('上次103').count(), 1);
    await page.evaluate(() => window.showRoomResults());
    const refreshed = (await inPlace.until('done')).data;
    assert.deepEqual(Object.fromEntries(refreshed.rooms.map(r => [r.name,r.state])), states);
    assert.equal(refreshed.query.day, conditions.query.day);
    assert.deepEqual(refreshed.fields, conditions.fields);
    assert.equal((await inPlace.tick()).stage, 'done');
    assert.equal(inPlace.submissions(), 1);
    assert.equal(requests.slice(at).filter(r => r.url === host+base+'result' && r.method === 'POST').length, 1);

    const planLabels = ['交通工程专业','交通运输专业'];
    const list = '<h2>培养方案查询</h2>' + planLabels.map((label,i) =>
        `<a href="track${i}">${label}</a>`).join(' ');
    pages[base+'pyfa/native-list'] = html(list);
    for (const [i,label] of planLabels.entries()) {
        pages[base+'pyfa/track'+i] = plan.replace('大学英语', label+'课程');
    }
    for (const [selected, expected] of [['',0],[planLabels[1],1],['已撤销的旧方案',0]]) {
        const at = requests.length;
        await page.goto(host+base+'pyfa/native-list');
        const sync = nativeSync(page, 'plan', {planChoice:selected});
        const acknowledged = await sync.until('submitted');
        assert.equal(acknowledged.data.planChoice, planLabels[expected]);
        const {data} = await sync.until('done');
        assert.deepEqual(data.planChoices.map(p => p.label), planLabels);
        assert.equal(data.planChoice, planLabels[expected]);
        assert.equal(data.courses.length, 3);
        assert.equal(data.courses[0].name, planLabels[expected]+'课程');
        assert.equal(sync.submissions(), 1);
        assert.equal(requests.slice(at).filter(r => r.url.includes('/pyfa/track')).length, 1);
    }

    // Leave stale details visible while the school's asynchronous selection is pending.
    const table = '<table id="mxh"><tr><th>课程编号</th><th>课程名称</th></tr>' +
        '<tr><td>A</td><td>旧课程</td></tr></table>';
    pages[base+'pyfa/in-place'] = html('<h2>培养方案</h2><button onclick="this.dataset.clicks=Number(this.dataset.clicks||0)+1">交通运输专业</button>' + table);
    for (const update of ['cell','row','table','empty']) {
        await page.goto(host+base+'pyfa/in-place');
        const sync = nativeSync(page, 'plan');
        await sync.until('submitted');
        assert.equal((await sync.tick()).stage, 'loading');
        assert.equal((await sync.tick()).stage, 'loading');
        await page.locator('#mxh').evaluate((el, kind) => {
            if (kind === 'cell') el.rows[1].cells[1].textContent = '新课程';
            if (kind === 'row') el.rows[1].replaceWith(el.rows[1].cloneNode(true));
            if (kind === 'table') el.replaceWith(el.cloneNode(true));
            if (kind === 'empty') el.insertAdjacentHTML('afterend','<p>暂无计划课程</p>');
        }, update);
        const {data} = await sync.until('done');
        assert.equal(data.planChoice, planLabels[1]);
        assert.deepEqual(data.courses.map(c => c.name), update === 'empty' ? [] : [update === 'cell' ? '新课程' : '旧课程']);
        assert.equal(await page.locator('button').getAttribute('data-clicks'), '1');
    }

    pages[base+'pyfa/framed-list'] = html('<h2>培养方案查询</h2><a href="track1" target="detail">交通运输专业</a>' +
        '<iframe name="detail" src="track0"></iframe>');
    await page.goto(host+base+'pyfa/framed-list');
    await page.frameLocator('[name=detail]').getByText('交通工程专业课程').waitFor();
    const framed = nativeSync(page, 'plan');
    await framed.until('submitted');
    assert.equal((await framed.until('done')).data.courses[0].name, '交通运输专业课程');

    // Header-only and unrelated pages have no evidence of a completed empty query.
    pages[base+'pyfa/pending'] = html('<h2>培养方案</h2><table id="mxh"><tr><th>课程名称</th><th>学分</th></tr></table>');
    await page.goto(host+base+'pyfa/pending');
    const zeroPlan = nativeSync(page, 'plan');
    assert.notEqual((await zeroPlan.tick()).stage, 'done');
    await page.locator('#mxh').evaluate(el => el.insertRow().insertCell().textContent='暂无数据');
    assert.deepEqual((await zeroPlan.until('done')).data.courses, []);
    pages[base+'room-empty-form'] = form.replace('action="result"','action="room-empty"');
    pages[base+'room-empty'] = html('<title>教室课表查询结果</title><p>没有符合条件的教室</p>');
    await page.goto(host+base+'room-empty-form');
    const zeroRooms = roomQuery(page, 'north', '1');
    await zeroRooms.until('submitted');
    assert.deepEqual((await zeroRooms.until('done')).data.rooms, []);

    for (const [path, body, expected] of [
        ['/fingerprint',pages['/fingerprint'],'login'],
        [base+'expired',html('<p>会话已过期，请重新登录</p>'),'login'],
        [base+'maintenance',html('<p>系统维护中</p>'),'error']
    ]) {
        pages[path] = body;
        await page.goto(host+path);
        for (const type of ['rooms','plan']) {
            const result = await nativeSync(page,type).tick();
            assert.equal(result.stage, expected);
            assert.equal(result.data, undefined);
        }
    }
}

(async () => {
    const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || undefined});
    try {
        await require('./sdjtu-legacy-portal.cjs')(browser, portal);
        const page = await browser.newPage({viewport:{width:390,height:800}});
        page.setDefaultTimeout(4500);
        const requests=[], errors=[];
        page.on('pageerror', e => errors.push(e.message));
        await page.route('**/*', route => {
            const req=route.request(), url=new URL(req.url());
            requests.push({url:url.href,method:req.method(),data:req.postData()});
            return route.fulfill({body:pages[url.pathname] || html(''),contentType:'text/html; charset=utf-8'});
        });
        await roomConditionRegressions(page, requests);
        const run = (feature, go=true) => page.evaluate('('+portal+')('+JSON.stringify(feature)+','+
            go+',false,'+readers[feature]+')');
        const reader = page.locator('[data-lvlv-room-reader]');
        const plans = page.locator('[data-lvlv-plan-reader]');
        await page.goto(host+'/fingerprint');
        for (const type of ['rooms','plan']) {
            assert.equal((await run(type)).base,'');
            assert.equal(await page.locator('[data-lvlv-room-reader],[data-lvlv-plan-reader]').count(),0);
        }
        assert.equal(requests.some(r=>r.url.endsWith('/forbidden')),false);
        await page.goto(host+'/home');
        await run('rooms'); await page.waitForURL('**/framework/main');
        for (const stage of ['teaching','schedule','rooms']) assert.equal((await run('rooms')).stage,stage);
        await page.frameLocator('[name=work]').locator('form').waitFor();
        assert.equal((await run('rooms')).stage,'form');
        const visibleStart = requests.length;
        const fields = reader.locator('.fields');
        const queryRooms = reader.getByRole('button',{name:'查询教室'});
        assert.equal(await queryRooms.isDisabled(), true);
        await fields.getByLabel('周次',{exact:true}).selectOption({label:'第2周'});
        assert.equal(await queryRooms.isDisabled(), true);
        await reader.getByLabel('校区',{exact:true}).selectOption('south');
        await reader.getByLabel('所在教学楼').locator('option',{hasText:'南区教学楼'}).waitFor({state:'attached'});
        await reader.getByLabel('教室',{exact:true}).fill('示例');
        await queryRooms.click();
        await reader.getByText('空教室查询结果',{exact:true}).waitFor();
        const posted=requests.slice(visibleStart).find(r=>r.url===host+base+'result');
        assert.equal(posted.method,'POST');
        const params=new URLSearchParams(posted.data);
        assert.equal(params.get('xqbh'),'south'); assert.equal(params.get('jsmc'),'示例');
        assert.equal(params.get('zc'),'2'); assert.equal(params.get('zc2'),'2');
        assert.equal(params.get('jxlbh'),'');
        assert.equal(params.get('queryMode'),'fixture');
        const list=reader.locator('section'), names=()=>list.locator('li').allTextContents();
        // The returned grid includes both days. The official submission selected Tuesday.
        assert.equal(await list.getByLabel('星期').inputValue(),'2');
        assert.deepEqual(await names(),['示例101','示例103']);
        await list.getByLabel('节次').selectOption({label:'第1、2节'});
        assert.deepEqual(await names(),['示例101','示例102','示例103']);
        await list.getByLabel('状态').selectOption('unknown');
        assert.deepEqual(await names(),['示例104']);
        await reader.getByRole('button',{name:'教务原页'}).click();
        await reader.getByRole('button',{name:'查空教室'}).click();
        assert.equal(await list.getByLabel('状态').inputValue(),'unknown');
        // A second query that produces no response must not label the previous rows as current.
        await page.frameLocator('[name=work]').locator('form').evaluate(form=>
            form.addEventListener('submit',e=>e.preventDefault(),{once:true}));
        await queryRooms.click();
        await list.waitFor({state:'detached'});
        assert.equal(await reader.getByRole('button',{name:'教务原页'}).isVisible(),true);
        await page.goto(host+base+'status');
        assert.equal((await run('rooms')).stage,'results');
        assert.deepEqual(await names(),['A101']);
        await list.getByLabel('状态').selectOption('busy');
        assert.deepEqual(await names(),['A102']);
        await page.goto(host+base+'other');
        assert.equal((await run('rooms')).stage,'waiting');
        assert.equal(await reader.count(),0);
        await page.goto('https://example.org'+base+'status');
        assert.equal((await run('rooms')).stage,'waiting');
        assert.equal(await reader.count(),0);
        await page.goto(host+base+'framework/main');
        for (let i=0;i<3;i++) await run('plan');
        const work = page.frameLocator('[name=work]');
        await work.getByRole('link',{name:'示例专业'}).waitFor();
        assert.equal((await run('plan')).stage,'official');
        assert.equal(await plans.count(),0);
        await work.getByText('示例专业').click();
        await plans.getByRole('heading',{name:'培养方案'}).waitFor();
        assert.equal((await run('plan')).stage,'plan');
        assert.match(await plans.locator('p').innerText(),/3 门课程.*8.5/);
        await plans.getByLabel('开设学期').selectOption('2');
        assert.equal(await plans.locator('article').count(),2);
        await plans.getByRole('searchbox').fill('DEMO02');
        assert.match(await plans.locator('article').innerText(),/高等数学[\s\S]*4 学分.*64 学时.*在修/);
        await plans.getByRole('button',{name:'查看教务原页'}).click();
        await plans.getByRole('button',{name:'课程阅读视图'}).click();
        await page.locator('[name=work]').evaluate(el=>el.remove());
        await plans.waitFor({state:'detached'});
        await syncRegressions(page, requests);
        assert.deepEqual(errors,[]);
        console.log('PASS: WebVPN navigation, school-only readers, official form POST, dependent fields, weekday/period/week filters, room states, plan selection and course filters, original views, iframe replacement.');
        console.log('PASS: native sync, single iframe/full-page/in-place POST, saved plan selection, asynchronous detail refresh, explicit empty results, expired login and school errors.');
        console.log('PASS: campus and single week required before room POST, campus-dependent buildings, all buildings and selected building, school field values and optional building without a placeholder.');
    } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
