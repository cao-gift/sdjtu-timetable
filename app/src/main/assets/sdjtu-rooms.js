// Read the official room form and result grid in the student's school WebView.
(function (navigate, resetMenus, request) {
    var silent = request && request.sync;
    if (silent && window.__lvlvRoomSync) return window.__lvlvRoomSync(request);
    if (!silent && window.__lvlvRooms) return window.__lvlvRooms.inspect();

    var PANEL = '[data-lvlv-rooms]', days = ['','星期一','星期二','星期三','星期四','星期五','星期六','星期日'];
    function array(list) { return Array.prototype.slice.call(list); }
    function compact(s) { return (s || '').replace(/\s+/g, ''); }
    function school(doc) {
        return doc.body && doc.location.protocol === 'https:' &&
            /(^|\.)sdjtu\.edu\.cn$/i.test(doc.location.hostname) &&
            !/\/(?:login|fingerprint|cas\/login)(?:[./;]|$)/i.test(doc.location.pathname) &&
            !array(doc.querySelectorAll('input[type=password]')).some(function(input) {return input.getClientRects().length;});
    }
    function academic(doc) { return /\/jsxsd\//i.test(doc.location.pathname); }
    function dayNumber(s) {
        var m = /^(?:星期|周|礼拜)([一二三四五六日天1-7])$/.exec(compact(s));
        return m ? ('一二三四五六日'.indexOf(m[1].replace('天','日')) + 1 || Number(m[1])) : 0;
    }
    function periodText(text) {
        var raw = compact(text).replace(/^第|(?:小节|节次|节)$/g,'').replace(/[－—–～]/g,'-');
        if (!/^\d{1,2}(?:[-~至、,，]\d{1,2})*$/.test(raw) && !/^(?:\d\d){2,8}$/.test(raw)) return '';
        var ns = /^(?:\d\d){2,8}$/.test(raw) ? raw.match(/\d\d/g) : raw.match(/\d+/g);
        var numbers = ns.map(Number);
        if (numbers.some(function (n, i) { return n < 1 || n > 40 || (i && n <= numbers[i-1]); })) return '';
        return '第' + numbers.join(/[-~至]/.test(raw) ? '–' : '、') + '节';
    }
    function periodLabel(cell) { return periodText(cell.getAttribute('tdvalue') || cell.textContent); }
    var queryPath = /\/(?:kbcx\/kbxx_classroom|kbxx\/jsjy_query)(?:\.(?:jsp|do))?\/?$/i;
    var resultPath = /\/(?:kbcx\/kbxx_classroom_ifr|kbxx\/jsjy_query2)(?:\.(?:jsp|do))?\/?$/i;
    function knownResultAction(form) {
        var value = form.getAttribute('action');
        if (!value) return false;
        try {
            var url = new URL(value,form.ownerDocument.baseURI);
            return url.origin === form.ownerDocument.location.origin && resultPath.test(url.pathname);
        } catch (ignored) {return false;}
    }
    function isRoomForm(form) {
        // In older JSPs the browser can associate table controls with a form whose DOM subtree is empty.
        var controls = array(form.elements), text = compact(form.textContent);
        if (form.querySelectorAll('select').length >= 4 &&
            ['校区','教学楼','教室','周次','星期','节次'].every(function (s) {return text.indexOf(s) >= 0;})) return true;
        var selectors = roomFields(form);
        return selectors.campus && selectors.week && (knownResultAction(form) || queryPath.test(form.ownerDocument.location.pathname) ||
            /(?:空闲教室|教室课表|空教室)(?:查询)?/.test(form.ownerDocument.title));
    }
    function formEntry(form) {
        if (!form || resultPath.test(form.ownerDocument.location.pathname)) return '';
        return queryPath.test(form.ownerDocument.location.pathname) || knownResultAction(form)
            ? form.ownerDocument.location.href : '';
    }
    function queryButton(form) {
        var doc = form.ownerDocument;
        var buttons = array(doc.querySelectorAll('a,button,input[type=button],input[type=submit],input[type=image],[role=button]'))
            .filter(function (el) {
                return el.getClientRects().length && [el.value,el.title,el.getAttribute('alt'),el.textContent].some(function (label) {
                    return /^(?:查找|查询|检索|查询教室|开始查询|查空教室)$/.test(compact(label));
                });
            });
        var own = buttons.find(function (el) {return el.form===form || form.contains(el);});
        if (own) return own;
        // Old toolbars can sit outside Form1 and call its school's query callback.
        if (buttons.length===1 && !buttons[0].closest('form') && !buttons[0].form &&
            array(doc.forms).filter(isRoomForm).length===1) return buttons[0];
        // A plain HTML search form can have only implicit submit. Use the observed action and method.
        return knownResultAction(form) ? form : null;
    }
    function submitQuery(form,button) {
        // A school button may submit the associated form from a toolbar outside it.
        // Keep its method, named frame, hidden fields and submitter override intact.
        var here=form.ownerDocument.location;
        var prefix=here.hostname==='wwebvpn.sdjtu.edu.cn' &&
            /^(\/https?\/[a-f\d]{32,})\/jsxsd\//i.exec(here.pathname);
        if (prefix) {
            var action=form.getAttribute('action') || '';
            var override=button.getAttribute('formaction') || '';
            if (/^\/jsxsd\//i.test(action)) form.setAttribute('action',prefix[1]+action);
            if (/^\/jsxsd\//i.test(override)) button.setAttribute('formaction',prefix[1]+override);
        }
        if (button.tagName!=='FORM') {button.click(); return;}
        if (typeof button.requestSubmit==='function') {button.requestSubmit(); return;}
        var win=button.ownerDocument.defaultView;
        if (button.checkValidity() && button.dispatchEvent(new win.Event('submit',{bubbles:true,cancelable:true})))
            win.HTMLFormElement.prototype.submit.call(button);
    }
    function field(form, names, except) {
        for (var i = 0; i < names.length; i++) {
            var item = form.elements.namedItem(names[i]);
            if (item && item!==except && item.tagName === 'SELECT') return item;
        }
        return null;
    }
    function roomFields(form) {
        var fields = {};
        array(form.elements).forEach(function(c) {
            var key = (c.name || c.id || '').toLowerCase(), label = compact(fieldLabel(c));
            if (c.tagName === 'INPUT' && /^(text|number|search)$/i.test(c.type)) {
                if (/教室/.test(label) || /^(?:jsmc|jsbh|room|classroom)$/.test(key)) fields.roomInput = c;
                return;
            }
            if (c.tagName !== 'SELECT') return;
            if (/校区/.test(label) || /^(?:xqid|xqbh|xqdm|campusid|campus)$/.test(key)) fields.campus = c;
            else if (/教学楼|楼栋|教学建筑/.test(label) || /^(?:jxlid|jxlbh|jxl|jzwid|buildingid|building)$/.test(key)) fields.building = c;
            else if (/结束周|截止周|终止周/.test(label) || /^(?:zc2|zcend|jszc)$/.test(key)) fields.end = c;
            else if (/周次|起始周|开始周|教学周/.test(label) || /^(?:zc|zc1|zcstar|zcstart|kszc|week|weekno)$/.test(key)) fields.week = c;
            else if (/结束星期|截止星期|终止星期/.test(label) || /^(?:xq2|xqend|jsxq)$/.test(key)) fields.dayEnd = c;
            else if (/星期|起始星期|开始星期/.test(label) || /^(?:xq|xq1|xqstart|ksxq)$/.test(key)) fields.dayStart = c;
            else if (/结束节次|截止节次|终止节次/.test(label) || /^(?:jc2|jcend|jsjc)$/.test(key)) fields.periodEnd = c;
            else if (/节次|起始节次|开始节次/.test(label) || /^(?:jc|jc1|jcstart|ksjc)$/.test(key)) fields.periodStart = c;
        });
        return fields;
    }

    function placeholder(option) {
        var label = compact(option.textContent).replace(/[-－—*＊()（）]/g,'');
        return !option.value.trim() || /^(?:all|null|undefined|-1)$/i.test(option.value.trim()) ||
            !label || /请选择|选择校区|选择教学楼|选择周次|不限|全部|所有|加载中|^任意|^无$/.test(label);
    }
    function optionsOf(select) {
        return select && !select.disabled ? array(select.options).filter(function(o) {
            return !o.disabled && !o.matches('optgroup[disabled] > option');
        }) : [];
    }
    function optionWeek(o) {
        if (!o || placeholder(o)) return 0;
        var text = compact(o.textContent);
        var match = /^(?:第)?(\d{1,2})(?:周(?:[（(].*)?)?$/.exec(text) || /^(\d{1,2})$/.exec(o.value);
        if (match) return Number(match[1]) <= 60 ? Number(match[1]) : 0;
        var chinese = /^第?([一二三四五六七八九十]{1,3})周(?:[（(].*)?$/.exec(text);
        if (!chinese) return 0;
        var parts=chinese[1].split('十'), digits='零一二三四五六七八九';
        var number=parts.length===1 ? digits.indexOf(parts[0]) :
            (parts[0] ? digits.indexOf(parts[0]) : 1)*10 + (parts[1] ? digits.indexOf(parts[1]) : 0);
        return number > 0 && number <= 60 ? number : 0;
    }
    function optionDay(o) {
        if (!o || placeholder(o)) return 0;
        var d = dayNumber(o.textContent);
        if (d >= 1 && d <= 7) return d;
        return /^[1-7]$/.test((o.value || '').trim()) ? Number(o.value.trim()) : 0;
    }
    function dayOption(select, day) {
        return optionsOf(select).find(function(o){return optionDay(o)===Number(day) && Number(day)>=1 && Number(day)<=7;});
    }
    function emptyOptionValue(select) {
        var options = optionsOf(select);
        var blank = options.find(function(o){return !o.value;}) || options.find(placeholder);
        return blank ? blank.value : '';
    }
    function namedChoices(select) {
        return optionsOf(select).filter(function(o){return !placeholder(o);}).map(function(o) {
            return {value:o.value,label:o.textContent.trim()};
        });
    }
    function weekOption(select,week) {
        return optionsOf(select).find(function(o){return optionWeek(o)===Number(week) && Number(week)>0;});
    }
    function matchByHint(list, hint) {
        var needle = compact(hint);
        if (!needle) return '';
        var exact = list.find(function(o){return compact(o.label)===needle;});
        if (exact) return exact.value;
        var partial = list.find(function(o){return compact(o.label).indexOf(needle)>=0;});
        return partial ? partial.value : '';
    }
    function roomChoices(fields, wanted) {
        var choices = {campuses:namedChoices(fields.campus),weeks:[],buildings:[],days:[],periods:[]};
        optionsOf(fields.week).forEach(function(o) {
            var n=optionWeek(o);
            if (n && (!fields.end || weekOption(fields.end,n)) && !choices.weeks.some(function(w){return w.value===String(n);}))
                choices.weeks.push({value:String(n),label:o.textContent.trim()});
        });
        optionsOf(fields.dayStart).forEach(function(o) {
            var d=optionDay(o);
            if (d && (!fields.dayEnd || dayOption(fields.dayEnd,d)) && !choices.days.some(function(x){return x.value===String(d);}))
                choices.days.push({value:String(d),label:days[d]});
        });
        if (!choices.days.length) {
            for (var d=1; d<=7; d++) choices.days.push({value:String(d),label:days[d]});
        }
        choices.periods = namedChoices(fields.periodStart);
        function selected(list,value) {return list.some(function(o){return o.value===String(value);}) ? String(value) : '';}
        choices.campus = selected(choices.campuses, wanted.campus) || matchByHint(choices.campuses, wanted.campusHint);
        choices.week = selected(choices.weeks, wanted.week);
        if (choices.campus && fields.campus.value===choices.campus) choices.buildings=namedChoices(fields.building);
        choices.building = selected(choices.buildings, wanted.building) || matchByHint(choices.buildings, wanted.buildingHint);
        choices.day = selected(choices.days, wanted.day);
        choices.period = selected(choices.periods, wanted.period);
        choices.room = typeof wanted.room === 'string' ? wanted.room.trim() : '';
        choices.hasRoomField = !!fields.roomInput;
        return choices;
    }
    function setValue(select,value) {
        if (select.value===value && select.selectedIndex >= 0) return false;
        select.value=value;
        ['input','change'].forEach(function(type){select.dispatchEvent(new select.ownerDocument.defaultView.Event(type,{bubbles:true}));});
        return true;
    }
    function changeCampus(core,campus) {
        var building=core.building;
        var before=building && {select:building,first:building.options[0],
            choices:JSON.stringify(namedChoices(building)),time:Date.now()};
        if (!setValue(core.campus,campus)) {
            // The school may preselect a campus but only populate buildings on change.
            core.campus.dispatchEvent(new core.campus.ownerDocument.defaultView.Event('change',{bubbles:true}));
        }
        var jq=core.campus.ownerDocument.defaultView.jQuery;
        if (before) before.dependent=!!(core.campus.onchange || building.disabled || jq && jq.active);
        return before;
    }
    function buildingStatus(before,building,form) {
        if (!before) return '';
        var elapsed=Date.now()-before.time, jq=form.ownerDocument.defaultView.jQuery;
        var busy=building && building.disabled || jq && jq.active;
        if (busy) before.dependent=true;
        if (elapsed<300 || busy && elapsed<8000) return 'loading';
        // A school may leave the previous building options in place while the request runs.
        var unchanged=before.select===building && before.first===building.options[0] &&
            before.choices===JSON.stringify(namedChoices(building));
        if (before.dependent && (busy || unchanged || !namedChoices(building).length))
            return elapsed<8000 ? 'loading' : 'unconfirmed';
        return unchanged && elapsed<2500 ? 'loading' : '';
    }
    // Some old JSPs have only named buildings and automatically select the first one.
    // An explicit empty option makes their own form serialize the optional field as blank.
    function buildingValue(select,value) {
        if (value) return value;
        var options=optionsOf(select);
        var all=options.find(function(o){return /全部|不限|所有/.test(o.textContent);}) ||
            options.find(function(o){return !o.value;}) || options.find(placeholder);
        if (all) return all.value;
        select.add(new select.ownerDocument.defaultView.Option('全部教学楼',''));
        return '';
    }
    function readWeeks(form) {
        var fields=roomFields(form), start=fields.week, end=fields.end;
        if (!start) return [];
        var lo=optionWeek(start.options[start.selectedIndex]), hi=end ? optionWeek(end.options[end.selectedIndex]) : lo;
        if (!lo || !hi) return [];
        return unique(optionsOf(start).map(optionWeek).filter(function(n){return n>=lo && n<=hi;}));
    }

    var lastWeeks = [], preferredDay = 0, submission = 0, previousResults = [];
    function remember(form) {
        lastWeeks = readWeeks(form);
        var first = field(form, ['xq1','xqStart','xq'],roomFields(form).campus), last = field(form, ['xq2','xqEnd']);
        var opt = first && first.options[first.selectedIndex];
        preferredDay = opt && (dayNumber(opt.textContent) || (/^[1-7]$/.test(opt.value) ? Number(opt.value) : 0));
        if (first && last && last.value && last.value !== first.value) preferredDay = 0;
        submission++;
        previousResults = (matches || []).map(function (m) {
            return {table:m.table, version:m.table.__lvlvGridVersion || 0};
        });
        queue();
    }
    function capture(event) {
        var node = event.target, form = node.form || (node.closest && node.closest('form'));
        if (!form || !isRoomForm(form)) return;
        if (event.type === 'click') {
            var control = node.closest('button,input,a,[onclick]');
            if (!control || compact(control.value || control.textContent).indexOf('查询') < 0) return;
        }
        remember(form);
    }
    var roomName = /^(?:教室(?:名称|名|号|编号)?|场地(?:名称|名|编号)|上课地点)$/;
    var statusName = /^(?:状态|使用状态|占用状态|教室状态|排课状态)$/;
    var freeWord = /^(?:空闲(?:教室)?|完全空闲|未占用|可用|未排课)$/;
    var busyWord = /^(?:已?占用|已排课|被?借用|上课中|使用中|停用|维修|封闭)$/;
    function rowCells(row) { return array(row.cells); }

    function shape(table, roomContext) {
        var rows = table.rows;
        for (var r = 0; r < Math.min(6, rows.length); r++) {
            var cells = rowCells(rows[r]), labels = cells.map(function (c) { return compact(c.textContent); });
            var name = labels.findIndex(function (label) { return roomName.test(label); });
            var status = labels.findIndex(function (label) { return statusName.test(label); });
            if (name >= 0 && status >= 0 && cells.every(function (c) { return c.colSpan === 1; })) {
                var locations = ['校区','所在教学楼','教学楼','楼栋','功能区'].map(function (v) { return labels.indexOf(v); })
                    .filter(function (i) { return i >= 0; });
                return {start:r+1, name:name, locations:locations, status:status, slots:[]};
            }
            if (!roomContext && name < 0) continue;
            if (name < 0) {
                var marked = table.id === 'dataList' && labels[0] === '星期' && table.querySelector('[name=jsmc_mh], [jsbh]');
                var oldGrid = table.id === 'kbtable' && resultPath.test(table.ownerDocument.location.pathname) &&
                    /^(?:|星期|星期[\\/／]教室|教室[\\/／]星期)$/.test(labels[0]);
                if (!marked && !oldGrid) continue;
            }
            if (name < 0) name = 0;
            if (cells.length < 2 || cells.slice(0,name+1).some(function(c){return c.colSpan!==1;})) continue;
            var sub = rows[r+1] ? rowCells(rows[r+1]) : [];
            var prefix = cells.slice(0,name+1);
            var offset = prefix.filter(function(c){return c.rowSpan>1;}).length;
            var leafHeaders = sub.slice(prefix.length-offset);
            var hasPeriods = leafHeaders.length > 0 && leafHeaders.every(function (c) {return !!periodLabel(c);});
            var column = 0, slots = [];
            cells.forEach(function(header) {
                var text = compact(header.textContent);
                var parts = /^((?:星期|周|礼拜)[一二三四五六日天1-7])(?:[（(]?((?:第)?[\d,，、\-－–~至]+(?:小节|节))[）)]?)?$/.exec(text);
                var day = parts ? dayNumber(parts[1]) : 0;
                for (var i=0; i<header.colSpan; i++, column++) {
                    if (!day) continue;
                    var leaf = sub[column-offset];
                    var period = hasPeriods && leaf && leaf.colSpan===1 ? periodLabel(leaf)
                        : parts[2] ? periodText(parts[2]) : '教务查询所选节次';
                    if (period) slots.push({day:day, period:period, col:column});
                }
            });
            if (slots.length && (!hasPeriods || sub.length + offset === column)) {
                var place = labels.map(function(label, index) {
                    return /^(校区|教学楼|所在教学楼|楼栋)$/.test(label) ? index : -1;
                }).filter(function(index){return index>=0;});
                return {start:r+(hasPeriods ? 2 : 1), width:column, name:name, locations:place, status:-1, slots:slots};
            }
        }
        return null;
    }

    // Each separately displayed booking must be outside the chosen week before the cell is free.
    function bookingState(text, week) {
        var s = compact(text).replace(/[（]/g,'(').replace(/[）]/g,')').replace(/[,，、]/g,',').replace(/[～~至—–]/g,'-');
        if (/加载中|待定|状态未知|另行通知|数据异常/.test(s)) return 'unknown';
        if (!week) return 'busy';
        var ranges = /(?:第)?(\d{1,2}(?:-\d{1,2})?(?:,\d{1,2}(?:-\d{1,2})?)*)(单|双)?周(?:\((单|双)(?:周)?\)|(单|双)周)?/g;
        var found = false, active = false, invalid = false;
        var rest = s.replace(ranges, function (all, parts, p1, p2, p3, index) {
            if (index && /[0-9,\-]/.test(s.charAt(index-1))) invalid = true;
            found = true;
            var parity = p1 || p2 || p3;
            parts.split(',').forEach(function (part) {
                var bounds = part.split('-').map(Number), a = bounds[0], b = bounds[bounds.length-1];
                if (a < 1 || b < a || b > 60) invalid = true;
                if (a <= week && week <= b && (!parity || a === b || (week%2 === (parity === '单' ? 1 : 0)))) active = true;
            });
            return '';
        });
        if (!found || invalid || /周|待定|另有|另行/.test(rest)) return 'unknown';
        return active ? 'busy' : 'free';
    }
    function gridState(cell, week) {
        if (!cell || cell.colSpan !== 1 || cell.rowSpan !== 1) return 'unknown';
        var lines = [], line = '';
        function flush() { if (line.trim()) lines.push(line.trim()); line = ''; }
        function collect(node) {
            if (node.nodeType === 3) { line += node.nodeValue; return; }
            if (node.nodeType !== 1) return;
            var block = /^(BR|HR|DIV|P|LI)$/.test(node.tagName);
            if (block) flush();
            array(node.childNodes).forEach(collect);
            if (block) flush();
        }
        array(cell.childNodes).forEach(collect); flush();
        if (!lines.length) {
            // Do not interpret icons, inaccessible booking details, or loading controls as whitespace.
            if (cell.hasAttribute('bgcolor') || cell.style.backgroundColor || cell.style.backgroundImage ||
                /(?:busy|occupied|booked|disabled)/i.test(cell.className) ||
                cell.querySelector('img,a,svg,button,input,iframe,canvas,i,[title]:not([title=""]),[aria-label]:not([aria-label=""])') ||
                compact(cell.title || cell.getAttribute('aria-label'))) return 'unknown';
            return 'free';
        }
        var states = lines.map(function (line) {
            if (freeWord.test(compact(line))) return 'free';
            if (busyWord.test(compact(line))) return 'busy';
            return bookingState(line, week);
        });
        return combine(states);
    }
    function combine(states) {
        if (states.indexOf('busy') >= 0) return 'busy';
        return !states.length || states.indexOf('unknown') >= 0 ? 'unknown' : 'free';
    }
    function node(doc, tag, text) {
        var el = doc.createElement(tag);
        if (text) el.textContent = text;
        return el;
    }
    function select(panel, title, key, options, selected) {
        var doc = panel.ownerDocument, label = node(doc, 'label', title), sel = node(doc, 'select');
        sel.setAttribute('aria-label', title);
        options.forEach(function (item) {
            var opt = node(doc, 'option', item.label);
            opt.value = String(item.value); sel.appendChild(opt);
        });
        if (options.some(function (o) {return String(o.value) === String(selected);})) sel.value = String(selected);
        label.appendChild(sel); panel.appendChild(label);
        sel.onchange = function () { panel.__choice[key] = sel.value; panel.__render(); };
        return sel.value;
    }
    function unique(values) { return values.filter(function (v,i) { return values.indexOf(v) === i; }); }
    function collectRows(table, model, columns, week, byRoom) {
        array(table.rows).slice(model.start).forEach(function (row) {
            var cells = rowCells(row), room = cells[model.name];
            if (!room || room.colSpan !== 1 || room.rowSpan !== 1) return;
            var name = room.textContent.trim();
            if (!name || name.length > 100 || /^(?:暂无|无记录|未查询到|合计|小计|共\d+条)/.test(compact(name))) return;
            var place = model.locations.map(function (i) {return cells[i] ? cells[i].textContent.trim() : '';}).filter(Boolean);
            name = place.concat(name).join(' · ');
            function aligned(i) {
                return (!model.width || cells.length===model.width) && cells[i] && cells.slice(0,i+1).every(function(c){return c.colSpan === 1 && c.rowSpan === 1;});
            }
            var check = 'unknown';
            if (model.status >= 0 && aligned(model.status)) {
                var value = compact(cells[model.status].textContent);
                check = freeWord.test(value) ? 'free' : busyWord.test(value) ? 'busy' : 'unknown';
            } else if (model.status < 0) {
                check = combine(columns.map(function (i) { return aligned(i) ? gridState(cells[i],week) : 'unknown'; }));
            }
            byRoom.set(name, byRoom.has(name) ? combine([byRoom.get(name),check]) : check);
        });
    }
    function collectRoomPeriods(table, model, daySlots, week, byRoomPeriods) {
        if (model.status >= 0 || !daySlots.length) return;
        var periodNames = unique(daySlots.map(function(s){return s.period;}));
        var colsByPeriod = periodNames.map(function(p) {
            return {period:p, cols:daySlots.filter(function(s){return s.period===p;}).map(function(s){return s.col;})};
        });
        array(table.rows).slice(model.start).forEach(function(row) {
            var cells = rowCells(row), room = cells[model.name];
            if (!room || room.colSpan !== 1 || room.rowSpan !== 1) return;
            var name = room.textContent.trim();
            if (!name || name.length > 100 || /^(?:暂无|无记录|未查询到|合计|小计|共\d+条)/.test(compact(name))) return;
            var place = model.locations.map(function(i){return cells[i] ? cells[i].textContent.trim() : '';}).filter(Boolean);
            name = place.concat(name).join(' · ');
            function aligned(i) {
                return (!model.width || cells.length===model.width) && cells[i] && cells.slice(0,i+1).every(function(c){return c.colSpan === 1 && c.rowSpan === 1;});
            }
            var map = byRoomPeriods.get(name);
            if (!map) { map = new Map(); byRoomPeriods.set(name, map); }
            colsByPeriod.forEach(function(entry) {
                var st = combine(entry.cols.map(function(i){return aligned(i) ? gridState(cells[i], week) : 'unknown';}));
                map.set(entry.period, map.has(entry.period) ? combine([map.get(entry.period), st]) : st);
            });
        });
    }
    function paint(panel, table, model, weeks) {
        var doc = panel.ownerDocument, state = panel.__choice;
        if (state.submission !== submission) { state = panel.__choice = {submission:submission,day:preferredDay}; }
        panel.textContent = '';
        panel.appendChild(node(doc,'h3','空教室查询结果'));
        var columns = [], week = 0;
        if (model.status < 0) {
            var weeksList = weeks.map(function (n) {return {value:n,label:'第' + n + '周'};});
            if (weeksList.length !== 1) weeksList.unshift({value:0,label:'教务查询所选周次'});
            week = Number(select(panel,'周次','week',weeksList,state.week));
            var day = Number(select(panel,'星期','day',unique(model.slots.map(function (s) {return s.day;}))
                .map(function (d) {return {value:d,label:days[d]};}),state.day));
            var slots = model.slots.filter(function (s) {return s.day === day;});
            var periods = unique(slots.map(function (s) {return s.period;}));
            var options = periods.map(function (v) {return {value:v,label:v};});
            if (periods.length > 1) options.unshift({value:'all',label:'当日全部所列节次'});
            var period = select(panel,'节次','period',options,state.period);
            var matching = slots.filter(function (s) {return period === 'all' || s.period === period;});
            columns = matching.map(function (s) {return s.col;});
        }
        var byRoom = new Map();
        collectRows(table,model,columns,week,byRoom);
        var groups = {free:[],busy:[],unknown:[]};
        byRoom.forEach(function (value,name) {groups[value].push(name);});
        if (!byRoom.size) {
            panel.appendChild(node(doc,'p','本页没有可识别的教室记录，请核对教务原表。')); return;
        }
        var show = select(panel,'状态','show',[
            {value:'free',label:'空闲（'+groups.free.length+'）'},
            {value:'busy',label:'有占用（'+groups.busy.length+'）'},
            {value:'unknown',label:'待核实（'+groups.unknown.length+'）'}
        ],state.show);
        var summary = node(doc,'p',show === 'free' ? '本页符合条件的空闲教室' : show === 'busy' ? '本页有占用的教室' : '本页状态或周次待核实的教室');
        summary.setAttribute('aria-live','polite'); panel.appendChild(summary);
        var list = node(doc,'ul');
        groups[show].forEach(function (name) {list.appendChild(node(doc,'li',name));});
        if (!list.children.length) summary.textContent += '：0 间';
        panel.appendChild(list);
    }
    function renderTable(table, model, weeks) {
        var panel = table.__lvlvRoomsPanel, doc = table.ownerDocument;
        if (!panel || !doc.body.contains(panel)) {
            panel = node(doc,'section'); panel.setAttribute('data-lvlv-rooms','');
            panel.__choice = {}; table.__lvlvRoomsPanel = panel; panel.__table = table;
            table.parentNode.insertBefore(panel, table);
        }
        panel.__render = function () { paint(panel,table,model,weeks); };
        panel.__render();
    }
    function scan(doc, inherited) {
        var forms = array(doc.forms).filter(isRoomForm);
        var roomContext = inherited || forms.length > 0 || /教室课表|教室借用|空闲教室/.test(doc.title) ||
            /\/(?:kbxx_classroom[^/]*|jsjy_query2?)\/?$/i.test(doc.location.pathname);
        var result = false;
        if (forms.length && !queryForm) queryForm = forms[0];
        array(doc.querySelectorAll(PANEL)).forEach(function (p) {
            if (!p.__table || !doc.body.contains(p.__table)) p.parentNode.removeChild(p);
        });
        array(doc.querySelectorAll('table')).forEach(function (t) {
            if (!t.getClientRects().length || t.closest(PANEL)) return;
            var model = shape(t, roomContext);
            if (previousResults.some(function (old) {
                return old.table === t && old.version === (t.__lvlvGridVersion || 0);
            })) return;
            if (model && (roomContext || model.status >= 0 || /^(?:教室|教室名称)$/.test(compact(t.rows[0].cells[0].textContent)))) {
                matches.push({table:t, model:model}); result = true;
            } else if (t.__lvlvRoomsPanel && t.__lvlvRoomsPanel.parentNode) {
                t.__lvlvRoomsPanel.parentNode.removeChild(t.__lvlvRoomsPanel);
            }
        });
        return {room:roomContext, stage:result ? 'results' : forms.length ? 'form' : 'waiting'};
    }
    function attach(doc) {
        if (doc.__lvlvRoomWatch) return;
        var css = node(doc,'style');
        css.textContent =
            '[data-lvlv-rooms]{box-sizing:border-box;width:100%;max-width:46rem;margin:6px 0;padding:12px 16px;background:#f5f4f0;color:#111;font:15px/1.5 sans-serif;text-align:left;white-space:normal}' +
            '[data-lvlv-rooms] h3{font:bold 18px/1.5 sans-serif;margin:0 0 6px;color:inherit}[data-lvlv-rooms] label{display:inline-flex;flex-direction:column;min-width:0;max-width:100%;margin:2px 12px 2px 0;font-size:12px}' +
            '[data-lvlv-rooms] select{display:block;box-sizing:border-box;max-width:100%;min-height:48px;font:15px sans-serif;color:inherit;background:transparent;border:1px solid #9a9993}' +
            '[data-lvlv-rooms] p{margin:8px 0 4px;color:inherit}[data-lvlv-rooms] ul{display:flex;flex-wrap:wrap;padding:0;margin:0;list-style:none;max-height:36vh;overflow:auto}[data-lvlv-rooms] li{padding:4px 10px 4px 0;margin:0;font-variant-numeric:tabular-nums}' +
            '@media(prefers-color-scheme:dark){[data-lvlv-rooms]{background:#171716;color:#eceae4}[data-lvlv-rooms] select{background:#171716}}';
        (doc.head || doc.body).appendChild(css);
        doc.addEventListener('click',capture,true); doc.addEventListener('submit',capture,true);
        doc.addEventListener('load',function (e) {
            if (/^(I?FRAME)$/.test(e.target.tagName || '')) queue();
        },true);
        doc.__lvlvRoomWatch = new MutationObserver(function (changes) {
            var changed = false;
            changes.forEach(function (change) {
                var el = change.target.nodeType === 1 ? change.target : change.target.parentElement;
                if (!el || el.closest(PANEL)) return;
                if (change.type === 'childList' && !array(change.addedNodes).concat(array(change.removedNodes)).some(function (n) {
                    return !(n.nodeType === 1 && n.matches(PANEL));
                })) return;
                var table = el.closest('table');
                if (table && change.type !== 'attributes') table.__lvlvGridVersion = (table.__lvlvGridVersion || 0) + 1;
                changed = true;
            });
            if (changed) queue();
        });
        doc.__lvlvRoomWatch.observe(doc.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['hidden','style','class']});
    }
    var fieldNames = {
        xnxqh:'学年学期',xnxq01id:'学年学期',kbjcmsid:'时间模式', xqid:'校区',xqbh:'校区',
        jxlid:'所在教学楼',jxlbh:'所在教学楼', gnqbh:'功能区', jsbh:'教室',jsmc:'教室',
        zc:'起始周次', zc1:'起始周次', zc2:'结束周次', xq:'起始星期', xq1:'起始星期', xq2:'结束星期', jc:'起始节次', jc1:'起始节次', jc2:'结束节次'
    };
    function fieldLabel(control) {
        var title = control.getAttribute('aria-label') || control.title;
        if (!title && control.labels && control.labels.length) {
            var label = control.labels[0].cloneNode(true);
            array(label.querySelectorAll('select,input')).forEach(function (el) {el.remove();});
            title = label.textContent;
        }
        var row = control.closest('.search-line,.form-group');
        var caption = row && row.querySelector('.search-line-label,.control-label');
        var td = control.closest('td'), previous = td && td.previousElementSibling;
        if (!title && caption) title = caption.textContent;
        if (!title && previous && !previous.querySelector('input,select')) title = previous.textContent;
        var name = control.name || control.id;
        // Several classic templates use "xq" for either campus or weekday.
        if (title && /校区|教学楼|楼栋/.test(title)) return title.trim();
        if (/^(zc|xq|jc)(1|2)?$/.test(name) && fieldNames[name]) return fieldNames[name];
        title = (title || '').trim().replace(/[：:]$/, '');
        return title && title.length < 20 ? title : fieldNames[name];
    }
    function mobileReader(host) {
        var mount = node(host,'div');
        if (!mount.attachShadow) return null;
        var root = mount.attachShadow({mode:'open'});
        mount.setAttribute('data-lvlv-room-reader','');
        host.body.appendChild(mount);
        var css = node(host,'style');
        css.textContent =
            ':host{all:initial;--paper:#f5f4f0;--ink:#111;--card:#fbfaf7;--line:#d9d7cf;--sub:#55554f;color-scheme:light}' +
            '@media(prefers-color-scheme:dark){:host{--paper:#0e0e0d;--ink:#eceae4;--card:#171716;--line:#363630;--sub:#b3b1aa;color-scheme:dark}}' +
            '*{box-sizing:border-box} [hidden]{display:none!important} main,.reopen{position:fixed;z-index:2147483646;background:var(--paper);color:var(--ink);font:16px/1.5 sans-serif;text-align:left}' +
            'main{top:0;left:0;right:0;bottom:0;overflow:auto;padding:16px} article{max-width:48rem;margin:0 auto} header{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap} h1{font-size:24px;margin:0} h3{font-size:18px;margin:12px 0 6px}' +
            'button,input,select{max-width:100%;min-height:44px;border:1px solid var(--line);border-radius:4px;color:var(--ink);background:var(--card);padding:6px 10px;font:inherit} button{cursor:pointer} .run{color:var(--paper);background:var(--ink);margin-top:12px;min-width:92px}.reopen{right:12px;bottom:12px}' +
            '.fields{display:flex;flex-wrap:wrap;margin:8px -5px 0}label{display:inline-flex;flex-direction:column;font:13px/1.5 sans-serif;color:var(--sub);vertical-align:top;padding:4px 5px}label select,label input{font:16px/1.4 sans-serif}.fields label{width:50%;min-width:8rem}.fields select,.fields input{width:100%}' +
            'section{border-top:1px solid var(--line);margin-top:16px}p{color:var(--sub);margin:8px 0}ul{display:flex;flex-wrap:wrap;list-style:none;padding:0;margin:4px 0} li{font-variant-numeric:tabular-nums;padding:5px 12px 5px 0}';
        var main = node(host,'main'), page = node(host,'article'), header = node(host,'header');
        var original = node(host,'button','教务原页'), reopen = node(host,'button','查空教室');
        var inputs = node(host,'div'), term = node(host,'p'), run = node(host,'button','查询教室');
        var feedback = node(host,'p'), results = node(host,'div');
        inputs.className = 'fields'; run.className = 'run'; reopen.className = 'reopen';
        original.type = run.type = reopen.type = 'button';
        feedback.setAttribute('aria-live','polite');
        header.appendChild(node(host,'h1','查空教室')); header.appendChild(original);
        [header,term,inputs,run,feedback,results].forEach(function (e) {page.appendChild(e);});
        main.appendChild(page); [css,main,reopen].forEach(function (e) {root.appendChild(e);});
        var meta, oldViewport, inserted, activeForm, submit, sourceControls=[], sources=[], lastFields='';
        var selected={}, buildingBefore;
        function toggle(on) {
            main.hidden = !on; reopen.hidden = on;
            if (!on && meta) {
                if (inserted) meta.remove();
                else if (oldViewport === null) meta.removeAttribute('content');
                else meta.content = oldViewport;
                meta = null;
            } else if (on && host.head && !meta) {
                meta = host.querySelector('meta[name=viewport]'); inserted = !meta;
                if (!meta) {meta = node(host,'meta'); meta.name='viewport'; host.head.appendChild(meta);}
                oldViewport = meta.getAttribute('content'); meta.content='width=device-width, initial-scale=1';
            }
        }
        original.onclick=function(){toggle(false);reopen.focus();};
        reopen.onclick=function(){toggle(true);original.focus();};
        toggle(true);
        function emit(control,type) {
            control.dispatchEvent(new control.ownerDocument.defaultView.Event(type,{bubbles:true}));
        }
        function showFields(form) {
            if (form!==activeForm) {selected={}; buildingBefore=null;}
            var core=form ? roomFields(form) : {};
            var controls=[core.campus,core.week,core.building].filter(Boolean);
            if (form) controls=controls.concat(formControls(form).filter(function(c){return controls.indexOf(c)<0 && c!==core.end;}));
            var changed = form !== activeForm || controls.length !== sourceControls.length ||
                controls.some(function(c,i){return c !== sourceControls[i];});
            activeForm=form; submit=form && queryButton(form);
            var canQuery = !!(submit && controls.length);
            var choices=roomChoices(core,selected), status=form && buildingStatus(buildingBefore,core.building,form);
            var loading=status==='loading';
            if (loading) queue();
            if (!status) buildingBefore=null;
            if (status==='unconfirmed') {choices.building='';choices.buildings=[];}
            run.hidden=!canQuery; inputs.hidden=!canQuery;
            run.disabled=!(submit && !submit.disabled && choices.week && choices.campus && !loading);
            var fixedTerm=form && form.querySelector('input[type=hidden][name=xnxqh],input[type=hidden][name=xnxq01id]');
            term.textContent=fixedTerm && /^\d{4}-\d{4}-\d+$/.test(fixedTerm.value) ? '学年学期 '+fixedTerm.value : '';
            term.hidden=!term.textContent;
            var definitions=controls.map(function(c) {
                if (c===core.campus) return {role:'campus',name:'校区',options:[{value:'',label:'请选择校区'}].concat(choices.campuses)};
                if (c===core.week) return {role:'week',name:'周次',options:[{value:'',label:'请选择周次'}].concat(choices.weeks)};
                if (c===core.building) return {role:'building',name:'所在教学楼',options:[{value:'',label:'全部教学楼'}].concat(loading ? [] : choices.buildings)};
                return {name:fieldLabel(c),options:c.options && optionsOf(c).map(function(o){return {value:o.value,label:o.textContent};})};
            });
            var snapshot=JSON.stringify(definitions);
            if (changed || snapshot !== lastFields) {
                lastFields=snapshot; sourceControls=controls; inputs.textContent='';
                controls.forEach(function(c,i) {
                    var d=definitions[i], label=node(host,'label',d.name), el=node(host,d.options?'select':'input');
                    el.setAttribute('aria-label',d.name);
                    if (d.options) d.options.forEach(function(o){
                        var opt=node(host,'option',o.label); opt.value=o.value;el.appendChild(opt);
                    });
                    else {el.type=c.type;el.placeholder=c.placeholder || ''; if (c.maxLength>=0) el.maxLength=c.maxLength;}
                    label.appendChild(el);inputs.appendChild(label);
                    if (!c.options) el.oninput=function(){c.value=el.value;emit(c,'input');};
                    el.onchange=function(){
                        if (d.role) selected[d.role]=el.value;
                        if (d.role==='campus') {
                            selected.building='';
                            if (el.value && (c.value!==el.value || core.building && !namedChoices(core.building).length))
                                buildingBefore=changeCampus(core,el.value);
                        } else if (!d.role) setValue(c,el.value);
                        showFields(form);
                    };
                });
            }
            controls.forEach(function(c,i){
                var el=inputs.children[i].lastChild, role=definitions[i].role;
                el.disabled=c.disabled || (role==='building' && (!choices.campus || loading));
                el.value=role ? choices[role] : c.value;
            });
        }
        run.onclick=function(){
            if (!submit || !submit.isConnected || submit.disabled || run.disabled) return;
            var core=roomFields(activeForm), values=roomChoices(core,selected);
            var status=buildingStatus(buildingBefore,core.building,activeForm);
            if (!values.campus || !values.week || values.building!==(selected.building || '') ||
                status==='loading' || status==='unconfirmed' && selected.building) {showFields(activeForm);return;}
            setValue(core.week,weekOption(core.week,values.week).value);
            if (core.end) setValue(core.end,weekOption(core.end,values.week).value);
            if (core.building && !core.building.disabled) setValue(core.building,buildingValue(core.building,values.building));
            remember(activeForm);
            feedback.textContent='已提交查询。若未显示教室，请到「教务原页」查看学校提示。';
            submitQuery(activeForm,submit);
        };
        return {host:host,mount:mount,dispose:function(){toggle(false);mount.remove();},
            update:function(form,models){
                showFields(form);
                if (models.length) feedback.textContent='';
                else if (!submit && form) feedback.textContent='请点「教务原页」使用学校提供的查询条件。';
                // Keep the student's selected result filters when the school updates this table in place.
                models.forEach(function(m,i){
                    var data=sources[i];
                    if (!data || data.table!==m.table) {
                        if(data) data.panel.remove();
                        var panel=node(host,'section');panel.__choice={};
                        data={table:m.table,panel:panel}; sources[i]=data;results.appendChild(panel);
                    }
                    var panel=data.panel;
                    var version = m.table.__lvlvGridVersion || 0;
                    if (data.version !== version || data.submission !== submission) {
                        data.version=version; data.submission=submission;
                        panel.__render=function(){paint(panel,m.table,m.model,lastWeeks);};
                        panel.__render();
                    }
                });
                while(sources.length>models.length) sources.pop().panel.remove();
            }};
    }

    function formControls(form) {
        return array(form.elements).filter(function(c) {
            var name = c.name || c.id, label = fieldLabel(c) || '';
            return name && !c.disabled && !c.multiple && c.getClientRects().length &&
                /校区|学期|学年|教学区|教学楼|教室|功能区|人数|院系|星期|节次|周次|时间模式|开课学院/.test(label) &&
                (c.tagName === 'SELECT' || c.tagName === 'INPUT' && /^(text|number|search)$/.test(c.type));
        });
    }
    function describeControls(controls) {
        return controls.map(function(c) {
            var item = {name:c.name || c.id,label:fieldLabel(c),value:c.value};
            if (c.options) item.options = array(c.options).filter(function(o) {return !o.disabled;})
                .map(function(o) {return {value:o.value,label:o.textContent.trim()};});
            return item;
        });
    }
    function selectedSummary(fields) {
        return fields.map(function(f) {
            var option = (f.options || []).find(function(o) { return o.value === f.value; });
            if (!f.value && (!option || !/全部|不限/.test(option.label))) return '';
            return f.label + ' ' + (option ? option.label : f.value);
        }).filter(Boolean).join(' · ');
    }
    function roomSync(request) {
        var sync = roomSync.state || (roomSync.state={sent:false,stale:[],empty:[],observers:[]});
        // Android records the submission and its conditions before the target can replace this document.
        if (sync.pending && request.submitted && !request.capture) {
            var pending = sync.pending;
            sync.pending = null;
            if (!pending.button.isConnected || !pending.form.isConnected) return {stage:'error',message:'教务查询表单已变更，请重新同步。'};
            submitQuery(pending.form,pending.button);
            return {stage:'loading'};
        }
        if (sync.pending && !request.capture) return {stage:'submitted',data:sync.context};
        var form=null, models=[], empty=[];
        function visit(win, inherited) {
            var doc;
            try {doc=win.document; if (!school(doc) || win !== window && (!win.frameElement ||
                !win.frameElement.getClientRects().length || win.frameElement.ownerDocument.defaultView.getComputedStyle(win.frameElement).visibility === 'hidden')) return;}
            catch (e) {return;}
            if (academic(doc)) {
                var current = array(doc.forms).find(isRoomForm);
                if (current && !form) form=current;
                var room = inherited || !!current || /教室课表|教室查询|教室借用|空闲教室/.test(doc.title) ||
                    /\/(?:kbxx_classroom[^/]*|jsjy_query2?)(?:[;/]|$)/i.test(doc.location.pathname);
                array(doc.querySelectorAll('table')).forEach(function(t) {
                    if (!t.getClientRects().length) return;
                    var model = shape(t,room);
                    if (model) models.push({table:t,model:model});
                });
                if (room) array(doc.querySelectorAll('td,p,.no-data,.empty')).forEach(function(e) {
                    if (request.capture && (current && current.contains(e) || !resultPath.test(doc.location.pathname) &&
                        !/查询结果|课表结果/.test(doc.title) && !e.closest('table#kbtable,table#dataList'))) return;
                    if (e.getClientRects().length && /^(?:暂无(?:教室|数据|记录)|没有符合条件的(?:教室|记录)|未查询到(?:符合条件的)?(?:教室|数据|记录)|无教室记录)[。！!]?$/i.test(compact(e.textContent)))
                        empty.push(e);
                });
                inherited=room;
            }
            for (var i=0; i<win.frames.length; i++) visit(win.frames[i],inherited);
        }
        visit(window,false);
        var initial = {fields:[],choices:{weeks:[],days:[],periods:[]},query:{}};
        var context = request.capture ? initial : sync.context || request.context || initial;
        var want = request.query || {}, chosen = request.fields || {};
        function choose(message) {return {stage:'choose',entryPage:formEntry(form),message:message,data:context};}
        if (!request.capture && !request.submitted && !sync.sent && form) {
            var core = roomFields(form), wanted = request.roomConditions || {};
            var controls = formControls(form);
            var choices = roomChoices(core,wanted);
            context = {roomConditions:choices,fields:describeControls(controls),choices:{weeks:[],days:[],periods:[]},query:{}};
            if (choices.campus && (choices.campus !== core.campus.value ||
                core.building && !choices.buildings.length && sync.preparedCampus!==choices.campus)) {
                sync.preparedCampus=choices.campus;
                sync.buildingBefore=changeCampus(core,choices.campus);
                return {stage:'loading'};
            }
            var buildingState=buildingStatus(sync.buildingBefore,core.building,form);
            if (buildingState==='loading') return {stage:'loading'};
            if (!buildingState) sync.buildingBefore=null;
            if (buildingState==='unconfirmed') {choices.building='';choices.buildings=[];}
            if (!core.campus || !core.week) return {stage:'error',message:'未能识别学校表单的校区和周次，请打开教务原页核对。'};
            if (request.roomAction!=='query' || !choices.campus || !choices.week)
                return choose('请选择校区、教学楼、周次和星期后查询。');
            if (wanted.requireBuilding && core.building && !choices.building)
                return choose('请选择所在教学楼。');
            if (wanted.building && !choices.building)
                return choose('该教学楼不在当前校区的选项中，请重新选择或选全部教学楼。');
            var weekControls=core.end ? [core.week,core.end] : [core.week];
            for (var i=0; i<weekControls.length; i++) {
                var weekControl=weekControls[i], weekValue=weekOption(weekControl,choices.week);
                if (!weekValue) return choose('学校的周次选项已变化，请重新选择。');
                if (setValue(weekControl,weekValue.value)) return {stage:'loading'};
            }
            if (core.building && !core.building.disabled && setValue(core.building,buildingValue(core.building,choices.building)))
                return {stage:'loading'};
            if (choices.day && core.dayStart && !core.dayStart.disabled) {
                var dayControls = core.dayEnd ? [core.dayStart, core.dayEnd] : [core.dayStart];
                for (var di=0; di<dayControls.length; di++) {
                    var dOpt = dayOption(dayControls[di], choices.day);
                    if (dOpt && setValue(dayControls[di], dOpt.value)) return {stage:'loading'};
                }
            }
            if (core.periodStart && !core.periodStart.disabled && Object.prototype.hasOwnProperty.call(wanted, 'period')) {
                var targetPeriod = choices.period || emptyOptionValue(core.periodStart);
                if (setValue(core.periodStart, targetPeriod)) return {stage:'loading'};
                if (core.periodEnd && !core.periodEnd.disabled) {
                    var targetPeriodEnd = choices.period && optionsOf(core.periodEnd).some(function(o){return o.value===choices.period;})
                        ? choices.period : emptyOptionValue(core.periodEnd);
                    if (setValue(core.periodEnd, targetPeriodEnd)) return {stage:'loading'};
                }
            }
            if (core.roomInput && !core.roomInput.disabled && Object.prototype.hasOwnProperty.call(wanted, 'room')) {
                if (core.roomInput.value !== choices.room) {
                    core.roomInput.value = choices.room;
                    ['input','change'].forEach(function(type){core.roomInput.dispatchEvent(new core.roomInput.ownerDocument.defaultView.Event(type,{bubbles:true}));});
                    return {stage:'loading'};
                }
            }
            // Other filters (for example a room name) retain the school's names and native change handlers.
            for (var f=0; f<controls.length; f++) {
                var c=controls[f], key=c.name || c.id;
                if (c===core.campus || c===core.week || c===core.end || c===core.building ||
                    c===core.dayStart || c===core.dayEnd || c===core.periodStart || c===core.periodEnd ||
                    (c===core.roomInput && Object.prototype.hasOwnProperty.call(wanted, 'room')) ||
                    !Object.prototype.hasOwnProperty.call(chosen,key) || c.value === String(chosen[key])) continue;
                var target=String(chosen[key]);
                if (c.options && !optionsOf(c).some(function(o) {return o.value===target;}))
                    return choose('教务已更新「'+fieldLabel(c)+'」的选项，请重新选择。');
                setValue(c,target);
                return {stage:'loading'};
            }
            var needed=controls.find(function(c){return c.validity && !c.validity.valid;});
            if (needed) return choose('请选择或填写「'+fieldLabel(needed)+'」。');
            context.fields=describeControls(controls);
            context.choices.weeks=[Number(choices.week)];
            context.summary=selectedSummary(context.fields);
            var send=queryButton(form);
            if (!send) return {stage:'error',message:'教务页面没有可识别的教室查询按钮。'};
            if (send.disabled) return choose('请补全教务查询条件。');
            var rawAction = send.getAttribute('formaction') || form.getAttribute('action') || '';
            // Old Qiangzhi buttons can set the real form action in their click handler.
            if (send !== form && /^javascript:\s*(?:void\s*\(0\)\s*)?;?$/i.test(rawAction.trim())) rawAction = '';
            var action=new URL(rawAction || form.ownerDocument.URL,form.ownerDocument.baseURI);
            if (action.protocol !== 'https:' || !/(^|\.)sdjtu\.edu\.cn$/i.test(action.hostname) || action.username || action.password)
                return {stage:'error',message:'教室查询地址不属于山东交通学院统一门户。'};
            var first=field(form,['xq1','xqStart','xq'],core.campus), option=first && first.options[first.selectedIndex];
            var selectedDayNum = choices.day ? Number(choices.day) : (option ? dayNumber(option.textContent) || Number(option.value) || 0 : 0);
            context.query={week:0,day:selectedDayNum,period:'all'};
            sync.context=context; sync.sent=true;
            sync.empty=empty.slice();
            models.forEach(function(m){
                sync.stale.push(m.table);
                var observer=new MutationObserver(function(changes){
                    if (!changes.some(function(change) {
                        var el=change.target.nodeType===1 ? change.target : change.target.parentElement;
                        return el && (el.closest('td,tbody') || change.type==='childList' && el===m.table);
                    })) return;
                    sync.stale=sync.stale.filter(function(t){return t!==m.table;});
                    observer.disconnect();
                });
                observer.observe(m.table,{childList:true,subtree:true,characterData:true});
                sync.observers.push(observer);
            });
            sync.pending={form:form,button:send};
            return {stage:'submitted',data:context,entryPage:formEntry(form)};
        }
        if (request.capture && form && !models.length && !empty.length)
            return {stage:'form',entryPage:formEntry(form)};
        if (!request.capture && !sync.sent && !request.submitted) return {stage:'waiting'};
        models=models.filter(function(m){return sync.stale.indexOf(m.table)<0;});
        var confirmedEmpty=empty.some(function(e){return sync.empty.indexOf(e)<0;});
        if (!models.length && !confirmedEmpty) return {stage:'loading',entryPage:formEntry(form)};

        var weeks=context.choices && context.choices.weeks || [];
        var slots=[].concat.apply([], models.map(function(m){return m.model.slots;}));
        var dayChoices=unique(slots.map(function(slot){return slot.day;})).sort();
        var suggested=Number(want.day) || Number(context.query.day) || (new Date().getDay() || 7);
        var day=dayChoices.indexOf(suggested)>=0 ? suggested : dayChoices[0] || 0;
        var periods=unique(slots.filter(function(s){return s.day===day;}).map(function(s){return s.period;}));
        var period=periods.indexOf(want.period)>=0 ? want.period : 'all';
        var week=weeks.length===1 ? weeks[0] : 0;
        context.query={week:week,day:day,period:period};
        context.choices={weeks:weeks,days:dayChoices,periods:periods};
        if (slots.length && (Number(want.day)>0 && Number(want.day)!==day ||
            want.period && want.period!=='all' && want.period!==period))
            return choose('教务返回的时段已变化，请重新选择。');
        var byRoom=new Map(), byRoomPeriods=new Map();
        models.forEach(function(m){
            var daySlots=m.model.slots.filter(function(s){return s.day===day;});
            var matching=daySlots.filter(function(s){return period==='all' || s.period===period;});
            var cols=matching.map(function(s){return s.col;});
            collectRows(m.table,m.model,cols,week,byRoom);
            collectRoomPeriods(m.table,m.model,daySlots,week,byRoomPeriods);
        });
        if (!byRoom.size && !confirmedEmpty) return {stage:'loading'};
        var rooms=[];
        byRoom.forEach(function(state,name){
            var item = {name:name, state:state};
            var pMap = byRoomPeriods.get(name);
            if (pMap && pMap.size) {
                var list = [];
                pMap.forEach(function(st, p){ list.push({period:p, state:st}); });
                item.periods = list;
            }
            rooms.push(item);
        });
        sync.observers.forEach(function(observer){observer.disconnect();});
        var summary = request.capture ? '教务页当前显示的结果' : context.summary || '';
        if (day) {
            var selection = [week ? '第' + week + '周' : '', days[day], period === 'all' ? '全部所列节次' : period];
            summary += (summary ? '；' : '') + '结果时段 ' + selection.filter(Boolean).join(' · ');
        }
        return {stage:'done',entryPage:formEntry(form),data:{rooms:rooms,fields:context.fields,choices:context.choices,query:context.query,
            roomConditions:context.roomConditions,summary:summary}};
    }

    var timer, queryForm, matches, mobile;
    function queue() { clearTimeout(timer); timer = setTimeout(inspect,150); }
    function inspect() {
        var stage = 'waiting', count = 0;
        queryForm = null; matches = [];
        function visit(win, inherited) {
            try {
                var doc = win.document;
                if (!school(doc) || ++count > 24) return;
                if (win !== window && win.frameElement && (!win.frameElement.getClientRects().length ||
                    win.frameElement.ownerDocument.defaultView.getComputedStyle(win.frameElement).visibility === 'hidden')) return;
                attach(doc);
                if (academic(doc)) {
                    var info = scan(doc,inherited);
                    inherited = info.room;
                    if (info.stage === 'results' || (info.stage === 'form' && stage === 'waiting')) stage = info.stage;
                }
                for (var i=0; i<win.frames.length; i++) visit(win.frames[i],inherited);
            } catch (e) { /* Cross-origin frames remain in the school's browser flow. */ }
        }
        visit(window,false);
        var sourceDoc = queryForm ? queryForm.ownerDocument : matches.length ? matches[0].table.ownerDocument : null;
        if (sourceDoc) {
            var host = document.body.tagName === 'FRAMESET' ? sourceDoc : document;
            if (mobile && (mobile.host !== host || !mobile.mount.isConnected)) { mobile.dispose(); mobile = null; }
            if (!mobile) mobile = mobileReader(host);
            if (mobile) mobile.update(queryForm, matches);
            else matches.forEach(function (m) {renderTable(m.table,m.model,lastWeeks);});
        } else if (mobile) { mobile.dispose(); mobile = null; }
        return {stage:stage,entryPage:formEntry(queryForm)};
    }
    if (silent) {
        window.__lvlvRoomSync = roomSync;
        return roomSync(request);
    }
    window.__lvlvRooms = {inspect:inspect};
    return inspect();
})
