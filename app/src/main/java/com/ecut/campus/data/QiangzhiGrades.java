package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 强智教务成绩：kscj/cjcx_list 的 #dataList 表格。
 *
 * 各校列数和顺序不一样，所以只按表头文字找列（开课学期、课程编号、课程名称、成绩、学分、绩点……），
 * 找不到「课程名称」和「成绩」两列就当作解析失败。纯 Java，无 Android 依赖。
 */
public final class QiangzhiGrades {

    public static final String GRADES_PATH = "kscj/cjcx_list";
    public static final String QUERY_FORM_BODY = "kksj=&kcxz=&kcmc=&xsfs=all";
    /** 分页拉取上限，避免学校改版导致死循环分页。 */
    public static final int MAX_PAGES = 20;
    /** 单次解析最大行数保护。 */
    public static final int MAX_ROWS = 2000;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern TABLE = Pattern.compile(
            "<table\\b[^>]*\\bid\\s*=\\s*[\"']?dataList[\"']?[^>]*>(.*?)</table>", FLAGS);
    private static final Pattern TR = Pattern.compile("<tr\\b[^>]*>(.*?)(?=<tr\\b|</table>|$)", FLAGS);
    private static final Pattern CELL = Pattern.compile("<(td|th)\\b[^>]*>(.*?)(?=<td\\b|<th\\b|</tr>|$)", FLAGS);
    private static final Pattern TERM_ID = Pattern.compile("(\\d{4})-(\\d{4})-(\\d)");
    private static final Pattern TOTAL_PAGES_CN = Pattern.compile("共\\s*(\\d+)\\s*页");
    private static final Pattern TOTAL_PAGES_SLASH = Pattern.compile("/\\s*(\\d+)\\s*页");
    private static final Pattern NO_PERM_TEXT = Pattern.compile("无(操作)?权限|未开放|评教");

    private QiangzhiGrades() {}

    /** 构造强智全量成绩查询的表单体；page <= 1 为首页。 */
    public static String pageQueryBody(int page) {
        if (page <= 1) return QUERY_FORM_BODY;
        return QUERY_FORM_BODY + "&pageNum=" + page + "&pageIndex=" + page;
    }

    /** 从成绩查询页 HTML 中提取 #dataList 完整表格标签，未找到返回空串。 */
    public static String extractDataListTable(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher m = TABLE.matcher(html);
        return m.find() ? m.group(0) : "";
    }

    /** 从分页提示文案解析总页数，范围收敛在 [1, MAX_PAGES]。 */
    public static int totalPages(String html) {
        if (html == null || html.isEmpty()) return 1;
        String plain = QiangzhiSource.text(html);
        Matcher m = TOTAL_PAGES_CN.matcher(plain);
        boolean matched = m.find();
        if (!matched) {
            m = TOTAL_PAGES_SLASH.matcher(plain);
            matched = m.find();
        }
        if (matched) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n > 1) return Math.min(n, MAX_PAGES);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    /** 判断成绩页是否明确提示无查询权限或未完成评教。 */
    public static boolean isNoPermission(String html) {
        if (html == null || html.isEmpty() || !extractDataListTable(html).isEmpty()) return false;
        String plain = QiangzhiSource.text(html);
        return plain.length() < 2000 && NO_PERM_TEXT.matcher(plain).find();
    }

    /** 判断成绩表是否包含有效表头（至少同时存在课程名称与成绩列），用于区分“真实 0 门成绩”与结构异常。 */
    public static boolean hasValidHeader(String html) {
        if (html == null || html.isEmpty()) return false;
        Matcher tm = TABLE.matcher(html);
        String body = tm.find() ? tm.group(1) : html;
        Matcher rm = TR.matcher(body);
        while (rm.find()) {
            List<String> cells = cells(rm.group(1));
            if (cells.isEmpty()) continue;
            return header(cells)[C_NAME] >= 0;
        }
        return false;
    }

    /**
     * 当前页面有 #dataList 就直接用；否则同步 POST 查全部学期成绩。
     * 若教务返回分页成绩表，自动翻页合并，最多拉取 {@link #MAX_PAGES} 页，避免死循环。
     * 只把成绩表格交回 Java；学校提示没权限时返回 __NOPERM__，没找到表返回 NOTABLE。
     */
    public static String extractionJs() {
        return "(function(){" +
                "try{" +
                "var MAX_PAGES=" + MAX_PAGES + ";" +
                "function pick(doc){var t=doc&&doc.getElementById('dataList');return t?t.outerHTML:'';}" +
                "function totalPages(doc){" +
                "if(!doc||!doc.body)return 1;" +
                "var txt=doc.body.textContent||'';" +
                "var m=txt.match(/共\\s*(\\d+)\\s*页/)||txt.match(/\\/\\s*(\\d+)\\s*页/);" +
                "if(m){var n=parseInt(m[1],10);if(n>1)return Math.min(n,MAX_PAGES);}" +
                "return 1;}" +
                "function postPage(url,page){" +
                "var x=new XMLHttpRequest();x.open('POST',url,false);" +
                "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
                "var q='kksj=&kcxz=&kcmc=&xsfs=all';" +
                "if(page>1)q+='&pageNum='+page+'&pageIndex='+page;" +
                "x.send(q);return x.responseText||'';}" +
                "var r=pick(document);if(r&&location.search.indexOf('kksj=')<0&&document.body" +
                "&&/cjcx_list/.test(location.pathname)&&totalPages(document)<=1)return r;" +
                "var p=location.pathname;var i=p.indexOf('/jsxsd/');" +
                "var base=i>=0?p.substring(0,i+7):'/jsxsd/';" +
                "var url=base+'kscj/cjcx_list';" +
                "var txt=postPage(url,1);" +
                "try{var d=new DOMParser().parseFromString(txt,'text/html');var r2=pick(d);" +
                "if(r2){" +
                "var pages=totalPages(d);var tables=[r2];var seen={};seen[r2]=true;" +
                "for(var pg=2;pg<=pages&&pg<=MAX_PAGES;pg++){" +
                "try{var t2=postPage(url,pg);var d2=new DOMParser().parseFromString(t2,'text/html');" +
                "var rp=pick(d2);if(!rp||seen[rp])break;seen[rp]=true;tables.push(rp);}catch(e){break;}}" +
                "return tables.join('\\n');}" +
                "var b=(d.body&&d.body.textContent)||'';if(/无(操作)?权限|未开放|评教/.test(b)&&b.length<2000)return '__NOPERM__';}catch(e){}" +
                "if(r)return r;" +
                "return 'NOTABLE';" +
                "}catch(e){return 'ERR:'+e;}" +
                "})()";
    }

    /** 解析一个或多个 #dataList 表格（支持分页合并与重复行去重）。永不抛异常；失败返回空 Grades。 */
    public static Grades parseHtml(String html) {
        Grades out = new Grades();
        if (html == null || html.isEmpty()) return out;
        try {
            List<String> tables = new ArrayList<>();
            Matcher tm = TABLE.matcher(html);
            while (tm.find() && tables.size() < MAX_PAGES) {
                tables.add(tm.group(1));
            }
            if (tables.isEmpty()) tables.add(html);

            Map<String, Grades.SemesterGrades> bySem = new LinkedHashMap<>();
            java.util.Set<String> seenRows = new java.util.HashSet<>();
            int rowCount = 0;

            for (String table : tables) {
                int[] col = null;
                Matcher rm = TR.matcher(table);
                while (rm.find() && rowCount < MAX_ROWS) {
                    List<String> cells = cells(rm.group(1));
                    if (cells.isEmpty()) continue;
                    if (col == null) {
                        col = header(cells);
                        continue;
                    }
                    String name = get(cells, col[C_NAME]);
                    if (name.isEmpty() || "课程名称".equals(name)) continue;
                    String score = get(cells, col[C_SCORE]);
                    String code = get(cells, col[C_CODE]);
                    String examType = get(cells, col[C_EXAM]);

                    String termRaw = get(cells, col[C_TERM]);
                    Matcher term = TERM_ID.matcher(termRaw);
                    String semId;
                    String semName;
                    if (term.find()) {
                        semId = term.group(1) + "-" + term.group(2) + "-" + term.group(3);
                        semName = term.group(1) + "-" + term.group(2) + " 学年第" + term.group(3) + "学期";
                    } else {
                        semId = termRaw.isEmpty() ? "未知学期" : termRaw;
                        semName = semId;
                    }
                    String dedupeKey = semId + "|" + (code.isEmpty() ? name : code) + "|" + score + "|" + examType;
                    if (!seenRows.add(dedupeKey)) continue;
                    rowCount++;

                    Grades.SemesterGrades sem = bySem.get(semId);
                    if (sem == null) {
                        sem = new Grades.SemesterGrades();
                        sem.id = semId;
                        sem.name = semName;
                        bySem.put(semId, sem);
                    }

                    Grades.CourseGrade cg = new Grades.CourseGrade();
                    cg.code = code;
                    cg.name = name;
                    cg.score = score;
                    cg.scoreValue = Grades.tryParseDouble(score);
                    Double credit = Grades.tryParseDouble(get(cells, col[C_CREDIT]));
                    cg.credit = credit == null ? 0 : credit;
                    cg.gradePoint = Grades.tryParseDouble(get(cells, col[C_GP]));
                    if (cg.gradePoint == null) cg.gradePoint = levelPoint(score, cg.scoreValue);
                    cg.nature = get(cells, col[C_NATURE]);
                    cg.category = get(cells, col[C_ATTR]);
                    cg.examType = examType;
                    cg.college = get(cells, col[C_COLLEGE]);
                    cg.teacher = get(cells, col[C_TEACHER]);
                    cg.isPassed = Grades.evaluatePassed(score, cg.scoreValue);
                    sem.courses.add(cg);
                }
            }
            out.semesters.addAll(bySem.values());
            out.sortSemestersDescending();
            out.recomputeAll();
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static final int C_TERM = 0, C_CODE = 1, C_NAME = 2, C_SCORE = 3, C_CREDIT = 4, C_GP = 5,
            C_NATURE = 6, C_ATTR = 7, C_EXAM = 8, C_COLLEGE = 9, C_TEACHER = 10;

    /** 表头 → 列号；没有的列是 -1。课程名称或成绩缺失时整表视为无效（返回全 -1，行全被跳过）。 */
    static int[] header(List<String> cells) {
        int[] c = new int[11];
        java.util.Arrays.fill(c, -1);
        for (int i = 0; i < cells.size(); i++) {
            String h = cells.get(i).replace(" ", "");
            if (c[C_TERM] < 0 && (h.contains("开课学期") || h.equals("学年学期") || h.equals("学期"))) c[C_TERM] = i;
            else if (c[C_CODE] < 0 && (h.contains("课程编号") || h.contains("课程代码") || h.equals("课程号"))) c[C_CODE] = i;
            else if (c[C_NAME] < 0 && h.contains("课程名称")) c[C_NAME] = i;
            else if (c[C_SCORE] < 0 && (h.equals("成绩") || h.equals("总成绩") || h.equals("总评成绩")
                    || h.equals("最终成绩") || h.equals("总评"))) c[C_SCORE] = i;
            else if (c[C_CREDIT] < 0 && h.equals("学分")) c[C_CREDIT] = i;
            else if (c[C_GP] < 0 && (h.equals("绩点") || h.equals("成绩绩点") || h.equals("学分绩点"))) c[C_GP] = i;
            else if (c[C_NATURE] < 0 && h.contains("课程性质")) c[C_NATURE] = i;
            else if (c[C_ATTR] < 0 && (h.contains("课程属性") || h.contains("课程类别"))) c[C_ATTR] = i;
            else if (c[C_EXAM] < 0 && (h.contains("考试性质") || h.contains("考核方式") || h.contains("成绩标识"))) c[C_EXAM] = i;
            else if (c[C_COLLEGE] < 0 && (h.contains("开课单位") || h.contains("开课学院"))) c[C_COLLEGE] = i;
            else if (c[C_TEACHER] < 0 && (h.contains("教师") || h.contains("任课老师"))) c[C_TEACHER] = i;
        }
        if (c[C_NAME] < 0 || c[C_SCORE] < 0) java.util.Arrays.fill(c, -1);
        return c;
    }

    /** 等级制成绩没有绩点列时按常见五级制估：优秀 4.5/良好 3.5/中等 2.5/及格 1.5/不及格 0。 */
    static Double levelPoint(String score, Double value) {
        if (value != null) return value < 60 ? 0.0 : Grades.round2(Math.min(5.0, Math.max(1.0, (value - 50) / 10)));
        if (score == null) return null;
        String s = score.trim();
        if (s.contains("不及格") || s.contains("不合格")) return 0.0;
        if (s.contains("优")) return 4.5;
        if (s.contains("良")) return 3.5;
        if (s.contains("中")) return 2.5;
        if (s.contains("及格") || s.contains("合格")) return 1.5;
        return null;
    }

    private static String get(List<String> cells, int i) {
        return i >= 0 && i < cells.size() ? cells.get(i) : "";
    }

    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        Matcher m = CELL.matcher(row);
        while (m.find()) out.add(QiangzhiSource.text(m.group(2)));
        return out;
    }
}
