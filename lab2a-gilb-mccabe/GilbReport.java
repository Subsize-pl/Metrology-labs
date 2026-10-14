import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Текстовое представление результата: расчёт «как вручную» по формулам
 * методики и таблицы для консоли. Используется и окном программы, и
 * консольным режимом, чтобы цифры в обоих местах всегда совпадали.
 */
public final class GilbReport {
    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    private GilbReport() {
    }

    /** Число с десятичной запятой, как в методичке (0,36). */
    public static String ratio(double value) {
        return String.format(RU, "%.4f", value);
    }

    /** Расчёт CL, N, cl, CLI и Z с подстановкой найденных значений. */
    public static String calculation(GilbMetrics metrics) {
        int cl = metrics.absoluteComplexity();
        int n = metrics.operatorCount();
        StringBuilder text = new StringBuilder();

        text.append("Абсолютная сложность (сумма ветвлений)\n");
        text.append("  CL  = ").append(branchingSum(metrics)).append(" = ").append(cl).append("\n\n");

        text.append("Общее число операторов языка (JLS, гл. 14)\n");
        text.append("  N   = ").append(operatorSum(metrics)).append(" = ").append(n).append("\n\n");

        text.append("Относительная сложность\n");
        if (n == 0) {
            text.append("  cl  = CL / N — не определена: операторов нет\n\n");
        } else {
            text.append("  cl  = CL / N = ").append(cl).append(" / ").append(n)
                    .append(" = ").append(ratio(metrics.relativeComplexity())).append("\n\n");
        }

        text.append("Максимальный уровень вложенности\n");
        text.append("  CLI = ").append(metrics.maxNesting()).append(deepestPlace(metrics)).append("\n\n");

        text.append("Цикломатическое число Маккейба: Z = CL + 1 для каждой подпрограммы\n");
        text.append("  Z   = ").append(cyclomaticSum(metrics)).append(" = ")
                .append(metrics.cyclomaticNumber()).append("\n");
        return text.toString();
    }

    private static String branchingSum(GilbMetrics metrics) {
        List<String> parts = new ArrayList<>();
        for (GilbMetrics.BranchingKind kind : GilbMetrics.BranchingKind.values()) {
            int count = metrics.countOf(kind);
            if (count > 0) {
                parts.add(kind.title() + " " + count);
            }
        }
        return parts.isEmpty() ? "0" : String.join(" + ", parts);
    }

    private static String operatorSum(GilbMetrics metrics) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<GilbMetrics.StatementKind, Integer> entry
                : metrics.statementCount().entrySet()) {
            parts.add(entry.getKey().title() + " " + entry.getValue());
        }
        return parts.isEmpty() ? "0" : String.join(" + ", parts);
    }

    private static String cyclomaticSum(GilbMetrics metrics) {
        if (metrics.methods().isEmpty()) {
            return "0";
        }
        return metrics.absoluteComplexity() + " (CL) + " + metrics.methods().size()
                + " (подпрограмм)";
    }

    private static String deepestPlace(GilbMetrics metrics) {
        for (GilbMetrics.Branching branching : metrics.branchings()) {
            if (branching.level() == metrics.maxNesting() && metrics.maxNesting() > 0) {
                return "  — самое глубокое: строка " + branching.line() + ", "
                        + branching.kind().title();
            }
        }
        return "";
    }

    /** Полный консольный отчёт: сводка, ветвления, подпрограммы, ошибки. */
    public static String console(GilbMetrics metrics, String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder text = new StringBuilder();
        text.append("Разбор: ").append(metrics.mode().title()).append("\n");
        for (GilbMetrics.SyntaxProblem problem : metrics.problems()) {
            text.append("  ! строка ").append(problem.line()).append(": ")
                    .append(problem.message()).append("\n");
        }
        text.append("\nВЕТВЛЕНИЯ\n");
        text.append(String.format("%4s %7s  %-9s %7s  %s%n", "№", "строка", "вид", "уровень", "код"));
        int index = 1;
        for (GilbMetrics.Branching branching : metrics.branchings()) {
            String code = branching.line() <= lines.length
                    ? lines[branching.line() - 1].strip() : "";
            text.append(String.format("%4d %7d  %-9s %7d  %s%n", index++, branching.line(),
                    branching.kind().title(), branching.level(), code));
        }
        text.append("\nОПЕРАТОРЫ ПО ВИДАМ\n");
        for (Map.Entry<GilbMetrics.StatementKind, Integer> entry
                : metrics.statementCount().entrySet()) {
            text.append(String.format("  %-62s JLS %-7s %4d%n", entry.getKey().description(),
                    entry.getKey().section(), entry.getValue()));
        }
        text.append("\nПОДПРОГРАММЫ\n");
        text.append(String.format("  %-34s %7s %9s %4s %11s %4s%n",
                "имя", "строка", "операторов", "CL", "вложенность", "Z"));
        for (GilbMetrics.MethodStats method : metrics.methods()) {
            text.append(String.format("  %-34s %7d %9d %4d %11d %4d%n", method.name(),
                    method.line(), method.operators(), method.branchings(),
                    method.maxNesting(), method.cyclomaticNumber()));
        }
        text.append("\nРАСЧЁТ\n").append(calculation(metrics));
        return text.toString();
    }
}
