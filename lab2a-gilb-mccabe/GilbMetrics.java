import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Результат анализа: метрики Джилба и Маккейба вместе с данными, из которых
 * они получены (список ветвлений, операторы по видам, разбивка по методам).
 *
 * @param options        параметры подсчёта, с которыми получен результат
 * @param mode           как интерпретирован исходный текст
 * @param operatorCount  N — общее число операторов языка (JLS, гл. 14)
 * @param branchings     все ветвления в порядке следования в тексте; CL = размер
 * @param statementCount число операторов по видам; сумма значений равна N
 * @param methods        статистика по подпрограммам
 * @param problems       синтаксические ошибки, найденные парсером
 */
public record GilbMetrics(
        GilbOptions options,
        ParseMode mode,
        int operatorCount,
        List<Branching> branchings,
        Map<StatementKind, Integer> statementCount,
        List<MethodStats> methods,
        List<SyntaxProblem> problems) {

    public GilbMetrics {
        branchings = List.copyOf(branchings);
        EnumMap<StatementKind, Integer> counts = new EnumMap<>(StatementKind.class);
        counts.putAll(statementCount);
        statementCount = Collections.unmodifiableMap(counts);
        methods = List.copyOf(methods);
        problems = List.copyOf(problems);
    }

    /** CL — абсолютная сложность: число ветвлений. */
    public int absoluteComplexity() {
        return branchings.size();
    }

    /** cl = CL / N — относительная сложность; 0, если операторов нет. */
    public double relativeComplexity() {
        return operatorCount == 0 ? 0.0 : (double) absoluteComplexity() / operatorCount;
    }

    /**
     * CLI — максимальный уровень вложенности ветвлений. Уровень не вложенного
     * ветвления равен 0 (так в методике: switch с n ветвями даёт n − 2).
     */
    public int maxNesting() {
        return branchings.stream().mapToInt(Branching::level).max().orElse(0);
    }

    /** Сумма цикломатических чисел Маккейба Z = CL + 1 всех подпрограмм. */
    public int cyclomaticNumber() {
        return methods.stream().mapToInt(MethodStats::cyclomaticNumber).sum();
    }

    /** Сколько ветвлений заданного вида найдено. */
    public int countOf(BranchingKind kind) {
        return (int) branchings.stream().filter(b -> b.kind() == kind).count();
    }

    /** Вид ветвления: то, что увеличивает абсолютную сложность CL. */
    public enum BranchingKind {
        IF("if"),
        WHILE("while"),
        DO_WHILE("do-while"),
        FOR("for"),
        FOR_EACH("for-each"),
        CASE("case"),
        TERNARY("?:");

        private final String title;

        BranchingKind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** Вид оператора языка Java по JLS, гл. 14: то, из чего складывается N. */
    public enum StatementKind {
        DECLARATION("объявление", "локальной переменной", "14.4"),
        EXPRESSION("выражение", "присваивание, ++/--, вызов метода, new", "14.8"),
        IF("if", "с веткой else или без неё", "14.9"),
        SWITCH("switch", "операторы в ветках считаются отдельно", "14.11"),
        WHILE("while", "", "14.12"),
        DO_WHILE("do-while", "", "14.13"),
        FOR("for", "заголовок — часть оператора", "14.14.1"),
        FOR_EACH("for-each", "", "14.14.2"),
        BREAK("break", "", "14.15"),
        CONTINUE("continue", "", "14.16"),
        RETURN("return", "", "14.17"),
        THROW("throw", "", "14.18"),
        TRY("try", "catch и finally — части оператора", "14.20"),
        SYNCHRONIZED("synchronized", "", "14.19"),
        ASSERT("assert", "", "14.10"),
        YIELD("yield", "", "14.21"),
        LABELED("помеченный", "метка: оператор", "14.7"),
        EMPTY("пустой", "одиночная ;", "14.6"),
        BLOCK("блок", "{ } как оператор", "14.2");

        private final String title;
        private final String note;
        private final String section;

        StatementKind(String title, String note, String section) {
            this.title = title;
            this.note = note;
            this.section = section;
        }

        /** Короткое название для формул: «выражение», «if», «while». */
        public String title() {
            return title;
        }

        /** Название с пояснением для таблиц. */
        public String description() {
            return note.isEmpty() ? title : title + " (" + note + ")";
        }

        /** Номер раздела JLS, описывающего этот вид оператора. */
        public String section() {
            return section;
        }
    }

    /** Как анализатор понял исходный текст. */
    public enum ParseMode {
        COMPILATION_UNIT("весь файл"),
        CLASS_MEMBERS("члены класса (фрагмент обёрнут в class)"),
        STATEMENTS("операторы (фрагмент обёрнут в метод)");

        private final String title;

        ParseMode(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /**
     * Одно ветвление.
     *
     * @param kind  вид
     * @param line  строка в исходном тексте (с 1)
     * @param level число ветвлений, внутри которых оно находится (внешнее — 0)
     */
    public record Branching(BranchingKind kind, int line, int level) {
    }

    /**
     * Статистика подпрограммы.
     *
     * @param name        имя с параметрами
     * @param line        строка объявления
     * @param operators   число операторов в теле
     * @param branchings  число ветвлений (CL подпрограммы)
     * @param maxNesting  максимальный уровень вложенности
     */
    public record MethodStats(String name, int line, int operators,
                              int branchings, int maxNesting) {

        /** Z = CL + 1: цикломатическое число графа подпрограммы. */
        public int cyclomaticNumber() {
            return branchings + 1;
        }
    }

    /** Синтаксическая ошибка исходного текста. */
    public record SyntaxProblem(int line, String message) {
    }
}
