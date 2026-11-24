package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 强智教务（/jsxsd/）。课表页 {@code jsxsd/xskb/xskb_list.do} 是一张 HTML 表格 {@code #kbtable}：
 * 每个格子里有 {@code div.kbcontent}（详细）和 {@code div.kbcontent1}（简略）两份，只解析详细那份；
 * 一个格子里多门课用一行 {@code -----} 隔开；字段放在 {@code <font title="老师|周次(节次)|教室">} 里，
 * 周次节次形如 {@code 1-16(周)[01-02节]}。
 *
 * 星期优先按表头（星期一…星期日）定位，表头认不出时按 div id 末尾的 {@code -星期-2}，再不行按列序；
 * 节次优先取 {@code [01-02节]}，没有时按行表头（如 0102）或「第 N 大节」推算。
 *
 * 思路参考拾光课程表适配仓库（MIT）中对强智 kbcontent 的说明；代码为烫梨独立实现。
 */
public final class QiangzhiSource {
    private QiangzhiSource() {}

    public static final class Result {
        public final Timetable timetable = new Timetable();
        public int rawRecords;          // 表格中的原始课程条数
        public int courseCount;         // 聚合后的课程数
        public int failedWeekExpr;      // 周次解析失败的条数
        public int mergedDuplicates;    // 同名同时段被合并的重复时段数
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() {
            return courseCount > 0;
        }
    }

    public static final String TIMETABLE_PATH = "xskb/xskb_list.do";
    public static final String EMPTY_HINT = "请先在教务里打开「学期理论课表」页面，再点「导入此页课表」。";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern TR = Pattern.compile("<tr\\b[^>]*>(.*?)(?=<tr\\b|</table>|$)", FLAGS);
    private static final Pattern CELL = Pattern.compile("<(td|th)\\b([^>]*)>(.*?)(?=<td\\b|<th\\b|</tr>|$)", FLAGS);
    private static final Pattern DIV = Pattern.compile("<div\\b([^>]*)>(.*?)</div>", FLAGS);
    private static final Pattern FIELD = Pattern.compile(
            "<(font\\b|span\\b(?=[^>]*\\btitle\\s*=))([^>]*)>(.*?)</\\1\\s*>", FLAGS);
    // 交院旧强智的课程号，如 004714-330001Y。只有后面另有名称时才拆出未标注的编号。
    private static final Pattern LEADING_CODE = Pattern.compile(
            "(?:[0-9]{4,}|[A-Za-z]{1,4}[0-9]{3,}[A-Za-z0-9]*)(?:[-－][A-Za-z0-9]+)*");
    private static final Pattern TITLE_ATTR = Pattern.compile("\\btitle\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", FLAGS);
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", FLAGS);
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", FLAGS);
    private static final Pattern COLSPAN = Pattern.compile("\\bcolspan\\s*=\\s*[\"']?(\\d+)", FLAGS);
    private static final Pattern SEPARATOR = Pattern.compile("(?:<br\\s*/?>\\s*)?-{5,}\\s*(?:<br\\s*/?>)?", FLAGS);
    private static final Pattern BRACKET_SECTIONS = Pattern.compile("[\\[［【]([^\\]］】]*)[\\]］】]");
    private static final Pattern SELECT_TERM = Pattern.compile(
            "<select\\b[^>]*(?:id|name)\\s*=\\s*[\"']?xnxq01id[\"']?[^>]*>(.*?)</select>", FLAGS);
    private static final Pattern INPUT_TERM = Pattern.compile(
            "<input\\b[^>]*(?:id|name)\\s*=\\s*[\"']?xnxq01id[\"']?[^>]*>", FLAGS);
    private static final Pattern OPTION = Pattern.compile("<option\\b([^>]*)>", FLAGS);
    private static final Pattern VALUE_ATTR = Pattern.compile("\\bvalue\\s*=\\s*[\"']?([^\"'\\s>]+)", FLAGS);
    private static final Pattern TERM_ID = Pattern.compile("(\\d{4})-(\\d{4})-(\\d)");
    private static final String[] CN_NUM = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 将教务参数 xnm/xqm（如 2024 / 3、12）转为强智学期编号（如 2024-2025-1）；无效时返回空串。 */
    public static String termId(String xnm, String xqm) {
        if (xnm == null || !xnm.matches("\\d{4}")) return "";
        int y = Integer.parseInt(xnm);
        int n = "12".equals(xqm) ? 2 : ("16".equals(xqm) ? 3 : 1);
        return y + "-" + (y + 1) + "-" + n;
    }

    /** 同步落盘决策：普通同步与历史同步分开判断，防止缺失或错位的学期写进历史归档。 */
    public static final class SyncTarget {
        public enum Action { SAVE_ACTIVE, SAVE_ARCHIVE, SKIP }

        public final Action action;
        public final String semesterId;
        public final String skipReason;

        private SyncTarget(Action action, String semesterId, String skipReason) {
            this.action = action;
            this.semesterId = semesterId == null ? "" : semesterId;
            this.skipReason = skipReason == null ? "" : skipReason;
        }
    }

    /**
     * 判断本次同步解析出的课表是否允许落盘以及写入哪个学期：
     * - 历史同步（targetXnm 非空）：必须返回非空且与请求学期一致的 xnxq01id，否则跳过归档。
     * - 普通同步（targetXnm 为空）：返回了明确 xnxq01id 时写入该学期；缺少 xnxq01id 时，仅当当前激活学期就是本学期才沿用，避免把当前课表覆盖进历史或自定义学期。
     */
    public static SyncTarget resolveSyncTarget(String targetXnm, String targetXqm,
                                               String actualSemesterId,
                                               String activeSemesterId,
                                               String currentCalendarTermId) {
        String actual = actualSemesterId == null ? "" : actualSemesterId.trim();
        String active = activeSemesterId == null ? "" : activeSemesterId.trim();
        String current = currentCalendarTermId == null ? "" : currentCalendarTermId.trim();
        if (targetXnm != null && !targetXnm.isEmpty()) {
            String expected = termId(targetXnm, targetXqm);
            if (expected.isEmpty()) {
                return new SyncTarget(SyncTarget.Action.SKIP, "", "请求的历史学期参数无效，已跳过归档");
            }
            if (actual.isEmpty()) {
                return new SyncTarget(SyncTarget.Action.SKIP, "",
                        "教务返回的课表缺少学期标识（请求 " + expected + "），已跳过归档");
            }
            if (!expected.equals(actual)) {
                return new SyncTarget(SyncTarget.Action.SKIP, "",
                        "教务返回的是 " + actual + "（请求 " + expected + "），已跳过归档");
            }
            return new SyncTarget(SyncTarget.Action.SAVE_ARCHIVE, actual, "");
        }
        if (!actual.isEmpty()) {
            return new SyncTarget(SyncTarget.Action.SAVE_ACTIVE, actual, "");
        }
        if (!active.isEmpty() && !active.startsWith("custom_") && active.equals(current)) {
            return new SyncTarget(SyncTarget.Action.SAVE_ACTIVE, active, "");
        }
        return new SyncTarget(SyncTarget.Action.SKIP, "",
                "教务返回的课表缺少学期标识，已取消保存以免覆盖历史学期");
    }

    /**
     * 先在当前页面和同源 frame 里找 #kbtable；找不到或需要切换历史学期时请求 xskb_list.do。
     * 只把课表表格和学期控件交回 Java，不带页面其余内容。
     */
    public static String extractionJs(String xnm, String xqm) {
        String term = termId(xnm, xqm);
        return "(function(){" +
                "try{" +
                "function sel(doc){return doc&&(doc.getElementById('xnxq01id')" +
                "||(doc.querySelector&&doc.querySelector('select[name=\"xnxq01id\"],input[name=\"xnxq01id\"]')));}" +
                // #kbtable，或新版界面里任何装着 .kbcontent 的表格
                "function pick(doc){var t=doc.getElementById('kbtable');" +
                "if(!t){var ts=doc.getElementsByTagName('table');for(var j=0;j<ts.length;j++){" +
                "if(ts[j].querySelector&&ts[j].querySelector('.kbcontent')){t=ts[j];break;}}}" +
                "if(!t)return '';var s=sel(doc);" +
                "return (s?s.outerHTML:'')+t.outerHTML;}" +
                "function termOf(doc){var s=sel(doc);if(!s)return '';" +
                "if(s.tagName&&s.tagName.toLowerCase()==='select'){" +
                "for(var k=0;k<s.options.length;k++)if(s.options[k].hasAttribute('selected'))return (s.options[k].value||'').trim();" +
                "return '';}return (s.value||'').trim();}" +
                "var want='" + term + "';" +
                "if(!want){var r=pick(document);if(r)return r;" +
                "for(var f=0;f<window.frames.length;f++){try{var r2=pick(window.frames[f].document);" +
                "if(r2)return r2;}catch(e){}}}" +
                "else{if(termOf(document)===want){var r0=pick(document);if(r0)return r0;}" +
                "for(var f0=0;f0<window.frames.length;f0++){try{var fd=window.frames[f0].document;" +
                "if(termOf(fd)===want){var rf=pick(fd);if(rf)return rf;}}catch(e){}}}" +
                "var p=location.pathname;var i=p.indexOf('/jsxsd/');" +
                "var base=i>=0?p.substring(0,i+7):'/jsxsd/';" +
                "function resolve(u){u=(u||'').replace(/&amp;/g,'&');" +
                "if(u.indexOf('/jsxsd/')===0)return base+u.substring(7);" +
                "if(u.indexOf('http://')===0||u.indexOf('https://')===0||u.indexOf('/')===0)return u;" +
                "var xk=u.indexOf('xskb/');if(xk>=0)return base+u.substring(xk);" +
                "return base+'xskb/'+u.replace(/^\\.\\//,'');}" +
                "function withTerm(u){if(!want)return u;" +
                "var clean=u.replace(/([?&])xnxq01id=[^&]*(&?)/gi,function(_,a,b){return b?a:'';}).replace(/[?&]$/,'');" +
                "return clean+(clean.indexOf('?')>=0?'&':'?')+'xnxq01id='+encodeURIComponent(want);}" +
                // 候选课表地址：页面菜单里出现过的 xskb 链接优先，再试常见地址；历史同步一律附带 xnxq01id
                "var cands=[];function add(u){u=withTerm(resolve(u));if(u&&cands.indexOf(u)<0)cands.push(u);}" +
                "var all=document.documentElement.outerHTML;" +
                "for(var f2=0;f2<window.frames.length;f2++){try{all+=window.frames[f2].document.documentElement.outerHTML;}catch(e){}}" +
                "var re=/[\\w\\/.-]*xskb\\/xskb_list\\.[a-z]+[^'\\\"\\s<>)]*/gi,m;" +
                "while((m=re.exec(all))&&cands.length<4){add(m[0]);}" +
                "add(base+'xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL');" +
                "add(base+'xskb/xskb_list.do');" +
                "add(base+'xskb/xskb_list.htmlx');" +
                "var fallback='',diag=[];" +
                "function inspectResp(txt,label,st){var d=null;try{d=new DOMParser().parseFromString(txt,'text/html');}catch(e){}" +
                "var r3=d?pick(d):'';if(r3){if(!want||termOf(d)===want)return r3;if(!fallback)fallback=r3;}" +
                "else if(txt.indexOf('kbtable')>=0){if(!want)return txt;if(!fallback)fallback=txt;}" +
                "var tt=d&&d.title?d.title:'';" +
                "diag.push(label+' '+st+' '+txt.length+'B '+tt.substring(0,20)" +
                "+(txt.indexOf('kbcontent')>=0?' kbcontent':''));return '';}" +
                "if(want){try{var fdoc=document,s0=sel(fdoc);" +
                "if(!s0){for(var f3=0;f3<window.frames.length;f3++){try{var fd3=window.frames[f3].document;" +
                "var sf=sel(fd3);if(sf){fdoc=fd3;s0=sf;break;}}catch(e){}}}" +
                "var fm=s0&&s0.form;var act=resolve((fm&&fm.getAttribute('action'))||(base+'xskb/xskb_list.do'));" +
                "var pairs=['xnxq01id='+encodeURIComponent(want)];" +
                "if(fm){var ins=fm.querySelectorAll('input[name],select[name]');for(var k=0;k<ins.length;k++){" +
                "var el=ins[k],nm=el.name;if(!nm||nm==='xnxq01id'||el.disabled)continue;" +
                "var tp=(el.type||'').toLowerCase();if((tp==='checkbox'||tp==='radio')&&!el.checked)continue;" +
                "if(tp==='submit'||tp==='button'||tp==='reset'||tp==='file')continue;" +
                "pairs.push(encodeURIComponent(nm)+'='+encodeURIComponent(el.value||''));}}" +
                "var xp=new XMLHttpRequest();xp.open('POST',act,false);" +
                "xp.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
                "xp.send(pairs.join('&'));" +
                "var rp=inspectResp(xp.responseText||'','POST '+act.replace(/\\?.*$/,''),xp.status);" +
                "if(rp)return rp;}catch(e){diag.push('POST ERR');}}" +
                "for(var c=0;c<cands.length;c++){try{" +
                "var x=new XMLHttpRequest();x.open('GET',cands[c],false);x.send();" +
                "var rg=inspectResp(x.responseText||'',cands[c].replace(/\\?.*$/,''),x.status);" +
                "if(rg)return rg;}catch(e){diag.push(cands[c].replace(/\\?.*$/,'')+' ERR');}}" +
                "if(fallback)return fallback;" +
                "return 'NOTABLE|'+diag.join(' ; ');" +
                "}catch(e){return 'ERR:'+e;}" +
                "})()";
    }

    /** 解析 xskb_list.do 的 HTML（整页或只含 #kbtable 都行）。 */
    public static Result parseHtml(String html) {
        Result r = new Result();
        if (html == null || html.trim().isEmpty() || html.startsWith("ERR:") || html.startsWith("NOTABLE")) {
            r.warnings.add("页面里没有课表表格");
            // 诊断：试过的课表地址、状态码、长度、标题（不含课表内容和学生信息）
            if (html != null && html.startsWith("NOTABLE|") && html.length() > 8) {
                String d = html.substring(8);
                r.warnings.add("试过: " + (d.length() > 400 ? d.substring(0, 400) : d));
            }
            return r;
        }
        parseSemester(html, r.timetable.semester);
        r.timetable.semester.totalWeeks = 20;

        String table = html;
        int k = indexOfIgnoreCase(html, "kbtable");
        if (k >= 0) {
            int start = html.lastIndexOf('<', k);
            int end = indexOfIgnoreCase(html, "</table>", k);
            table = html.substring(Math.max(0, start), end < 0 ? html.length() : end + 8);
        } else {
            Matcher tbl = Pattern.compile("(?is)<table\\b[^>]*>.*?</table>").matcher(html);
            String best = null;
            while (tbl.find()) {
                String candidate = tbl.group();
                int wk = candidate.indexOf("星期一");
                if (wk < 0) wk = candidate.indexOf("周一");
                if (wk >= 0) {
                    int inner = lastIndexOfIgnoreCase(candidate.substring(0, wk), "<table");
                    best = inner > 0 ? candidate.substring(inner) : candidate;
                    break;
                }
            }
            if (best != null) {
                table = best;
            } else {
                r.warnings.add("没有 #kbtable，按整页解析");
            }
        }

        Map<String, Timetable.Course> grouped = new LinkedHashMap<>();
        int[] headerDays = null;
        int dataRow = 0;
        Matcher tr = TR.matcher(table);
        while (tr.find()) {
            String row = tr.group(1);
            List<String[]> cells = cells(row);
            if (cells.isEmpty()) continue;
            if (headerDays == null && looksLikeHeader(cells)) {
                headerDays = headerDays(cells);
                continue;
            }
            boolean hasTd = false;
            for (String[] c : cells) if ("td".equals(c[0])) { hasTd = true; break; }
            if (!hasTd) continue;
            dataRow++;

            int[] rowSections = null;
            int col = 0;
            for (String[] c : cells) {
                boolean isLabel = "th".equals(c[0])
                        || (col == 0 && !c[2].contains("kbcontent") && looksLikeRowLabel(c[2]));
                if (isLabel) {
                    if (rowSections == null) rowSections = rowLabelSections(text(c[2]), dataRow);
                    continue;
                }
                int colIndex = col;
                col += colspan(c[1]);
                if (!c[2].contains("kbcontent")) continue;
                if (rowSections == null) rowSections = new int[]{dataRow * 2 - 1, dataRow * 2};

                int headerDay = headerDays != null && colIndex < headerDays.length ? headerDays[colIndex] : 0;
                for (String[] div : contentDivs(c[2])) {
                    int day = headerDay > 0 ? headerDay : dayFromId(div[0]);
                    if (day <= 0) day = colIndex + 1;
                    if (day < 1 || day > 7) continue;
                    for (String block : SEPARATOR.split(div[1])) {
                        addBlock(block, day, rowSections, grouped, r);
                    }
                }
            }
        }

        List<Timetable.Course> courses = new ArrayList<>(grouped.values());
        // 有些页面先显示名称。遇到同名、同教师的多个编号时，所有相关课程都用编号区分。
        Set<String> seenKeys = new HashSet<>(), ambiguousKeys = new HashSet<>();
        for (Timetable.Course c : courses) if (!seenKeys.add(c.key)) ambiguousKeys.add(c.key);
        for (Timetable.Course c : courses) {
            if (ambiguousKeys.contains(c.key) && !c.code.isEmpty()) c.key = "qiangzhi_" + c.code + "_" + c.teacher;
        }
        r.mergedDuplicates = mergeOverlaps(courses);
        r.timetable.courses.addAll(courses);
        CourseColorAssigner.assign(r.timetable);
        r.courseCount = r.timetable.courses.size();
        if (r.courseCount == 0) r.warnings.add("课表表格里没有课程");
        return r;
    }

    // ---------- 单门课 ----------

    private static void addBlock(String block, int day, int[] rowSections,
                                 Map<String, Timetable.Course> grouped, Result r) {
        String teacher = "", weeksField = "", location = "";
        String name = "", code = "";
        StringBuilder heading = new StringBuilder();
        boolean readingHeading = true;
        int last = 0;
        Matcher field = FIELD.matcher(block);
        while (field.find()) {
            if (readingHeading) heading.append(block, last, field.start());
            last = field.end();
            String title = attr(TITLE_ATTR, field.group(2));
            String value = text(field.group(3));
            if (value.isEmpty()) continue;
            if (title.contains("课程名") || title.equals("课程")) {
                name = joinName(lines(field.group(3)));
            } else if (title.contains("课程号") || title.contains("课程编号")
                    || title.contains("课程代码") || title.contains("课程编码")) {
                code = value;
            } else if (title.contains("老师") || title.contains("教师")) {
                if (teacher.isEmpty()) teacher = value;
                readingHeading = false;
            } else if (title.contains("周次") || title.contains("节次")
                    || (title.isEmpty() && value.matches(".*[0-9].*周.*"))) {
                if (weeksField.isEmpty()) weeksField = value;
                readingHeading = false;
            } else if (title.contains("教室") || title.contains("地点") || title.contains("场地")) {
                if (location.isEmpty()) location = value;
                readingHeading = false;
            } else if (title.isEmpty() && readingHeading) {
                heading.append("<br>").append(field.group()).append("<br>");
            }
        }
        if (readingHeading) heading.append(block.substring(last));

        List<String> parts = lines(heading.toString());
        String first = parts.isEmpty() ? "" : parts.get(0);
        boolean leadingCode = LEADING_CODE.matcher(first).matches()
                && (parts.size() > 1 || !name.isEmpty()) && (code.isEmpty() || code.equals(first));
        if (leadingCode) {
            code = first;
            parts.remove(0);
        }
        // 早期模板用没有 title 的 font 显示教师等元数据，保留正文里的课程名。
        if (name.isEmpty() && teacher.isEmpty() && FIELD.matcher(heading).find()) {
            List<String> plainParts = lines(FIELD.matcher(heading).replaceAll("<br>"));
            if (leadingCode && !plainParts.isEmpty() && first.equals(plainParts.get(0))) plainParts.remove(0);
            if (!plainParts.isEmpty()) name = joinName(plainParts);
            else if (!parts.isEmpty()) name = parts.get(0);
        }
        if (name.isEmpty()) name = joinName(parts);
        if (name.isEmpty()) return;

        int start = rowSections[0], end = rowSections[1];
        String weeksExpr = weeksField;
        Matcher bm = BRACKET_SECTIONS.matcher(weeksField);
        int[] fallback = null;
        int fallbackAt = -1;
        while (bm.find()) {
            // 节次括号：「[01-02节]」「[01-02]节」；「1-16[周]」「[1-16]周」里的括号是周次，不能当节次
            String inner = bm.group(1);
            String after = weeksField.substring(bm.end()).trim();
            int[] s = sectionsFrom(inner);
            if (s == null) continue;
            if (inner.contains("节") || after.startsWith("节")) {
                start = s[0]; end = s[1];
                weeksExpr = weeksField.substring(0, bm.start()).trim();
                fallback = null;
                break;
            }
            if (fallback == null && !inner.contains("周") && !after.startsWith("周")) {
                fallback = s;
                fallbackAt = bm.start();
            }
        }
        if (fallback != null) {
            start = fallback[0]; end = fallback[1];
            weeksExpr = weeksField.substring(0, fallbackAt).trim();
        }

        // 旧版本用第一行作为 key。保留编号在前的 key 以继续匹配备注、调课和自选颜色。
        String identity = leadingCode ? code : name;
        String key = "qiangzhi_" + identity + "_" + teacher;
        String groupKey = (code.isEmpty() ? "name:" + name : "code:" + code) + "_" + teacher;
        Timetable.Course course = grouped.get(groupKey);
        if (course == null) {
            course = new Timetable.Course();
            course.key = key;
            course.name = name;
            course.code = code;
            course.teacher = teacher;
            course.source = Timetable.SOURCE_JWGLXT;
            grouped.put(groupKey, course);
        } else if (leadingCode) {
            course.key = key;
        }
        r.rawRecords++;

        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = day;
        s.startSection = start;
        s.endSection = end;
        s.weeksRaw = weeksExpr;
        WeekRange.Parsed wr = WeekRange.parse(weeksExpr);
        s.weeks.addAll(wr.weeks);
        if (!wr.ok()) {
            r.failedWeekExpr++;
            r.warnings.add("周次解析失败: '" + weeksExpr + "' (" + name + ")");
        }
        s.location = location;
        s.teacher = teacher;
        for (Timetable.Session x : course.sessions) {
            if (x.dayOfWeek == s.dayOfWeek && x.startSection == s.startSection
                    && x.endSection == s.endSection && x.weeksRaw.equals(s.weeksRaw)) {
                return;
            }
        }
        course.sessions.add(s);
    }

    // ---------- 表格结构 ----------

    /** 每个元素 {标签, 属性, 内容}。 */
    private static List<String[]> cells(String row) {
        List<String[]> out = new ArrayList<>();
        Matcher m = CELL.matcher(row);
        while (m.find()) {
            String body = m.group(3);
            int close = lastIndexOfIgnoreCase(body, "</" + m.group(1).toLowerCase(Locale.ROOT) + ">");
            if (close >= 0) body = body.substring(0, close);
            out.add(new String[]{m.group(1).toLowerCase(Locale.ROOT), m.group(2), body});
        }
        return out;
    }

    private static boolean looksLikeHeader(List<String[]> cells) {
        int hits = 0;
        for (String[] c : cells) if (dayFromText(text(c[2])) > 0) hits++;
        return hits >= 5;
    }

    /** 表头列（不含行表头格子）→ 星期。 */
    private static int[] headerDays(List<String[]> cells) {
        List<Integer> days = new ArrayList<>();
        boolean first = true;
        for (String[] c : cells) {
            int d = dayFromText(text(c[2]));
            if (first && d == 0) { first = false; continue; } // 左上角空格
            first = false;
            int span = colspan(c[1]);
            for (int i = 0; i < span; i++) days.add(d);
        }
        int[] out = new int[days.size()];
        for (int i = 0; i < out.length; i++) out[i] = days.get(i);
        return out;
    }

    static int dayFromText(String t) {
        if (t == null) return 0;
        String s = t.replaceAll("\\s+", "");
        String[] names = {"一", "二", "三", "四", "五", "六"};
        for (int i = 0; i < names.length; i++) {
            if (s.contains("星期" + names[i]) || s.equals("周" + names[i])
                    || s.startsWith("周" + names[i])) return i + 1;
        }
        if (s.contains("星期日") || s.contains("星期天") || s.startsWith("周日") || s.startsWith("周天")) return 7;
        return 0;
    }

    private static boolean looksLikeRowLabel(String html) {
        String t = text(html);
        return t.contains("大节") || t.contains("小节") || t.matches("^第?[0-9一二三四五六七八九十]+节.*");
    }

    /** 行表头 → 节次：「0102」「01,02」「1-2节」优先，其次「第三大节」，都没有按行号。 */
    static int[] rowLabelSections(String label, int dataRow) {
        String raw = label == null ? "" : label;
        String t = raw.replaceAll("\\s+", "");
        Matcher pairs = Pattern.compile("(?<![0-9])(\\d{2})(\\d{2})(?![0-9])").matcher(t);
        if (pairs.find()) {
            int a = Integer.parseInt(pairs.group(1)), b = Integer.parseInt(pairs.group(2));
            if (a > 0 && b >= a && b - a < 6) return new int[]{a, b};
        }
        String withoutTimes = raw.replaceAll("\\d{1,2}:\\d{2}", " ").replaceAll("\\s+", "");
        Matcher nums = Pattern.compile("\\d+").matcher(withoutTimes.replaceAll("^第[一二三四五六七八九十]+大节", ""));
        List<Integer> list = new ArrayList<>();
        while (nums.find()) list.add(Integer.parseInt(nums.group()));
        if (list.size() >= 2) {
            int a = list.get(0), b = list.get(list.size() - 1);
            if (a > 0 && b >= a && b - a < 6) return new int[]{a, b};
        }
        for (int i = CN_NUM.length - 1; i >= 0; i--) {
            if (t.contains("第" + CN_NUM[i] + "大节")) return new int[]{i * 2 + 1, i * 2 + 2};
        }
        int n = Math.max(1, dataRow);
        return new int[]{n * 2 - 1, n * 2};
    }

    /** 「01-02节」「01-02-03-04节」「9-11节」→ [起, 止]。 */
    static int[] sectionsFrom(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s);
        int min = Integer.MAX_VALUE, max = -1;
        while (m.find()) {
            int v = Integer.parseInt(m.group());
            if (v <= 0 || v > 20) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max < 0 ? null : new int[]{min, max};
    }

    /** 格子里的详细课程 div：{id, 内容}。没有 kbcontent 时退回 kbcontent1。 */
    private static List<String[]> contentDivs(String cell) {
        List<String[]> detailed = new ArrayList<>(), brief = new ArrayList<>();
        Matcher m = DIV.matcher(cell);
        while (m.find()) {
            String cls = attr(CLASS_ATTR, m.group(1));
            String[] item = {attr(ID_ATTR, m.group(1)), m.group(2)};
            if (cls.matches("(^|.*\\s)kbcontent(\\s.*|$)")) detailed.add(item);
            else if (cls.matches("(^|.*\\s)kbcontent1(\\s.*|$)")) brief.add(item);
        }
        return detailed.isEmpty() ? brief : detailed;
    }

    /** div id 形如 {@code 4F2A…-3-2}：倒数第二段是星期。 */
    static int dayFromId(String id) {
        if (id == null) return 0;
        Matcher m = Pattern.compile("-(\\d)-\\d$").matcher(id.trim());
        if (!m.find()) return 0;
        int d = Integer.parseInt(m.group(1));
        return d >= 1 && d <= 7 ? d : 0;
    }

    private static void parseSemester(String html, Timetable.Semester sem) {
        String value = "";
        Matcher sel = SELECT_TERM.matcher(html);
        if (sel.find()) {
            Matcher opt = OPTION.matcher(sel.group(1));
            while (opt.find()) {
                String attrs = opt.group(1);
                if (attrs.toLowerCase(Locale.ROOT).matches(".*\\bselected\\b.*")) {
                    value = attr(VALUE_ATTR, attrs);
                    break;
                }
            }
        } else {
            Matcher inp = INPUT_TERM.matcher(html);
            if (inp.find()) value = attr(VALUE_ATTR, inp.group());
        }
        Matcher t = TERM_ID.matcher(value);
        if (!t.find()) return;
        sem.id = t.group(1) + "-" + t.group(2) + "-" + t.group(3);
        sem.name = t.group(1) + "-" + t.group(2) + " 学年第" + t.group(3) + "学期";
    }

    // ---------- 文本工具 ----------

    private static int colspan(String attrs) {
        Matcher m = COLSPAN.matcher(attrs == null ? "" : attrs);
        if (!m.find()) return 1;
        try {
            return Math.max(1, Math.min(14, Integer.parseInt(m.group(1))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String attr(Pattern p, String attrs) {
        if (attrs == null) return "";
        Matcher m = p.matcher(attrs);
        if (!m.find()) return "";
        for (int g = 2; g <= m.groupCount(); g++) if (m.group(g) != null) return m.group(g).trim();
        return m.group(1) == null ? "" : m.group(1).trim();
    }

    private static List<String> lines(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?is)<[^>]+>", "");
        List<String> out = new ArrayList<>();
        for (String line : unescape(s).split("\n")) {
            String part = line.trim().replaceFirst("^[●★○◆■\\s]+", "");
            if (!part.isEmpty() && !part.matches("-+")) out.add(part);
        }
        return out;
    }

    private static String joinName(List<String> parts) {
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (name.length() > 0) {
                char previous = name.charAt(name.length() - 1), next = part.charAt(0);
                if (previous < 128 && next < 128
                        && Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(next)) name.append(' ');
            }
            name.append(part);
        }
        return name.toString();
    }

    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("(?is)<[^>]+>", "");
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace('\u00a0', ' ').replace('\u3000', ' ').trim()
                .replace("&ensp;", " ").replace("&emsp;", " ");
    }

    private static int indexOfIgnoreCase(String s, String key) {
        return indexOfIgnoreCase(s, key, 0);
    }

    private static int indexOfIgnoreCase(String s, String key, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(key.toLowerCase(Locale.ROOT), from);
    }

    private static int lastIndexOfIgnoreCase(String s, String key) {
        return s.toLowerCase(Locale.ROOT).lastIndexOf(key.toLowerCase(Locale.ROOT));
    }

    /** 合并同课名和课号在同节次、同一天且周次重叠的上课安排。 */
    static int mergeOverlaps(List<Timetable.Course> courses) {
        int merged = 0;
        for (int i = 0; i < courses.size(); i++) {
            Timetable.Course a = courses.get(i);
            for (int j = i; j < courses.size(); j++) {
                Timetable.Course b = courses.get(j);
                if (!a.name.equals(b.name) || !a.code.equals(b.code)) continue;
                for (int si = 0; si < a.sessions.size(); si++) {
                    Timetable.Session x = a.sessions.get(si);
                    int start = (a == b) ? si + 1 : 0;
                    for (int sj = start; sj < b.sessions.size(); sj++) {
                        Timetable.Session y = b.sessions.get(sj);
                        if (x.dayOfWeek != y.dayOfWeek || x.startSection != y.startSection
                                || x.endSection != y.endSection || !intersects(x.weeks, y.weeks)) continue;
                        mergeTeachers(x, y, a.teacher, b.teacher);
                        boolean added = false;
                        for (Integer w : y.weeks) if (!x.weeks.contains(w)) { x.weeks.add(w); added = true; }
                        java.util.Collections.sort(x.weeks);
                        if (added && !y.weeksRaw.isEmpty() && !x.weeksRaw.equals(y.weeksRaw)) {
                            x.weeksRaw = x.weeksRaw.isEmpty() ? y.weeksRaw : x.weeksRaw + "," + y.weeksRaw;
                        }
                        x.location = joinDistinct(x.location, y.location, " / ");
                        b.sessions.remove(sj);
                        sj--;
                        merged++;
                    }
                }
            }
        }
        for (int i = courses.size() - 1; i >= 0; i--) {
            if (courses.get(i).sessions.isEmpty()) courses.remove(i);
        }
        return merged;
    }

    /**
     * 合并两条记录的教师：周次范围更窄的那条更具体（如「第11周 刘锦春」比「11-14周 夏菲」具体），
     * 它覆盖的周记到 weekTeachers 里；宽的那条作为本时段默认教师。
     */
    private static void mergeTeachers(Timetable.Session x, Timetable.Session y,
                                      String courseTeacherX, String courseTeacherY) {
        String tx = x.teacher.isEmpty() ? courseTeacherX : x.teacher;
        String ty = y.teacher.isEmpty() ? courseTeacherY : y.teacher;
        if (ty == null || ty.isEmpty() || ty.equals(tx)) {
            for (java.util.Map.Entry<Integer, String> e : y.weekTeachers.entrySet()) {
                x.weekTeachers.putIfAbsent(e.getKey(), e.getValue());
            }
            return;
        }
        if (y.weeks.size() <= x.weeks.size() || x.weeks.isEmpty()) {
            for (Integer w : y.weeks) x.weekTeachers.put(w, ty);
        } else {
            for (Integer w : x.weeks) x.weekTeachers.putIfAbsent(w, tx);
            x.teacher = ty;
        }
        for (java.util.Map.Entry<Integer, String> e : y.weekTeachers.entrySet()) {
            x.weekTeachers.put(e.getKey(), e.getValue());
        }
    }

    private static boolean intersects(List<Integer> a, List<Integer> b) {
        for (Integer w : a) if (b.contains(w)) return true;
        return false;
    }

    private static String joinDistinct(String a, String b, String sep) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        for (String p : a.split(java.util.regex.Pattern.quote(sep))) if (p.equals(b)) return a;
        return a + sep + b;
    }
}
