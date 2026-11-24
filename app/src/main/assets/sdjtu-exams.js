// Read one exam query through the form and WebVPN session opened by the school menu.
(function (navigate, reset, request) {
    "use strict";
    if (!request || !request.sync || !/^(final|early)$/.test(request.examKind)) return {stage:"waiting"};
    var early = request.examKind === "early", desired = early ? "提前期末考试" : "期末";
    var categoryLabel = early ? "考试类型" : "学期类别";
    var ctx = request.context || {};
    var state = document.__lvlvExam || (document.__lvlvExam = {});
    function clean(s) { return (s || "").replace(/\s+/g," ").trim(); }
    function key(s) { return clean(s).replace(/[\s:：]/g,""); }
    function visible(el) {
        return el && el.getClientRects().length && el.ownerDocument.defaultView.getComputedStyle(el).visibility !== "hidden";
    }
    function scopedUrl(doc, raw) {
        try {
            var u = new URL(raw || doc.location.href, doc.baseURI);
            if (u.protocol !== "https:" || u.origin !== doc.location.origin || u.username || u.password) return null;
            var at = doc.location.pathname.search(/\/jsxsd\//i);
            if (at > 0 && /^\/jsxsd\//i.test(u.pathname))
                u.pathname = doc.location.pathname.slice(0,at) + u.pathname;
            return /\/jsxsd\//i.test(u.pathname) ? u : null;
        } catch (ignored) { return null; }
    }
    function frames(win, path, out) {
        try {
            var d = win.document;
            if (!d || !d.body || !/^https:$/.test(d.location.protocol) ||
                !/(^|\.)sdjtu\.edu\.cn$/i.test(d.location.hostname) || out.length >= 40) return;
            out.push({doc:d,path:path});
            Array.from(d.querySelectorAll("frame,iframe")).filter(visible).forEach(function (f) {
                var i = Array.prototype.indexOf.call(win.frames, f.contentWindow);
                if (i >= 0) frames(f.contentWindow, path + "/" + i, out);
            });
        } catch (ignored) { /* WebView enforces frame origins. */ }
    }
    var pages = [];
    frames(window,"",pages);
    pages = pages.filter(function(p){return /\/jsxsd\//i.test(p.doc.location.pathname);});
    function fail(s) {return {stage:"error",message:s};}
    function wait() {return {stage:request.submitted ? "loading" : "waiting"};}
    function termOf(text) {
        var n = key(text).match(/(\d{4})[-—–－~～至/](\d{4})(?:学年)?(?:度)?(?:第|[-—–－/])?([一二三123])(?:学期)?/);
        if (!n) return "";
        return n[1]+"-"+n[2]+"-"+({"一":"1","二":"2","三":"3"}[n[3]] || n[3]);
    }
    function options(s) {
        return Array.from(s.options).filter(function(o){return !o.disabled && !o.closest("optgroup[disabled]");});
    }
    function label(select) {
        if (select.getAttribute("aria-label")) return key(select.getAttribute("aria-label"));
        var explicit = select.labels && select.labels[0];
        var text = "";
        if (explicit) {
            var copy = explicit.cloneNode(true);
            Array.from(copy.querySelectorAll("select")).forEach(function(el){el.remove();});
            text = copy.textContent;
        }
        if (!key(text)) {
            var prev = select.previousSibling;
            while (prev && !key(prev.textContent)) prev = prev.previousSibling;
            if (prev && (!prev.tagName || prev.tagName !== "SELECT")) text = prev.textContent;
        }
        if (!key(text)) {
            var cell = select.closest("td"), prevCell = cell && cell.previousElementSibling;
            if (prevCell && !prevCell.querySelector("select")) text = prevCell.textContent;
        }
        return key(text);
    }
    function examForm(doc) {
        var forms = Array.from(doc.forms);
        for (var i = 0; i < forms.length; i++) {
            var f = forms[i], selects = Array.from(f.elements).filter(function(s){
                return s.tagName === "SELECT" && visible(s) && !s.disabled;
            });
            var term = selects.find(function(s){return /学年学期/.test(label(s)) ||
                options(s).some(function(o){return !!termOf(o.textContent);});});
            var category = selects.find(function(s){return s !== term && label(s).indexOf(categoryLabel) >= 0;});
            if (!category) {
                var heads = [doc.title].concat(Array.from(doc.querySelectorAll("h1,h2,h3,legend,.Nsb_r_title"))
                    .map(function(el){return el.textContent;}));
                var heading = early ? /随堂考(?:试|信息)/ : /考试安排查询/;
                if (heads.some(function(t){return heading.test(key(t));}))
                    category = selects.find(function(s){return s !== term && options(s).some(function(o){return key(o.textContent) === desired;});});
            }
            if (term && category) return {form:f,term:term,category:category};
        }
        return null;
    }
    function buttonFor(form) {
        var doc = form.ownerDocument, candidates = Array.from(doc.querySelectorAll("input[type=submit],input[type=button],button,a[onclick]"));
        var buttons = candidates.filter(function(el){
            return visible(el) && !el.disabled && [el.value,el.textContent,el.title].some(function(s){return key(s)==="查询";});
        });
        return buttons.find(function(el){return el.form===form || form.contains(el);}) ||
            (buttons.length===1 && !buttons[0].form && !buttons[0].closest("form") ? buttons[0] : null);
    }
    function targetsPath(form,button,owner) {
        var target = (button && button.getAttribute("formtarget")) || form.getAttribute("target") || "";
        if (target==="_top") return "";
        if (target==="_parent") return owner.path.replace(/\/\d+$/,"");
        if (!target || target==="_blank" || target==="_self" || target===owner.doc.defaultView.name) return owner.path;
        // The result frame can initially contain about:blank.
        for (var i=0;i<pages.length;i++) {
            var doc=pages[i].doc, frame=Array.from(doc.querySelectorAll("iframe,frame")).find(function(f){return f.name===target;});
            if (frame) return pages[i].path+"/"+Array.prototype.indexOf.call(doc.defaultView.frames,frame.contentWindow);
        }
        return null;
    }
    function scope(path) {
        return pages.filter(function(p){return p.path===path || p.path.indexOf(path+"/")===0;});
    }
    var headings = {
        name:/^(?:课程名称|课程名|课程|考试课程(?:名称)?|考试科目(?:名称)?|科目(?:名称)?)$/,
        code:/^(?:课程编号|课程编码|课程号|课程代码|科目代码)$/,
        time:/^(?:考试时间|考试具体时间|考试时间段|考试时段|考试起止时间|考试安排时间|时间|时间段|起止时间)$/,
        date:/^(?:考试日期|考试日|日期)$/, start:/^(?:考试)?(?:开始|起始)时间$/, end:/^(?:考试)?结束时间$/,
        room:/^(?:考试地点|考场地点|考试教室|教室(?:名称)?|考试场地|场地名称|考场(?:名称)?|地点)$/,
        seat:/^(?:座位号(?:码)?|座位|座次号?|考位号|考试座位号)$/
    };
    function examTitle(doc) {
        return [doc.title].concat(Array.from(doc.querySelectorAll("h1,h2,h3,legend,.Nsb_r_title"))
            .filter(visible).map(function(el){return el.textContent;})).some(function(t){
                return /考试安排|随堂考(?:试|信息)|考场安排/.test(key(t));
            });
    }
    var emptyText=/^(?:(?:无|暂无|没有|未查询到|未找到)(?:任何|相关|符合(?:查询)?条件的)?(?:考试(?:安排|信息|记录|数据)?|随堂考试(?:安排|信息)?|数据|记录)|共0条(?:记录)?)[。！!]?$/;
    function tableResult(table) {
        var rows=Array.from(table.rows), columns={}, start=-1;
        for (var i=0;i<rows.length;i++) {
            var map={};
            Array.from(rows[i].cells).forEach(function(cell,n){
                if (cell.querySelector("table")) return;
                var text=key(cell.textContent);
                Object.keys(headings).forEach(function(k){if(headings[k].test(text)) map[k]=n;});
            });
            if (map.name===undefined || Object.keys(map).length<2) continue;
            if (Object.keys(map).every(function(k){return k==="name" || k==="code";}) &&
                (!(/考试|考场/.test(key(table.caption && table.caption.textContent)) || examTitle(table.ownerDocument)) ||
                /成绩|教学周|任课教师|课程性质/.test(rows[i].textContent))) continue;
            columns=map;start=i+1;break;
        }
        if (start<0) return null;
        var exams=[], invalid=false;
        rows.slice(start).filter(visible).forEach(function(row) {
            var cells=row.cells;
            if (cells.length<=1 || row.querySelector("th") || row.querySelector("table") || emptyText.test(key(row.textContent))) return;
            function read(k) {
                var cell=cells[columns[k]];
                return cell && cell.colSpan===1 ? clean(cell.innerText || cell.textContent) : "";
            }
            var name=read("name");
            if (!name) {if (key(row.textContent)) invalid=true;return;}
            if (headings.name.test(key(name))) return;
            var time=read("time") || [read("start"),read("end")].filter(Boolean).join("–"), date=read("date");
            if (date && time.indexOf(date)<0) time=clean(date+" "+time);
            var exam={name:name};
            [["code",read("code")],["time",time],["room",read("room")],["seat",read("seat")]].forEach(function(kv){
                if (kv[1]) exam[kv[0]]=kv[1];
            });
            exams.push(exam);
        });
        return {node:table, rows:rows.slice(start), exams:exams, invalid:invalid};
    }
    function results(path) {
        var found=[];
        scope(path).forEach(function(p) {
            var doc=p.doc;
            Array.from(doc.querySelectorAll("table")).filter(visible).forEach(function(t) {
                var result=tableResult(t);
                if (result && (result.exams.length || result.invalid)) found.push(result);
            });
            Array.from(doc.querySelectorAll("td,p,span,div,li")).filter(function(el) {
                if (el.children.length || el.closest("nav,header,aside,form select") || (el.textContent||"").length>80 ||
                    !emptyText.test(key(el.textContent)) || !visible(el)) return false;
                var table=el.closest("table");
                if (table) return !!tableResult(table);
                if (!examTitle(doc) && doc.location.pathname!==(state.resultPath || ctx.resultPath)) return false;
                var msg=key(el.textContent);
                return /考试|考场/.test(msg) || key(doc.body.innerText)===msg ||
                    el.matches(".no-data,.empty") && examTitle(doc);
            }).forEach(function(el){found.push({node:el,rows:[],exams:[]});});
        });
        return found;
    }
    function fresh(r) {
        return !(state.before || []).some(function(old){
            return old.node===r.node && JSON.stringify(old.exams)===JSON.stringify(r.exams) &&
                old.rows.length===r.rows.length && old.rows.every(function(row,i){return row===r.rows[i];});
        });
    }
    if (!request.submitted) {
        for (var i=0;i<pages.length;i++) {
            var owner=pages[i], parts=examForm(owner.doc);
            if (!parts) continue;
            var form=parts.form, allowed=options(parts.term), termWanted=termOf(request.term);
            var term=termWanted ? allowed.find(function(o){return termOf(o.textContent)===termWanted;}) : parts.term.options[parts.term.selectedIndex];
            var termText=term && termOf(term.textContent);
            if (!termText || (request.term && termText!==termWanted)) return fail("教务页面未提供本次查询的学期。");
            var category=options(parts.category).find(function(o){return key(o.textContent)===desired;});
            if (!category) return fail("教务页面缺少所需的考试查询选项。");
            var button=buttonFor(form), doc=owner.doc;
            if (!button) return fail("未找到教务的考试查询按钮。");
            var raw=button.getAttribute("formaction");
            if (raw===null) raw=form.getAttribute("action");
            var routed=scopedUrl(doc,raw);
            // Some JSP query handlers set Form1.action immediately before calling Form1.submit().
            var scripted=button.hasAttribute("onclick") && /^javascript:\s*(?:void\s*\(0\))?;?$/i.test((raw||"").trim());
            if (!routed && !scripted) return fail("考试查询地址无法用于当前教务会话。");
            var path=targetsPath(form,button,owner);
            if (path===null) return fail("未找到接收考试结果的窗口。");
            state.pending=function(){
                if (!button.isConnected || !form.isConnected) return false;
                parts.term.value=term.value; parts.category.value=category.value;
                if (parts.term.value!==term.value || parts.category.value!==category.value) return false;
                var blocked=false;
                function prepare(submitter) {
                    var action=scopedUrl(doc,form.getAttribute("action"));
                    var override=submitter && submitter.getAttribute("formaction");
                    var url=override!=null ? scopedUrl(doc,override) : action;
                    var target=targetsPath(form,submitter,owner);
                    if (!url || target===null) {blocked=true;return false;}
                    if (action) form.action=action.href;
                    if (override!=null) submitter.setAttribute("formaction",url.href);
                    if (form.target==="_blank") form.target="_self";
                    if (submitter && submitter.getAttribute("formtarget")==="_blank") submitter.setAttribute("formtarget","_self");
                    if (target!==path) state.before=results(target);
                    state.frame=target; state.resultPath=url.pathname;
                    return true;
                }
                if (routed && !prepare(button)) return false;
                var proto=doc.defaultView.HTMLFormElement.prototype;
                var previous=Object.getOwnPropertyDescriptor(form,"submit");
                function onSubmit(event) {
                    if (event.target===form && !prepare(event.submitter || button)) event.preventDefault();
                }
                Object.defineProperty(form,"submit",{configurable:true,value:function(){
                    if (prepare(null)) proto.submit.call(form);
                }});
                doc.addEventListener("submit",onSubmit);
                try {button.click();}
                finally {
                    doc.removeEventListener("submit",onSubmit);
                    if (previous) Object.defineProperty(form,"submit",previous);
                    else delete form.submit;
                }
                return !blocked;
            };
            return {stage:"submitted",data:{term:termText,frame:path,resultPath:routed ? routed.pathname : ""}};
        }
        return wait();
    }
    if (!ctx.term || typeof ctx.frame!=="string") return fail("本次考试查询的学期或结果窗口已失效。");
    if (state.pending) {
        var issue=state.pending; state.pending=null;
        state.before=results(state.frame===undefined ? ctx.frame : state.frame);
        return issue() ? wait() : fail("考试查询页面已更新，请重新同步。");
    }
    var resultFrame=state.frame===undefined ? ctx.frame : state.frame;
    var freshResults=results(resultFrame).filter(fresh);
    if (!freshResults.length) return wait();
    if (freshResults.some(function(r){return r.invalid;})) return fail("考试安排表有无法识别的记录，请稍后重试。");
    var records = freshResults.reduce(function(all,r){return all.concat(r.exams);},[]);
    var resultDoc = (freshResults.find(function(r){return r.exams.length;}) || freshResults[0]).node.ownerDocument;
    // Only follow next-page controls on the confirmed results page, never links in the student menu.
    var pager = Array.from(resultDoc.querySelectorAll(".pagination,.pager,.Nsb_r_fy,#PageNavigation,.pageinfo"))
        .filter(visible).map(function(el){return key(el.textContent);}).join(" ");
    var position = /第(\d+)页?[,，\/ ]*(?:共|\/)\s*(\d+)页/.exec(pager) ||
        /(\d+)\s*\/\s*(\d+)页/.exec(pager);
    var lastPage=position && Number(position[1])>=Number(position[2]);
    var next = !lastPage && Array.from(resultDoc.querySelectorAll("a,button,input[type=button],input[type=submit]")).find(function(el){
        var img=el.querySelector("img");
        return visible(el) && [el.textContent,el.value,el.title,el.getAttribute("aria-label"),img && img.alt].some(function(s){return /^(?:下一页|下页|下一頁)$/.test(key(s));}) &&
            !el.disabled && !el.closest("[disabled],[aria-disabled=true],.disabled,.ui-state-disabled") &&
            !(el.tagName==="A" && !el.hasAttribute("onclick") && !(el.getAttribute("href")||"").replace(/^(?:#|javascript:\s*(?:void\s*\(0\))?;?)$/i,""));
    });
    if (position && !lastPage && !next) return fail("无法读取考试安排的下一页，请重试。");
    var exams=(ctx.previous || []).concat(records);
    if (next) {
        var page=(ctx.page || 1)+1;
        var signature=JSON.stringify(records), seen=ctx.seen || [];
        if (seen.indexOf(signature)>=0 || page>100) return fail("考试安排分页未能完整读取，请重试。");
        var href=next.getAttribute("href"), nextUrl;
        if (href && !/^javascript:|^#/.test(href)) {
            nextUrl=scopedUrl(resultDoc,href);
            if (!nextUrl) return fail("考试安排的下一页地址无法识别。");
        }
        state.pending=function(){
            if (!next.isConnected) return false;
            if (nextUrl) next.setAttribute("href",nextUrl.href);
            next.click(); return true;
        };
        return {stage:"examPage",data:{term:ctx.term,frame:resultFrame,resultPath:nextUrl ? nextUrl.pathname : state.resultPath || ctx.resultPath,previous:exams,page:page,seen:seen.concat(signature)}};
    }
    return {stage:"done",data:{term:ctx.term,exams:exams}};
})
