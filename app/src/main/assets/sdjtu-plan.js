// Read the course table for the native sync or the school's visible reader.
(function (navigate, resetMenus, request) {
    "use strict";
    function trusted(doc) {
        return doc && doc.body && doc.location.protocol === "https:" &&
            /(^|\.)sdjtu\.edu\.cn$/i.test(doc.location.hostname) &&
            !/\/(?:login|fingerprint|cas\/login)(?:[./;]|$)/i.test(doc.location.pathname) &&
            !Array.from(doc.querySelectorAll("input[type=password]")).some(visible);
    }
    if (!trusted(document)) return {base:"", stage:"waiting"};
    if (window.__lvlvInspectPlan) return window.__lvlvInspectPlan(navigate, resetMenus, request);

    var observed = [], reader = null, timer;
    var fields = {
        name:["课程名称","课程名","课程/环节名称","课程（环节）名称"], code:["课程编号","课程代码","课程编码","课程号","课号"],
        system:["课程体系","体系","课程模块","课程类别","课程分类"], group:["选课组","课程组"],
        term:["开设学期","建议修读学期","建议学期","开课学期","开设学年学期","学年学期","修读学期","学期安排","学期"],
        credit:["学分","课程学分"], hours:["总学时","总课时","学时"],
        nature:["课程性质","性质","类别"], kind:["课程属性","属性"],
        status:["完成情况","修读状态","修读情况","修读结果"]
    };
    function clean(text) { return (text || "").replace(/\s+/g, " ").trim(); }
    function label(text) { return clean(text).replace(/\s/g, ""); }
    function cellText(cell) { return cell ? clean(cell.innerText || cell.textContent) : ""; }
    function visible(el) {
        return el.getClientRects().length && el.ownerDocument.defaultView.getComputedStyle(el).visibility !== "hidden";
    }
    function collect(win, docs) {
        try {
            var doc = win.document;
            if (!trusted(doc) || docs.indexOf(doc) >= 0 || docs.length >= 24) return;
            if (win !== window && (!win.frameElement || !visible(win.frameElement))) return;
            docs.push(doc);
            for (var i = 0; i < win.frames.length; i++) collect(win.frames[i], docs);
        } catch (ignored) { /* The browser controls access to cross-origin frames. */ }
    }
    function fieldFor(cell) {
        var key = label(cellText(cell)).replace(/[：:]$/, "");
        return Object.keys(fields).find(function (k) { return fields[k].indexOf(key) >= 0; });
    }
    function courses(table, dataRows) {
        var rows = Array.from(table.rows);
        var start = rows.findIndex(function (row) {
            return Array.from(row.cells).some(function (cell) {
                return !cell.querySelector("table") && fieldFor(cell) === "name";
            });
        });
        if (start < 0) return [];
        var spans = [], cols = {}, items = [], header = true;
        var termHeading = /^(?:第[一二三四五六七八九十0-9]+学期|[0-9]{4}[-～][0-9]{4}[-－][12])$/;
        var caption = table.caption && label(table.caption.textContent);
        var sectionTerm = termHeading.test(caption) ? caption : "";
        rows.slice(start).forEach(function (row, y) {
            var cells = Array.from(row.cells), grid = [], x = 0;
            spans.forEach(function (span, n) { if (span.until > y) grid[n] = span.cell; });
            cells.forEach(function (cell) {
                while (grid[x]) x++;
                for (var n = 0; n < cell.colSpan; n++, x++) {
                    grid[x] = cell;
                    spans[x] = {cell:cell, until: cell.rowSpan === 0 ? rows.length : y + cell.rowSpan};
                }
            });
            // Some 强智 templates use TD for the second row of grouped headers.
            if (header && (y === 0 || cells.every(function (cell) {
                return cell.tagName === "TH" || !cellText(cell) || !!fieldFor(cell) || /(?:学时|课时)$/.test(label(cellText(cell)));
            }))) {
                grid.forEach(function (cell, n) {
                    var key = fieldFor(cell);
                    if (key) cols[key] = n;
                });
                return;
            }
            header = false;
            if (cells.length === 1 && cells[0].colSpan > 1) {
                // A merged heading starts a new section; only an explicit term can be inherited.
                var heading = label(cellText(cells[0]));
                sectionTerm = termHeading.test(heading) ? heading : "";
                return;
            }
            if (cols.name === undefined || Object.keys(cols).length < 2 || !grid[cols.name] ||
                grid[cols.name].colSpan !== 1 || cells.some(function (cell) {
                    return /^(?:合计|小计|总计)(?:$|[\s:：（(])/.test(cellText(cell));
                })) return;
            var item = {};
            Object.keys(cols).forEach(function (key) { item[key] = cellText(grid[cols[key]]); });
            if (!item.term && sectionTerm) item.term = sectionTerm;
            if (item.name && fieldFor(grid[cols.name]) !== "name" && !/^(?:没有|暂无).*(?:数据|记录)$/.test(item.name)) {
                items.push(item);
                dataRows.push(row);
            }
        });
        return items;
    }
    function planPage(doc) {
        if (/\/pyfa(?:\/|$)/i.test(doc.location.pathname)) return true;
        var titles = [doc.title].concat(Array.from(doc.querySelectorAll("h1,h2,h3,caption,legend,.Nsb_r_title"))
            .map(function (e) { return e.textContent; }));
        return titles.some(function (title) {
            return /(?:培养方案|培养计划|课程设置总表|执行计划)/.test(label(title));
        });
    }
    function planUrl(doc, raw) {
        try {
            var url = new URL(raw || doc.location.href, doc.baseURI);
            if (url.origin !== doc.location.origin || url.username || url.password) return null;
            var start = doc.location.pathname.search(/\/jsxsd\//i);
            if (start > 0 && /^\/jsxsd\//i.test(url.pathname))
                url.pathname = doc.location.pathname.slice(0, start) + url.pathname;
            return url;
        } catch (ignored) { return null; }
    }
    function queryButton(form) {
        return Array.from(form.elements).concat(Array.from(form.querySelectorAll("a[onclick]"))).find(function(el) {
            return el.matches("button,input[type=submit],input[type=button],a[onclick]") && visible(el) && !el.disabled &&
                [el.textContent,el.value,el.title].some(function (text) {
                    return /^(?:查询|检索|查看执行计划)$/.test(label(text));
                });
        });
    }
    function executionQuery(form, button) {
        var raw = button && button.hasAttribute("formaction") ? button.getAttribute("formaction") : form.getAttribute("action");
        var url = planUrl(form.ownerDocument, raw);
        return url && /\/jsxsd\/pyfa\/pyfa_query(?:\.(?:do|jsp))?\/?$/i.test(url.pathname);
    }
    function executionResult(doc) {
        var frame = doc.defaultView.frameElement;
        if (!frame || !frame.name) return false;
        return Array.from(frame.ownerDocument.forms).some(function (f) {
            var button = queryButton(f);
            return ((button && button.getAttribute("formtarget")) || f.target) === frame.name && executionQuery(f, button);
        });
    }
    function courseTables(doc) {
        var tables = Array.from(doc.querySelectorAll("table"));
        if (!planPage(doc) && !executionResult(doc)) tables = tables.filter(function (t) { return t.id === "mxh"; });
        var result = [];
        tables.filter(visible).forEach(function (table) {
            var dataRows = [], items = courses(table, dataRows);
            if (items.length) result.push({node:table, rows:items, dataRows:dataRows});
        });
        return result;
    }
    function emptyPlan(doc) {
        return Array.from(doc.querySelectorAll('td,p,.no-data,.empty')).filter(function (el) {
            if (!visible(el)) return false;
            var text = label(el.textContent);
            var table = el.closest('table');
            var courseHead = table && (table.id === 'mxh' || Array.from(table.rows).slice(0,4).some(function (row) {
                return Array.from(row.cells).some(function (cell) {return fieldFor(cell) === 'name';});
            }));
            return /^(?:暂无培养方案|未设置培养方案|暂无计划课程|没有培养方案数据)[。！!]?$/i.test(text) ||
                courseHead && /^(?:暂无|没有|未查询到|无)(?:相关|符合条件的)?(?:数据|记录|课程)[。！!]?$/i.test(text);
        }).map(function (el) { return {node:el, rows:[], dataRows:[]}; });
    }
    function node(doc, tag, text) {
        var el = doc.createElement(tag);
        if (text) el.textContent = text;
        return el;
    }
    function option(select, value, title) {
        var el = node(select.ownerDocument, "option", title);
        el.value = value;
        select.appendChild(el);
    }

    function createReader(source) {
        // Frameset documents cannot display ordinary HTML next to their frames.
        var host = document.body.tagName === "BODY" ? document : source;
        var mount = node(host, "div");
        mount.setAttribute("data-lvlv-plan-reader", "");
        host.body.appendChild(mount);
        if (!mount.attachShadow) { mount.remove(); return null; }
        var shadow = mount.attachShadow({mode:"open"});
        var style = node(host, "style");
        style.textContent =
            ":host{all:initial;--paper:#f5f4f0;--ink:#111;--sub:#55554f;--line:#dcdad3;--card:#fbfaf7;color-scheme:light}" +
            "@media(prefers-color-scheme:dark){:host{--paper:#0e0e0d;--ink:#eceae4;--sub:#b3b1aa;--line:#363630;--card:#171716;color-scheme:dark}}" +
            "*{box-sizing:border-box} section,button,select,input{font:16px/1.5 sans-serif;color:var(--ink)}" +
            "section{position:fixed;inset:0;top:0;right:0;bottom:0;left:0;z-index:2147483646;background:var(--paper);overflow:auto;padding:16px 20px 32px;text-align:left}" +
            "main{max-width:52rem;margin:auto} h1{font-size:24px;letter-spacing:0;margin:0} header,.tools{display:flex;align-items:center;justify-content:space-between;gap:8px}" +
            ".tools{flex-wrap:wrap;margin:12px 0} input,select,button{min-height:48px;padding:8px 12px;border:1px solid var(--line);border-radius:4px;background:var(--card)}" +
            "input{flex:2 1 12rem;min-width:0}select{flex:1 1 9rem;min-width:0}button{cursor:pointer} .launch{position:fixed;bottom:12px;right:12px;z-index:2147483646}" +
            "details{border-top:1px solid var(--line)}summary{min-height:48px;padding:12px 0;font-weight:bold;cursor:pointer}" +
            "article{padding:8px 0 8px 12px;border-top:1px solid var(--line);overflow-wrap:break-word}article strong{font-size:16px}small,p{display:block;color:var(--sub)}" +
            "small{font:14px/1.6 sans-serif}p{font:14px/1.5 monospace;font-variant-numeric:tabular-nums}[hidden]{display:none!important}";
        shadow.appendChild(style);
        var panel = node(host, "section");
        panel.setAttribute("aria-label", "培养方案课程");
        var main = node(host, "main"), head = node(host, "header"), tools = node(host, "div");
        var close = node(host, "button", "查看教务原页"), open = node(host, "button", "课程阅读视图");
        var search = node(host, "input"), term = node(host, "select"), count = node(host, "p"), groups = node(host, "div");
        close.type = open.type = "button";
        search.type = "search";
        search.placeholder = "搜课程名称或课程号";
        search.setAttribute("aria-label", search.placeholder);
        term.setAttribute("aria-label", "开设学期");
        count.setAttribute("aria-live", "polite");
        tools.className = "tools"; open.className = "launch";
        head.appendChild(node(host, "h1", "培养方案")); head.appendChild(close);
        tools.appendChild(search); tools.appendChild(term);
        [head, tools, count, groups].forEach(function (el) { main.appendChild(el); });
        panel.appendChild(main); shadow.appendChild(panel); shadow.appendChild(open);
        var items = [], signature = "", meta, previous, inserted;
        function restoreViewport() {
            if (!meta) return;
            if (inserted) meta.remove();
            else if (previous === null) meta.removeAttribute("content");
            else meta.setAttribute("content", previous);
            meta = null;
        }
        function show(on) {
            panel.hidden = !on; open.hidden = on;
            if (!on) { restoreViewport(); return; }
            if (!host.head || meta) return;
            meta = host.querySelector("meta[name=viewport]");
            inserted = !meta;
            if (inserted) {
                meta = node(host, "meta");
                meta.name = "viewport";
                host.head.appendChild(meta);
            }
            previous = meta.getAttribute("content");
            meta.content = "width=device-width, initial-scale=1";
        }
        function render() {
            var needle = clean(search.value).toLowerCase();
            var matches = items.filter(function (c) {
                return (!term.value || term.value === c.term) &&
                    (!needle || (c.name + " " + (c.code || "")).toLowerCase().indexOf(needle) >= 0);
            });
            var total = matches.reduce(function (n, c) {
                return /^\d+(?:\.\d+)?$/.test(c.credit || "") ? n + Number(c.credit) : NaN;
            }, 0);
            count.textContent = "本页匹配 " + matches.length + " 门课程" +
                (matches.length && isFinite(total) ? " · 所列课程学分合计 " + Number(total.toFixed(2)) : "");
            groups.textContent = "";
            var bySystem = new Map();
            matches.forEach(function (c) {
                var key = c.system || c.nature || "方案课程";
                if (!bySystem.has(key)) bySystem.set(key, []);
                bySystem.get(key).push(c);
            });
            bySystem.forEach(function (list, key) {
                var part = node(host, "details");
                part.open = bySystem.size === 1 || !!needle || !!term.value;
                part.appendChild(node(host, "summary", key + " · " + list.length + " 门"));
                list.forEach(function (c) {
                    var entry = node(host, "article");
                    entry.appendChild(node(host, "strong", c.name));
                    var details = [c.code, c.group, c.nature !== key ? c.nature : "", c.kind, c.term && "学期 " + c.term,
                        c.credit && c.credit + " 学分", c.hours && c.hours + " 学时", c.status];
                    entry.appendChild(node(host, "small", details.filter(Boolean).join(" · ")));
                    part.appendChild(entry);
                });
                groups.appendChild(part);
            });
        }
        close.onclick = function () { show(false); open.focus(); };
        open.onclick = function () { show(true); close.focus(); };
        term.onchange = search.oninput = render;
        show(true);
        return {
            source:source, mount:mount,
            update:function (rows) {
                var next = JSON.stringify(rows);
                if (signature === next) return;
                items = rows; signature = next;
                var selected = term.value, terms = Array.from(new Set(rows.map(function (c) { return c.term; }).filter(Boolean)));
                term.textContent = "";
                option(term, "", "全部学期");
                terms.forEach(function (s) { option(term, s, "学期 " + s); });
                if (terms.indexOf(selected) >= 0) term.value = selected;
                term.hidden = !terms.length;
                render();
            },
            dispose:function () { restoreViewport(); mount.remove(); }
        };
    }

    function refreshSoon() {
        clearTimeout(timer);
        timer = setTimeout(function () { inspect(false, false); }, 120);
    }
    function observe(docs) {
        observed = observed.filter(function (entry) {
            if (docs.indexOf(entry.doc) >= 0 && entry.body === entry.doc.body) return true;
            entry.observer.disconnect();
            entry.doc.removeEventListener("load", entry.onLoad, true);
            return false;
        });
        docs.forEach(function (doc) {
            if (observed.some(function (entry) { return entry.doc === doc; })) return;
            var observer = new MutationObserver(function (changes) {
                if (changes.some(function (m) {
                    var el = m.target.nodeType === 1 ? m.target : m.target.parentElement;
                    if (el && el.closest("table,frame,iframe")) return true;
                    return Array.from(m.addedNodes).concat(Array.from(m.removedNodes)).some(function (n) {
                        return n.nodeType === 1 && (n.matches("table,frame,iframe") || !!n.querySelector("table,frame,iframe"));
                    });
                })) refreshSoon();
            });
            observer.observe(doc.body, {childList:true, subtree:true, characterData:true, attributes:true,
                attributeFilter:["hidden","style","class","rowspan","colspan"]});
            var onLoad = function (e) {
                if (/^(IFRAME|FRAME)$/.test(e.target.tagName || "")) refreshSoon();
            };
            doc.addEventListener("load", onLoad, true);
            observed.push({doc:doc, body:doc.body, observer:observer, onLoad:onLoad});
        });
    }
    // Only retain known read-only *entry pages* the portal actually reached.
    // A selected major or student's detail address can contain a personal identifier.
    function stableEntry(doc) {
        var here = doc.location;
        if (here.search || here.hash || !/\/jsxsd\/pyfa\/(?:topyfamx|pyfa_query)(?:\.(?:do|jsp))?\/?$/i.test(here.pathname)) return "";
        return here.origin + here.pathname;
    }
    function oldPlanForm(doc, request, hasRows) {
        var form = Array.from(doc.forms).find(function (f) {
            if (!visible(f)) return false;
            return executionQuery(f, queryButton(f));
        });
        if (!form) return null;
        var term = Array.from(form.querySelectorAll("select")).find(function (s) {
            return /^(?:xnxq01id|xnxqh|xq01id|kcxq|xueqi)$/i.test((s.name || s.id).trim()) && !s.disabled;
        });
        var options = term ? Array.from(term.options).filter(function (o) {
            return !o.disabled && !!clean(o.textContent) && !/请选择/.test(label(o.textContent));
        }) : [];
        var getLabel = function(o) { return "开课学期 · " + clean(o.textContent); };
        var selected = options.find(function(o) { return getLabel(o) === request.planChoice; }) ||
            options.find(function(o) { return /^(?:全部(?:学期)?|所有学期|不限)$/.test(label(o.textContent)); }) ||
            options.find(function(o) {return o.selected;}) || options[0];
        var meta = {planChoice:selected ? getLabel(selected) : "",planChoices:options.map(function(o) {
            var title = getLabel(o);
            return {value:title,label:title};
        })};
        if (hasRows && (!selected || selected.selected)) return {data:meta};
        return {action:form,data:meta,submit:function(){
            var button = queryButton(form);
            if (!executionQuery(form, button)) return false;
            var action = planUrl(doc, form.getAttribute("action"));
            if (action && action.href !== form.action) form.action = action.href;
            if (button && button.hasAttribute("formaction")) {
                var override = planUrl(doc, button.getAttribute("formaction"));
                if (override && override.href !== button.formAction) button.formAction = override.href;
            }
            if (selected) term.value = selected.value;
            if (form.target.toLowerCase() === "_blank") form.target = "_self";
            if (button && (button.getAttribute("formtarget") || "").toLowerCase() === "_blank") button.formTarget = "_self";
            if (button) button.click();
            else if (term && term.onchange) term.dispatchEvent(new doc.defaultView.Event("change", {bubbles:true}));
            else doc.defaultView.HTMLFormElement.prototype.submit.call(form);
        }};
    }
    function choiceUrl(el) {
        var url = planUrl(el.ownerDocument, el.getAttribute("href"));
        return url && /\/jsxsd\//i.test(url.pathname) && !/delete|remove|save|update|submit|export/i.test(url.pathname) ? url : null;
    }
    function planChoice(doc, request, tables) {
        var options = [], actions = [];
        Array.from(doc.querySelectorAll('a[href],button,[onclick],input[type=button]')).forEach(function (el) {
            if (!visible(el) || el.closest('#mxh,#LeftMenu1_divChildMenu,#divFirstMenuClass,nav,aside,header,[role=navigation],.Nsb_l,.Nsb_l_list') || tables.some(function (t) { return t.node.contains(el); })) return;
            var text = clean(el.innerText || el.value || el.title), row = el.closest('tr');
            if (!text || text.length > 80 || /新增|删除|打印|导出|修改|编辑|设置|申报|申请|退出|保存|提交|维护|下载/.test(text)) return;
            var target = el.href;
            var detailLink = false;
            if (target && !/^javascript:/i.test(target)) {
                var url = choiceUrl(el);
                if (!url) return;
                detailLink = /\/pyfa\/topyfamx(?:[;/]|$)/i.test(url.pathname);
            }
            var named = /专业|方案|培养计划/.test(text) || detailLink, details = /^(?:查看|详情|明细|查看详情|查看明细|方案明细|方案详[细情]|培养方案明细)$/.test(text);
            if (!named && !(details && row)) return;
            if (/^(?:培养方案|培养管理|培养方案查询|个人培养方案|我的培养方案|指导培养方案|培养方案执行计划|教学执行计划|执行计划)$/.test(text) ||
                (!row && /^(?:培养方案明细|专业培养方案|教学计划)$/.test(text))) return;
            var title = details && row ? Array.from(row.cells).filter(function (c) {
                return !c.contains(el) && !c.querySelector('a,button,table');
            }).map(cellText).filter(Boolean).join(' · ') : text;
            if (!title || title.length > 160 || row && row.closest('nav')) return;
            if (el.closest('nav,header,aside,[role=navigation]')) return;
            if (options.some(function (o) {return o.value === title;})) return;
            options.push({value:title,label:title}); actions.push(el);
        });
        if (!options.length) return null;
        var i = options.findIndex(function (o) { return o.value === request.planChoice; });
        if (i < 0) i = 0;
        return {action:actions[i], data:{planChoice:options[i].value,planChoices:options}};
    }
    var selection = null, beforeClick = [];
    function fresh(result) {
        var old = beforeClick.find(function (item) { return item.node === result.node; });
        return !old || old.dataRows.length !== result.dataRows.length ||
            result.dataRows.some(function (row, i) { return old.dataRows[i] !== row; }) ||
            JSON.stringify(old.rows) !== JSON.stringify(result.rows);
    }
    function inspect(go, reset, request) {
        var docs = [];
        collect(window, docs);
        if (!request || !request.sync) observe(docs);
        var pages = docs.filter(function (d) { return /\/jsxsd(?:\/|$)/i.test(d.location.pathname); });
        var syncing = request && request.sync;
        var official = pages.find(planPage), results = [];
        pages.forEach(function (doc) {
            results = results.concat(courseTables(doc));
            if (syncing && (planPage(doc) || executionResult(doc))) results = results.concat(emptyPlan(doc));
        });
        var current = results.length ? results[0].node.ownerDocument : official;
        var base = current ? current.location.origin + current.location.pathname.split(/\/jsxsd(?:\/|$)/i)[0] + "/jsxsd/" : "";
        var entry = pages.map(stableEntry).find(Boolean) || "";
        var currentChoices = {}, manualList = false;
        if (syncing) {
            var context = request.context || {};
            if (!request.submitted && !request.capture) {
                for (var i = 0; !selection && i < pages.length; i++) {
                    var doc = pages[i];
                    if (!planPage(doc)) continue;
                    var legacy = oldPlanForm(doc, request, results.some(function (r) {return r.node.ownerDocument === doc && r.rows.length;}));
                    if (legacy) {
                        currentChoices = legacy.data;
                        if (legacy.action) selection = legacy;
                    } else {
                        selection = planChoice(doc, request, results);
                    }
                }
                if (selection) return {base:base,entryPage:entry,stage:"submitted",data:selection.data};
            } else if (selection && !request.capture) {
                var action = selection.action, submit = selection.submit;
                selection = null;
                if (!action.isConnected) return {base:base,stage:"error",message:"学校方案列表已更新，请重新同步。"};
                // The previous detail can stay visible after a click, even inside a sibling frame.
                beforeClick = results;
                if (submit) {
                    if (submit() === false) return {base:base,stage:"error",message:"学校查询地址已变更，请重新同步。"};
                }
                else {
                    if (action.href && !/^javascript:/i.test(action.href)) {
                        var link = choiceUrl(action);
                        if (!link) return {base:base,stage:"error",message:"学校方案链接已变更，请重新同步。"};
                        if (link.href !== action.href) action.href = link.href;
                    }
                    action.click();
                }
                return {base:base,entryPage:entry,stage:"loading"};
            }
            if (!request.capture) results = results.filter(fresh);
        }
        var first = results.find(function (r) { return r.rows.length; }) || results[0];
        var found = first && first.node.ownerDocument, rows = [];
        results.forEach(function (r) { if (r.node.ownerDocument === found) rows = rows.concat(r.rows); });
        if (syncing) {
            if (first) return {base:base,entryPage:entry,stage:"done",data:{courses:rows,
                planChoice:context.planChoice || currentChoices.planChoice || "",
                planChoices:context.planChoices || currentChoices.planChoices || []}};
            if (request.capture) return {base:base,entryPage:entry,stage:"waiting"};
            if (request.submitted) return {base:base,entryPage:entry,stage:"loading"};
        }
        if (reader && (reader.source !== found || !reader.mount.isConnected)) { reader.dispose(); reader = null; }
        if (found) {
            if (!reader) reader = createReader(found);
            if (reader) reader.update(rows);
            return {base:base,entryPage:entry, stage:reader ? "plan" : "official"};
        }
        if (!syncing && official) manualList = !!oldPlanForm(official, {}, false) || !!planChoice(official, {}, results);
        return {base:base, entryPage:entry, stage:manualList ? "official" : "waiting"};
    }
    window.__lvlvInspectPlan = inspect;
    return inspect(navigate, resetMenus, request);
})
