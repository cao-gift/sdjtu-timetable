package com.ecut.campus.data;

import java.util.List;
import java.util.Locale;

/**
 * GPA 目标模拟器与预估成绩计算（纯 Java，无 Android 依赖）。
 *
 * 绩点换算与 {@link QiangzhiGrades#levelPoint} 对齐：
 * 百分制 60 分 = 1.0 绩点，每多 1 分 +0.1 绩点，100 分 = 5.0 绩点（< 60 为 0.0）。
 */
public final class GpaSimulator {

    public static final double MAX_GP_SCALE = 5.0;

    private GpaSimulator() {}

    public static final class Baseline {
        public final double credits;
        public final double gpSum;
        public final double gpa;
        public final int courseCount;

        public Baseline(double credits, double gpSum, int courseCount) {
            this.credits = Math.max(0.0, credits);
            this.gpSum = Math.max(0.0, gpSum);
            this.gpa = this.credits > 0 ? round2(this.gpSum / this.credits) : 0.0;
            this.courseCount = Math.max(0, courseCount);
        }

        public static Baseline fromGrades(Grades grades) {
            if (grades == null) return new Baseline(0.0, 0.0, 0);
            double creditSum = 0.0;
            double gpSum = 0.0;
            int count = 0;
            for (Grades.SemesterGrades sem : grades.semesters) {
                if (sem == null || sem.courses == null) continue;
                for (Grades.CourseGrade c : sem.courses) {
                    if (c == null || c.gradePoint == null || c.credit <= 0.0) continue;
                    creditSum += c.credit;
                    gpSum += c.gradePoint * c.credit;
                    count++;
                }
            }
            return new Baseline(round2(creditSum), gpSum, count);
        }

        public static Baseline of(double currentGpa, double earnedCredits) {
            double cr = Math.max(0.0, earnedCredits);
            double gp = Math.max(0.0, Math.min(MAX_GP_SCALE, currentGpa));
            return new Baseline(round2(cr), gp * cr, cr > 0 ? 1 : 0);
        }
    }

    public static final class TargetResult {
        public enum Status { INVALID, REACHED, ACHIEVABLE, IMPOSSIBLE }

        public final Status status;
        public final double requiredGp;
        public final double requiredScore;
        public final String headline;
        public final String detail;

        public TargetResult(Status status, double requiredGp, double requiredScore,
                            String headline, String detail) {
            this.status = status;
            this.requiredGp = requiredGp;
            this.requiredScore = requiredScore;
            this.headline = headline;
            this.detail = detail;
        }
    }

    public static final class PlannedCourse {
        public final String name;
        public final double credit;
        public final double score;

        public PlannedCourse(String name, double credit, double score) {
            this.name = name == null ? "" : name.trim();
            this.credit = Math.max(0.0, credit);
            this.score = Math.max(0.0, Math.min(100.0, score));
        }

        public double gradePoint() {
            return scoreToGp(score);
        }
    }

    public static final class Projection {
        public final double projectedGpa;
        public final double delta;
        public final double addedCredits;
        public final double totalCredits;

        public Projection(double projectedGpa, double delta, double addedCredits, double totalCredits) {
            this.projectedGpa = projectedGpa;
            this.delta = delta;
            this.addedCredits = addedCredits;
            this.totalCredits = totalCredits;
        }
    }

    /** 百分制成绩转 5.0 制绩点（60 分起算 1.0，每 1 分 +0.1）。 */
    public static double scoreToGp(double score) {
        if (score < 60.0) return 0.0;
        return round2(Math.min(MAX_GP_SCALE, Math.max(1.0, (score - 50.0) / 10.0)));
    }

    /** 5.0 制绩点反推对应百分制均分（≤ 1.0 按及格线 60.0 计）。 */
    public static double gpToScore(double gp) {
        if (gp <= 0.0) return 0.0;
        if (gp <= 1.0) return 60.0;
        return round1(Math.min(100.0, gp * 10.0 + 50.0));
    }

    /** 反推：为了把总 GPA 达到 targetGpa，剩余 remainingCredits 学分平均需要多少绩点与分数。 */
    public static TargetResult computeTarget(Baseline base, double targetGpa, double remainingCredits) {
        if (base == null) base = new Baseline(0.0, 0.0, 0);
        if (targetGpa <= 0.0 || targetGpa > MAX_GP_SCALE || remainingCredits <= 0.0) {
            return new TargetResult(TargetResult.Status.INVALID, 0.0, 0.0,
                    "—", "请填写 0.1–5.0 的目标 GPA 与大于 0 的剩余学分");
        }
        double totalCredits = base.credits + remainingCredits;
        double needGp = (targetGpa * totalCredits - base.gpSum) / remainingCredits;
        if (needGp <= 0.0) {
            return new TargetResult(TargetResult.Status.REACHED, 0.0, 0.0,
                    "已超出目标",
                    String.format(Locale.US, "当前 %.2f（%.1f 学分）已稳超 %.2f，剩余 %.1f 学分正常及格即可",
                            base.gpa, base.credits, targetGpa, remainingCredits));
        }
        if (needGp > MAX_GP_SCALE + 1e-6) {
            double maxReachable = round2((base.gpSum + MAX_GP_SCALE * remainingCredits) / totalCredits);
            return new TargetResult(TargetResult.Status.IMPOSSIBLE, round2(needGp), 100.0,
                    "超出 5.0 上限",
                    String.format(Locale.US, "需均绩 %.2f，剩余 %.1f 学分全满分最高可达 %.2f",
                            needGp, remainingCredits, maxReachable));
        }
        double rGp = round2(needGp);
        double rScore = gpToScore(rGp);
        String scoreText = rGp < 1.0
                ? String.format(Locale.US, "平均 ≥ 60 分（均绩 %.2f）", rGp)
                : String.format(Locale.US, "平均 %.1f 分", rScore);
        String detail = String.format(Locale.US, "剩余 %.1f 学分需均绩 %.2f（按 5.0 绩点制估算）",
                remainingCredits, rGp);
        return new TargetResult(TargetResult.Status.ACHIEVABLE, rGp, rScore, scoreText, detail);
    }

    /** 按一组预估课程实时推算合并后的总 GPA。 */
    public static Projection project(Baseline base, List<PlannedCourse> planned) {
        if (base == null) base = new Baseline(0.0, 0.0, 0);
        double addCr = 0.0;
        double addGp = 0.0;
        if (planned != null) {
            for (PlannedCourse c : planned) {
                if (c == null || c.credit <= 0.0) continue;
                addCr += c.credit;
                addGp += c.gradePoint() * c.credit;
            }
        }
        double totalCr = base.credits + addCr;
        double nextGpa = totalCr > 0.0 ? round2((base.gpSum + addGp) / totalCr) : 0.0;
        double delta = base.credits > 0.0 ? round2(nextGpa - base.gpa) : 0.0;
        return new Projection(nextGpa, delta, round2(addCr), round2(totalCr));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
