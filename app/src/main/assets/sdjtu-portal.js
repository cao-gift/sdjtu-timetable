// Find student query links offered by the school, including the classic Qiangzhi frameset.
(function (feature, navigate, resetMenus, reader, request) {
    if (request && request.capture) navigate = false;
    var docs = [], foreignAcademic = null, repairedFrame = false;
    var schoolHost = /(^|\.)sdjtu\.edu\.cn$/i;
    var academicPath = /\/jsxsd(?:\/|$)/i;
    function trusted(url) {
        return url.protocol === "https:" && schoolHost.test(url.hostname) && !url.username && !url.password;
    }
    function plain(s) { return (s || "").replace(/\s+/g, ""); }
    function visible(el) {
        var rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && el.ownerDocument.defaultView.getComputedStyle(el).visibility !== "hidden";
    }
    function repairProxyFrame(frame, doc, child) {
        if (!navigate || request && request.submitted || !child || frame.hasAttribute("srcdoc") || loginPage(doc) ||
            doc.location.hostname !== "wwebvpn.sdjtu.edu.cn") return false;
        var prefix = /^(\/https?\/[a-f\d]{32,})\/jsxsd(?:\/|$)/i.exec(doc.location.pathname);
        var raw = frame.getAttribute("src") || "";
        if (!prefix || !/^\/jsxsd\//i.test(raw) || frame.__lvlvRepairedSrc === raw) return false;
        var url = new URL(raw, doc.location.origin);
        if (url.origin !== doc.location.origin || !/^\/jsxsd\//i.test(url.pathname)) return false;
        // A named frame may already show a different result after a form POST.
        // Its src attribute would still point at the original placeholder.
        if (child.location.href !== url.href) return false;
        frame.__lvlvRepairedSrc = raw;
        frame.src = url.origin + prefix[1] + url.pathname + url.search + url.hash;
        repairedFrame = true;
        return true;
    }
    function add(doc) {
        if (!trusted(doc.location) || !doc.body || docs.indexOf(doc) >= 0 || docs.length >= 32) return;
        docs.push(doc);
        Array.prototype.forEach.call(doc.querySelectorAll("iframe,frame"), function (frame) {
            if (!visible(frame)) return;
            try {
                var child = frame.contentDocument;
                if (child) {
                    if (!repairProxyFrame(frame,doc,child)) add(child);
                    return;
                }
                // A separately hosted academic frame cannot be inspected from its WebVPN parent.
                // Use its actual HTTPS student-page URL as a top-level navigation.
                if (frame.getAttribute("src")) {
                    var url = new URL(frame.src, doc.baseURI);
                    if (!foreignAcademic && url.origin !== doc.location.origin && trusted(url) && academicPath.test(url.pathname))
                        foreignAcademic = url;
                }
            } catch (ignored) {}
        });
    }

    function loginPage(doc) {
        return /\/(login|fingerprint|cas\/login)(?:[./;]|$)/i.test(doc.location.pathname) ||
            Array.prototype.some.call(doc.querySelectorAll("input[type=password]"), visible);
    }
    var qtyRooms = ["空闲教室查询","空教室查询","教室课表查询","查询空闲教室","空闲教室","教室课表"];
    var plans = ["培养方案明细","培养方案与完成情况","培养方案查看","查看培养方案","个人培养方案","我的培养方案",
        "专业培养方案查询","培养方案查询","培养计划查看","专业执行计划查询","执行计划查询","执行计划","指导培养方案"];
    var academicLabels = ["综合教务","教务系统","综合教务系统","教务管理系统","本科教务系统","强智教务","强智教务系统",
        "综合教务管理系统","教务系统（学生）","教学管理系统","教学一体化服务平台","教务一体化系统"];
    var roomEntry = /\/jsxsd\/(?:kbcx\/kbxx_classroom|kbxx\/jsjy_query)(?:\.(?:jsp|do))?\/?$/i;
    var planEntry = /\/jsxsd\/pyfa\/(?:topyfamx|pyfa_query)(?:\.(?:jsp|do))?\/?$/i;
    var exam = feature === "examFinal" || feature === "examEarly";
    var featureEntry = exam ? /\/jsxsd\/(?:xsks|ksap)\//i : feature === "rooms" ? roomEntry : planEntry;
    var stages = feature === "rooms" ? [
        ["rooms",["教室课表查询"]], ["rooms",qtyRooms,roomEntry], ["schedule",["我的课表","课表查询"]]
    ] : feature === "plan" ? [
        ["detail",["培养方案执行计划"]], ["detail",plans,planEntry], ["plans",["培养方案","培养计划"]]
    ] : exam ? [
        ["exam",feature === "examFinal" ? ["考试安排查询"] : ["随堂考试查询","随堂考信息查询"]],
        ["myExams",["我的考试"]], ["examMenu",["考试报名"]]
    ] : [];
    if (!exam && feature !== "portal") stages.push(["teaching",["培养管理","培养服务","教学服务"]]);
    stages.push(["academic",["旧版教务系统","综合教务系统（旧版）","教务系统（旧版）","旧版综合教务系统"]], ["academic",academicLabels]);

    function linkUrl(el) {
        var raw = el.getAttribute("href");
        if (!raw || /^(?:#|javascript:)/i.test(raw.trim())) return null;
        try {
            var doc = el.ownerDocument, url = new URL(raw, doc.baseURI);
            if (!trusted(url)) return null;
            // Some classic menu anchors retain a root-relative upstream path after WebVPN wraps the document.
            var split = doc.location.pathname.search(academicPath);
            if (split > 0 && /^\/jsxsd(?:\/|$)/i.test(raw) && url.origin === doc.location.origin)
                url.pathname = doc.location.pathname.slice(0,split) + url.pathname;
            return url;
        } catch (ignored) {return null;}
    }
    function matches(el, labels) {
        return [el.title, el.getAttribute("aria-label"), el.getAttribute("alt"),
            el.tagName === "INPUT" ? el.value : "", el.textContent].some(function (value) {
            return value && value.length <= 80 && labels.indexOf(plain(value)) >= 0;
        });
    }
    function findAction(doc, labels, entry) {
        var elements = doc.querySelectorAll("a,button,input[type=button],input[type=submit],img,[onclick],[role=button],[role=menuitem],li,span,div,p,h1,h2,h3,h4,h5,h6,label,strong,b");
        var hiddenLink = null;
        for (var i=0; i<elements.length; i++) {
            var el = elements[i];
            if (el.closest("[disabled],[aria-disabled=true]")) continue;
            var url = el.tagName === "A" ? linkUrl(el) : null;
            var routeMatch = entry && url && entry.test(url.pathname);
            if (!routeMatch && !matches(el,labels)) continue;
            if (!visible(el)) {
                if (!hiddenLink && url && academicPath.test(url.pathname) && (routeMatch || matches(el,labels)) &&
                    el.closest("nav,[role=menu],#LeftMenu1_divChildMenu,#divFirstMenuClass,.Nsb_l_list,.Nsb_menu,.ztree"))
                    hiddenLink = el;
                continue;
            }
            // A styled list item may inherit a pointer cursor while only its nested anchor navigates.
            var link = !el.matches("a,button,input,[onclick]") && el.querySelector("a,button");
            if (link && visible(link) && matches(link,labels)) return link;
            var node = el;
            while (node && node !== doc.body) {
                if (node.matches("a,button,input[type=button],input[type=submit],[onclick],[role=button],[role=menuitem]") ||
                    doc.defaultView.getComputedStyle(node).cursor === "pointer") {
                    // Trusted menu labels may link to an unrelated system. Leave those links to the user.
                    if (node.tagName === "A" && /^(?:https?:)?\/\//i.test(node.getAttribute("href") || "") && !linkUrl(node)) break;
                    return node;
                }
                node = node.parentElement;
            }
            // The mobile WebVPN dashboard also renders Vue resource tiles. Their title (often <p> or <div>)
            // has no href, onclick attribute or pointer cursor. A click bubbles to the tile.
            if (navigate && doc === document && doc.location.hostname === "wwebvpn.sdjtu.edu.cn" &&
                !/^\/https?\//i.test(doc.location.pathname) && labels.indexOf("教务系统") >= 0 &&
                plain(el.textContent) === "教务系统" && !el.closest("a,button,form")) {
                var card = el.parentElement;
                for (var depth = 0; card && card !== doc.body && depth < 6; depth++, card = card.parentElement) {
                    var label = plain(card.textContent);
                    if (label.length <= 180 && /https?:\/\/[^\s<>]+\/sso\.jsp(?:[?#]|$|[^a-zA-Z0-9_./-])/i.test(label))
                        return el;
                }
            }
        }
        return hiddenLink;
    }
    function click(action) {
        var raw = action.getAttribute("href"), url = linkUrl(action);
        if (url && url.origin !== document.location.origin && academicPath.test(url.pathname) &&
            !action.hasAttribute("onclick") && trusted(url)) {
            location.assign(url.href);
            return;
        }
        if (url && url.href !== action.href) {
            action.setAttribute("href",url.href);
            try { action.click(); } finally { action.setAttribute("href",raw); }
        } else action.click();
    }

    add(document);
    if (repairedFrame) return {stage:"openingFrame"};
    var pages = docs.filter(function (doc) { return !loginPage(doc); });
    var academic = pages.filter(function (doc) { return academicPath.test(doc.location.pathname); });
    if (request && request.sync) {
        if (docs.some(function (doc) {return Array.prototype.some.call(doc.querySelectorAll("input[type=password]"),visible);}) || docs.some(loginPage) && !academic.some(function (doc) {
            return doc.querySelector("#kbtable,#mxh,#LeftMenu1_divChildMenu");
        })) return {stage:"login",message:"请先登录山东交通学院统一门户。"};
        for (var i=0; i<pages.length; i++) {
            var message = plain(pages[i].body.innerText);
            if (message.length < 240 && /(?:登录已过期|请重新登录|会话已过期|用户未登录)/.test(message))
                return {stage:"login",message:"教务登录已过期，请重新登录。"};
            if (message.length < 180 && /(?:系统维护中|系统异常|查询失败|服务暂不可用|访问被拒绝|没有访问权限|无权访问)/.test(message))
                return {stage:"error",message:"学校教务暂时无法提供这次查询，请稍后重试。"};
        }
    }

    var result = reader ? reader(navigate, resetMenus, request) : {};
    var base = result.base || "", entryPage = result.entryPage || "";
    academic.forEach(function (doc) {
        var path = doc.location.pathname, idx = path.search(academicPath);
        var menu = doc.querySelector("#LeftMenu1_divChildMenu,#divFirstMenuClass,#Top1_divLoginName,.Nsb_top,.Nsb_menu_pw");
        if (menu || doc.querySelector("#mxh,#kbtable,#dataList") ||
            (/\/jsxsd\/(framework\/|xsMain)/i.test(path) && doc.querySelector("frame,iframe,#dataList")) ||
            findAction(doc,["我的课表","教室课表查询","学期理论课表","培养方案","培养管理"])) {
            base = doc.location.origin + path.slice(0,idx) + "/jsxsd/";
        }
        if (resetMenus) delete doc.__lvlvPortalSteps;
    });
    function outcome(stage) { return {base:base,entryPage:entryPage,stage:stage}; }
    if (["form","results","official","plan","done","choose","error","submitted","examPage","loading","login"].indexOf(result.stage) >= 0) {
        result.base = base;
        result.entryPage = entryPage;
        return result;
    }
    if (!navigate) return outcome("idle");
    if (foreignAcademic && !document.__lvlvPromotedFrame &&
        (!academic.length || featureEntry.test(foreignAcademic.pathname))) {
        document.__lvlvPromotedFrame = true;
        location.assign(foreignAcademic.href);
        return outcome("academic");
    }
    for (var n=0; n<stages.length; n++) {
        var stage = stages[n], portalStage = stage[0] === "academic";
        if (portalStage && (academic.length || foreignAcademic)) continue;
        var sources = portalStage ? pages : academic;
        for (var j=0; j<sources.length; j++) {
            var page = sources[j], action = findAction(page,stage[1],stage[2]);
            if (!action) continue;
            var steps = page.__lvlvPortalSteps || (page.__lvlvPortalSteps = {});
            if (steps[stage[0]] !== action) {
                steps[stage[0]] = action;
                click(action);
            }
            return outcome(stage[0]);
        }
    }

    return outcome("waiting");
})
