import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Проверки анализатора — от одного оператора до случайных программ.
 * Запуск из каталога лабораторной: {@code java -cp build GilbAnalyzerTest [-v]}.
 * Код возврата 0 — всё прошло, 1 — есть расхождения.
 */
public final class GilbAnalyzerTest {
    private static final GilbAnalyzer ANALYZER = new GilbAnalyzer();
    private static final String REFERENCE_PROGRAM = "NumberReport.java";

    private static boolean verbose;
    private static int totalChecks;
    private static int totalFailures;
    private static String sectionName;
    private static int sectionChecks;
    private static int sectionFailures;

    public static void main(String[] args) throws IOException {
        for (String arg : args) {
            if (arg.startsWith("--dump=")) { // показать случайную программу для отладки
                RandomProgram program = RandomProgram.generate(Long.parseLong(arg.substring(7)));
                System.out.println(program.source());
                System.out.printf("// ожидается: N=%d CL=%d CLI=%d методов=%d%n", program.operators(),
                        program.branchings(), program.maxNesting(), program.methods());
                return;
            }
        }
        verbose = List.of(args).contains("-v");
        long started = System.nanoTime();

        specificationExamples();
        statementKinds();
        branchingsAndNesting();
        switchRules();
        options();
        lexicalRobustness();
        languageFeatures();
        fragmentsAndErrors();
        referenceProgram();
        randomPrograms();
        stress();
        endSection();

        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("%nПроверок: %d, расхождений: %d, время: %d мс%n",
                totalChecks, totalFailures, millis);
        System.exit(totalFailures == 0 ? 0 : 1);
    }

    // ================================================================ примеры

    /** Числа из методички (рис. 2 и 3: CL = 4, N = 11, cl = 0,36, CLI = 3). */
    private static void specificationExamples() {
        section("Примеры из методички");

        // Рис. 3: цепочка из четырёх if. N = 11: ввод, 4 if, 5 присваиваний, вывод.
        String figure3 = """
                class Figure3 {
                    double y;
                    void run(java.util.Scanner in) {
                        double x = in.nextDouble();
                        if (x < 0) {
                            y = 0;
                        } else if (x == 0) {
                            y = 1;
                        } else if (x < 0.5) {
                            y = 2;
                        } else if (x < 1) {
                            y = 3;
                        } else {
                            y = 4;
                        }
                        System.out.println(y);
                    }
                }
                """;
        expect("рис. 3: CL = 4, N = 11, CLI = 3", figure3, 4, 11, 3);
        GilbMetrics metrics = analyze(figure3);
        checkEquals("рис. 3: cl = 4/11 ≈ 0,36", 0.36, Math.round(metrics.relativeComplexity() * 100) / 100.0);

        // Рис. 2: switch с пятью ветвями эквивалентен 4 вложенным if (n − 1), глубина n − 2 = 3.
        expect("рис. 2: switch из 5 ветвей → CL = 4, CLI = 3", """
                class Figure2 {
                    double y;
                    void run(int code) {
                        switch (code) {
                            case 0 -> y = 0;
                            case 1 -> y = 1;
                            case 2 -> y = 2;
                            case 3 -> y = 3;
                            default -> y = 4;
                        }
                    }
                }
                """, 4, 6, 3);

        // Рис. 1: цикл и следующий за ним if. В тексте программы они идут друг за другом.
        expect("рис. 1: while, затем if → CL = 2, вложенность 0", inMethod("""
                while (a > 0) { a--; }
                if (c) { b++; } else { b--; }
                """), 2, 5, 0);
    }

    // ============================================================ операторы

    private static void statementKinds() {
        section("Виды операторов (JLS, гл. 14)");
        expectN("объявление без инициализатора", "int x;", 1);
        expectN("объявление с инициализатором", "int x = 1;", 1);
        expectN("int a1 = 1, b1 = 2, c1; — один оператор", "int a1 = 1, b1 = 2, c1;", 1);
        expectN("int x = 1; int y = 2; — два оператора", "int x = 1; int y = 2;", 2);
        expectN("объявление массивов", "int[] x = {1, 2}, y = new int[3];", 1);
        expectN("var", "var text = \"abc\";", 1);
        expectN("присваивание и составное присваивание", "a = 1; a += 2;", 2);
        expectN("++ и --", "a++; --a;", 2);
        expectN("вызов метода", "System.out.println(a);", 1);
        expectN("new как оператор", "new Object();", 1);
        expectN("return без значения", "return;", 1);
        expectN("throw", "throw new IllegalStateException();", 1);
        expectN("try-catch-finally: try + 3 оператора внутри",
                "try { a++; } catch (RuntimeException e) { b++; } finally { a--; }", 4);
        expectN("try-with-resources: ресурс не оператор",
                "try (java.io.StringReader r = new java.io.StringReader(\"\")) { a++; }", 2);
        expectN("synchronized", "synchronized (this) { a++; }", 2);
        expectN("assert", "assert a > 0;", 1);
        expectN("пустой оператор", ";", 1);
        expectN("помеченный оператор: метка + for + break", "outer: for (;;) { break outer; }", 3);
        expectN("заголовок for — часть оператора for",
                "for (int i = 0, j = 5; i < j; i++, j--) { }", 1);
        expectN("for-each: оператор + тело", "for (int v : items) { a += v; }", 2);
        expectN("for без скобок: тело — отдельный оператор", "for (int i = 0; i < a; i++) b++;", 2);
        expectN("for-each без скобок", "for (int v : items) b += v;", 2);
        expectN("while без скобок", "while (a > 0) a--;", 2);
        expectN("do-while без скобок", "do a--; while (a > 0);", 2);
        expectN("блок сам по себе не оператор", "{ a++; }", 1);
        expectN("локальный класс: считаются операторы его методов",
                "class Local { void g() { System.out.println(); } }", 1);
        expectN("лямбда с блоком: объявление + 2 оператора",
                "Runnable r = () -> { System.out.println(); System.out.println(); };", 3);
        expectN("лямбда-выражение: тело — не оператор",
                "Runnable r = () -> System.out.println();", 1);
        expectN("анонимный класс: объявление + оператор метода",
                "Runnable r = new Runnable() { public void run() { System.out.println(); } };", 2);

        expect("поля и параметры — не операторы",
                "class A { int x = 5; static final int Y = 6; void f(int a, int b) { } }", 0, 0, 0);
        expect("блоки инициализации",
                "class A { static { System.out.println(); } { System.out.println(); } }", 0, 2, 0);
        expect("конструктор: super() и присваивание",
                "class A { int x; A() { super(); x = 1; } }", 0, 2, 0);
        expect("интерфейс: метод без тела не даёт операторов",
                "interface I { int f(int a); default int g() { return 1; } }", 0, 1, 0);
    }

    // ===================================================== ветвления, вложенность

    private static void branchingsAndNesting() {
        section("Ветвления и вложенность");
        expectBody("if без else", "if (a > 0) { b++; }", 1, 2, 0);
        expectBody("if-else — одно ветвление", "if (a > 0) { b++; } else { b--; }", 1, 3, 0);
        expectBody("else if ×3: уровни 0, 1, 2",
                "if (a == 1) b++; else if (a == 2) b--; else if (a == 3) a++; else a--;", 3, 7, 2);
        expectBody("последовательные if не вкладываются", "if (a > 0) b++; if (b > 0) a++;", 2, 4, 0);
        expectBody("if в if", "if (a > 0) { if (b > 0) { a++; } }", 2, 3, 1);
        expectBody("висячий else относится к ближайшему if",
                "if (a > 0) if (b > 0) a++; else b++;", 2, 4, 1);
        expectBody("while", "while (a > 0) { a--; }", 1, 2, 0);
        expectBody("do-while", "do { a--; } while (a > 0);", 1, 2, 0);
        expectBody("for", "for (int i = 0; i < b; i++) { a += i; }", 1, 2, 0);
        expectBody("for(;;) — тоже цикл", "for (;;) { break; }", 1, 2, 0);
        expectBody("while(true) — тоже цикл", "while (true) { break; }", 1, 2, 0);
        expectBody("цикл в цикле",
                "for (int i = 0; i < a; i++) { for (int j = 0; j < b; j++) { a++; } }", 2, 3, 1);
        expectBody("if → for-each → if",
                "if (c) { for (int v : items) { if (v > 0) { a++; } } }", 3, 4, 2);
        expectBody("шесть вложенных if → CLI = 5",
                "if (c) { if (c) { if (c) { if (c) { if (c) { if (c) { a++; } } } } } }", 6, 7, 5);
        expectBody("тело do-while вложено, условие — нет",
                "do { if (c) { a++; } } while (a < 10);", 2, 3, 1);
        expectBody("прямолинейный код: CL = 0, CLI = 0", "a = 1; b = 2; a = a + b;", 0, 3, 0);
        expectBody("&& и || не ветвления", "if (a > 0 && b > 0 || c) { a++; }", 1, 2, 0);
        expectBody("catch и finally не ветвления",
                "try { a++; } catch (Exception e) { a--; } finally { b++; }", 0, 4, 0);
    }

    // ================================================================ switch

    private static void switchRules() {
        section("switch: n − 1 условий, глубина n − 2; default и селектор не считаются");
        expectBody("4 метки + default (n = 5): CL = 4, CLI = 3", """
                switch (a) { case 1: b = 1; break; case 2: b = 2; break;
                             case 3: b = 3; break; case 4: b = 4; break; default: b = 0; }
                """, 4, 10, 3);
        expectBody("4 метки без default: то же CL", """
                switch (a) { case 1: b = 1; break; case 2: b = 2; break;
                             case 3: b = 3; break; case 4: b = 4; break; }
                """, 4, 9, 3);
        expectBody("только default: условий нет", "switch (a) { default: b = 0; }", 0, 2, 0);
        expectBody("пустой switch", "switch (a) { }", 0, 1, 0);
        expectBody("одна метка без default ≡ один if", "switch (a) { case 1: b = 1; }", 1, 2, 0);
        expectBody("стопка меток: каждая метка — условие", """
                switch (a) { case 1: case 2: case 3: b = 1; break; default: b = 0; }
                """, 3, 4, 2);
        expectBody("метки через запятую в стрелочной форме", """
                switch (a) { case 1, 2, 3 -> b = 1; default -> b = 0; }
                """, 3, 3, 2);
        expectBody("switch-выражение", "int r = switch (a) { case 1 -> 10; case 2 -> 20; default -> 0; };",
                2, 1, 1);
        expectBody("switch-выражение с yield",
                "int r = switch (a) { case 1 -> { yield 5; } default -> 0; };", 1, 2, 0);
        expectBody("default в середине: его тело — последний else цепочки", """
                switch (a) { case 1: b = 1; break; default: b = 0; break;
                             case 2: if (c) { b = 2; } }
                """, 3, 7, 2);
        expectBody("if в теле default, стоящего между метками: уровень = число меток", """
                switch (a) { case 1: b = 1; break; default: if (c) { b = 0; } break;
                             case 2: b = 2; break; }
                """, 3, 8, 2);
        expectBody("if в ветках лежат на разной глубине цепочки", """
                switch (a) { case 1: if (c) { b = 1; } break;
                             case 2: if (c) { b = 2; } break;
                             default: if (c) { b = 3; } }
                """, 5, 9, 2);
        expectBody("switch в цикле в if: уровни 2 и 3", """
                if (c) { for (int v : items) {
                    switch (v) { case 1: a++; break; case 2: a--; break; } } }
                """, 4, 7, 3);
        expectBody("вложенный switch начинается с уровня своей ветки", """
                switch (a) { case 1: switch (b) { case 1: a++; break; case 2: a--; break; }
                             break; case 2: a = 0; }
                """, 4, 8, 2);
        expectBody("switch по строке", "switch (s) { case \"a\": a++; break; default: a--; }", 1, 4, 0);
        expectBody("case null, default", "switch (s) { case \"x\" -> a--; case null, default -> a++; }",
                2, 3, 1);
        expectBody("образцы с охраной: охрана — не отдельное условие", """
                switch (o) { case Integer i when i > 5 -> a++; case Integer i -> a--;
                             case String t -> b++; default -> b--; }
                """, 3, 5, 2);
    }

    // ============================================================ настройки

    private static void options() {
        section("Настройки подсчёта");
        String loopWithIf = inMethod("for (int i = 0; i < a; i++) { if (c) { b++; } }");
        expect("циклы — ветвления (по умолчанию)", loopWithIf, 2, 3, 1);
        expect("циклы не считаются ветвлениями", loopWithIf,
                new GilbOptions(false, false, true, false), 1, 3, 0);

        expect("?: по умолчанию не считается", inMethod("a = c ? 1 : 2;"), 0, 1, 0);
        GilbOptions ternary = new GilbOptions(true, true, true, false);
        expect("?: как ветвление", inMethod("a = c ? 1 : 2;"), ternary, 1, 1, 0);
        expect("вложенный ?: даёт вложенность", inMethod("a = c ? (b > 0 ? 1 : 2) : 3;"),
                ternary, 2, 1, 1);

        String declarations = inMethod("int x = 1; int y = 2; a = x + y;");
        expect("объявления считаются операторами", declarations, 0, 3, 0);
        expect("объявления не считаются", declarations,
                new GilbOptions(true, false, false, false), 0, 1, 0);

        GilbOptions blocks = new GilbOptions(true, false, true, true);
        expect("блок if как оператор: if + блок + оператор", inMethod("if (c) { b++; }"), blocks, 1, 3, 0);
        expect("отдельный блок как оператор", inMethod("{ a++; }"), blocks, 0, 2, 0);
        expect("блоки try/finally и тело метода — не операторы",
                inMethod("try { a++; } finally { b++; }"), blocks, 0, 3, 0);
        expect("тело стрелочной ветки — не оператор",
                inMethod("switch (a) { case 1 -> { b++; } default -> b--; }"), blocks, 1, 3, 0);
        expect("блок в ветке с двоеточием — оператор",
                inMethod("switch (a) { case 1: { b++; } }"), blocks, 1, 3, 0);
    }

    // ======================================================== устойчивость

    private static void lexicalRobustness() throws IOException {
        section("Устойчивость: комментарии, строки, имена, кодировка файла");
        expect("ключевые слова в комментариях, строках, символах, текстовых блоках", """
                class A {
                    void f() {
                        // if (x) while (y) for (;;) switch (z) {}
                        /* if (a) { } else if (b) { } do { } while (c); */
                        String s = "if (a) while (b) for (c) switch (d)";
                        char c = 'x';
                        String t = \"\"\"
                                if (x) { switch (y) { case 1: } }
                                \"\"\";
                    }
                }
                """, 0, 3, 0);
        expect("имена, содержащие ключевые слова", inMethod("int iffy = 1; int whileLoop = 2; int format = 3;"),
                0, 3, 0);
        expect("\\u-последовательности обрабатываются как в javac",
                "class A { void f(int x) { \\u0069f (x > 0) { x++; } } }", 1, 2, 0);
        char byteOrderMark = 0xFEFF;
        expect("BOM в начале файла",
                byteOrderMark + "class A { void f(boolean x) { if (x) { } } }", 1, 1, 0);
        expect("CRLF и табуляция",
                "class A {\r\n\tvoid f(boolean x) {\r\n\t\tif (x) {\r\n\t\t\tx = false;\r\n\t\t}\r\n\t}\r\n}\r\n",
                1, 2, 0);
        expect("русские идентификаторы и комментарии", """
                class Пример {
                    // условие: если число положительное
                    void проверить(int число) { if (число > 0) { число--; } }
                }
                """, 1, 2, 0);

        Path file = Files.createTempFile("gilb-encoding", ".java");
        try {
            String text = "// комментарий\nclass A { void f(boolean x) { if (x) { } } }\n";
            Files.writeString(file, text, StandardCharsets.UTF_8);
            checkEquals("файл в UTF-8 читается без искажений", text, GilbAnalyzerApp.readSource(file));
            Files.write(file, text.getBytes(Charset.forName("windows-1251")));
            checkEquals("файл в Windows-1251 читается без искажений", text,
                    GilbAnalyzerApp.readSource(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void languageFeatures() {
        section("Возможности языка");
        expect("дженерики и приведения", inMethod(
                "java.util.Map<String, java.util.List<Integer>> m = new java.util.HashMap<>();"
                        + " Object x = (Comparable<String>) s;"), 0, 2, 0);
        expect("лямбда: операторы внутри считаются", inMethod(
                "java.util.List<Integer> list = new java.util.ArrayList<>();"
                        + " list.forEach(x -> { if (x > 0) { System.out.println(x); } });"), 1, 4, 0);
        expect("лямбда внутри if: вложенность лексическая", inMethod(
                "if (c) { Runnable r = () -> { if (c) { a++; } }; }"), 2, 4, 1);
        expect("аннотации не операторы", """
                class A { @SuppressWarnings("unchecked") @Deprecated void f() { } }
                """, 0, 0, 0);
        expect("record, enum с телом константы, интерфейс с default", """
                record P(int x, int y) {
                    P { if (x < 0) throw new IllegalArgumentException(); }
                    int sum() { return x + y; }
                }
                enum Color { RED, GREEN { void f() { if (true) { } } }; void f() { } }
                interface I { default int g(int a) { if (a > 0) return 1; return 0; } }
                """, 3, 7, 0);
        expect("вложенные и статические классы", """
                class Outer {
                    static class Inner { int f(int a) { while (a > 0) { a--; } return a; } }
                    class Deep { void g(boolean c) { if (c) { return; } } }
                }
                """, 2, 5, 0);
        expect("несколько классов в файле", """
                class One { void f(boolean c) { if (c) { } } }
                class Two { void g(boolean c) { while (c) { c = false; } } }
                """, 2, 3, 0);
    }

    // ============================================== неполный и неверный код

    private static void fragmentsAndErrors() {
        section("Фрагменты кода и синтаксические ошибки");
        GilbMetrics method = analyze("int f(int x) { if (x > 0) { return 1; } return 0; }");
        checkEquals("метод без класса: режим", GilbMetrics.ParseMode.CLASS_MEMBERS, method.mode());
        checkEquals("метод без класса: CL", 1, method.absoluteComplexity());
        checkEquals("метод без класса: N", 3, method.operatorCount());
        checkEquals("метод без класса: ошибок нет", 0, method.problems().size());

        GilbMetrics statements = analyze("int a = 5; if (a > 3) { a++; } else { a--; }");
        checkEquals("операторы без метода: режим", GilbMetrics.ParseMode.STATEMENTS, statements.mode());
        checkEquals("операторы без метода: CL", 1, statements.absoluteComplexity());
        checkEquals("операторы без метода: N", 4, statements.operatorCount());

        GilbMetrics empty = analyze("");
        checkEquals("пустой текст: N", 0, empty.operatorCount());
        checkEquals("пустой текст: CL", 0, empty.absoluteComplexity());
        checkEquals("пустой текст: cl", 0.0, empty.relativeComplexity());
        checkEquals("пустой текст: CLI", 0, empty.maxNesting());
        checkEquals("пустой текст: ошибок нет", 0, empty.problems().size());

        GilbMetrics comments = analyze("// только комментарий\n/* и ещё один */\n");
        checkEquals("только комментарии: N", 0, comments.operatorCount());
        checkEquals("null как пустой текст", 0, ANALYZER.analyze(null).operatorCount());

        GilbMetrics broken = analyze("class A { void f(boolean x) { if (x { x = false; } } }");
        checkTrue("скобка не закрыта: ошибка сообщена", !broken.problems().isEmpty());
        checkTrue("скобка не закрыта: номер строки задан", broken.problems().get(0).line() >= 1);

        GilbMetrics unclosed = analyze("class A {\n void f(boolean x) {\n if (x) { x = false; }\n");
        checkTrue("не хватает }: ошибка сообщена", !unclosed.problems().isEmpty());
        checkEquals("не хватает }: ветвление всё равно найдено", 1, unclosed.absoluteComplexity());

        GilbMetrics garbage = analyze("привет, мир! @#$ %%%");
        checkTrue("мусор: ошибка сообщена, исключения нет", !garbage.problems().isEmpty());

        GilbMetrics lineCheck = analyze("class A {\n void f() {\n\n int x;\n if (x > 0 {\n }\n }\n}\n");
        checkEquals("ошибка указывает на строку 5", 5, lineCheck.problems().get(0).line());
    }

    // ===================================================== эталонная программа

    private static void referenceProgram() throws IOException {
        section("Анализируемая программа " + REFERENCE_PROGRAM);
        Path path = Path.of(REFERENCE_PROGRAM);
        if (!Files.isRegularFile(path)) {
            fail("файл " + REFERENCE_PROGRAM + " не найден — запускайте тест из каталога лабораторной");
            return;
        }
        String source = Files.readString(path);
        GilbMetrics metrics = analyze(source);

        // Значения получены вручную по тексту программы (см. README).
        checkEquals("N — общее число операторов", 56, metrics.operatorCount());
        checkEquals("CL — абсолютная сложность", 18, metrics.absoluteComplexity());
        checkEquals("cl = 18/56", 18.0 / 56, metrics.relativeComplexity());
        checkEquals("CLI — максимальная вложенность", 3, metrics.maxNesting());
        checkEquals("Z — сумма по 8 подпрограммам", 26, metrics.cyclomaticNumber());
        checkEquals("подпрограмм", 8, metrics.methods().size());
        checkEquals("ошибок синтаксиса нет", 0, metrics.problems().size());

        // Требования задания к коду.
        int lines = source.split("\n", -1).length - 1;
        checkTrue("размер 90–100 строк (сейчас " + lines + ")", lines >= 90 && lines <= 100);
        checkTrue("есть for", metrics.countOf(GilbMetrics.BranchingKind.FOR) > 0);
        checkTrue("есть for-each", metrics.countOf(GilbMetrics.BranchingKind.FOR_EACH) > 0);
        checkTrue("есть while", metrics.countOf(GilbMetrics.BranchingKind.WHILE) > 0);
        checkTrue("есть do-while", metrics.countOf(GilbMetrics.BranchingKind.DO_WHILE) > 0);
        checkTrue("есть if и else if", metrics.countOf(GilbMetrics.BranchingKind.IF) >= 3);
        checkTrue("есть switch с метками", metrics.countOf(GilbMetrics.BranchingKind.CASE) >= 3);
        checkTrue("есть пользовательские подпрограммы", metrics.methods().size() >= 3);
        for (GilbMetrics.StatementKind kind : List.of(GilbMetrics.StatementKind.SWITCH,
                GilbMetrics.StatementKind.BREAK, GilbMetrics.StatementKind.CONTINUE,
                GilbMetrics.StatementKind.RETURN, GilbMetrics.StatementKind.TRY)) {
            checkTrue("есть оператор " + kind.title(), metrics.statementCount().containsKey(kind));
        }
        checkInvariants("инварианты", metrics);
    }

    // ============================================================ случайные

    /**
     * Случайные программы с заранее известными метриками: генератор строит
     * дерево конструкций и сам считает N, CL и CLI по правилам методики, затем
     * печатает его в Java с произвольным оформлением (скобки, else if, шум в
     * комментариях и строках). Анализатор обязан получить те же числа.
     */
    private static void randomPrograms() {
        section("Случайные программы с известным ответом");
        int programs = 400;
        int mismatches = 0;
        RandomProgram smallest = null;
        for (int seed = 1; seed <= programs; seed++) {
            RandomProgram program = RandomProgram.generate(seed);
            GilbMetrics metrics = analyze(program.source());
            boolean same = metrics.problems().isEmpty()
                    && metrics.operatorCount() == program.operators()
                    && metrics.absoluteComplexity() == program.branchings()
                    && metrics.maxNesting() == program.maxNesting()
                    && metrics.methods().size() == program.methods();
            if (same) {
                pass("seed " + seed);
            } else if (mismatches++ < 3) {
                fail("seed " + seed + ": ожидалось N=" + program.operators() + " CL="
                        + program.branchings() + " CLI=" + program.maxNesting() + ", получено N="
                        + metrics.operatorCount() + " CL=" + metrics.absoluteComplexity()
                        + " CLI=" + metrics.maxNesting() + ", ошибок: " + metrics.problems());
            } else {
                fail("seed " + seed + ": расхождение (подробности выведены для первых трёх)");
            }
            if (!same && (smallest == null || program.source().length() < smallest.source().length())) {
                smallest = program;
            }
            checkInvariants("seed " + seed + " инварианты", metrics);
        }
        if (smallest != null) {
            System.out.println("    Наименьшая программа с расхождением:\n" + smallest.source());
        }
    }

    private static void stress() {
        section("Нагрузка");
        StringBuilder flat = new StringBuilder("class Flat { void run(int a) {\n");
        for (int i = 0; i < 20_000; i++) {
            flat.append(i % 5 == 0 ? "  if (a > " + i + ") { a++; }\n" : "  a += " + i + ";\n");
        }
        flat.append("} }\n");
        long started = System.nanoTime();
        GilbMetrics metrics = analyze(flat.toString());
        long millis = (System.nanoTime() - started) / 1_000_000;
        checkEquals("20 000 строк: CL", 4_000, metrics.absoluteComplexity());
        checkEquals("20 000 строк: N", 20_000 + 4_000, metrics.operatorCount());
        checkTrue("20 000 строк за разумное время (" + millis + " мс)", millis < 15_000);

        int depth = 1_500;
        StringBuilder deep = new StringBuilder("class Deep { void run(boolean c) {\n");
        deep.append("if (c) { ".repeat(depth)).append("c = false;").append(" }".repeat(depth));
        deep.append("\n} }\n");
        GilbMetrics nested = analyze(deep.toString());
        checkEquals("вложенность 1500: CLI", depth - 1, nested.maxNesting());
        checkEquals("вложенность 1500: CL", depth, nested.absoluteComplexity());
    }

    // ============================================================= помощники

    private static GilbMetrics analyze(String source) {
        return ANALYZER.analyze(source);
    }

    private static String inMethod(String body) {
        return "class T {\n void m(int a, int b, boolean c, int[] items, String s, Object o) {\n"
                + body + "\n}\n}\n";
    }

    private static void expectBody(String name, String body, int cl, int n, int cli) {
        expect(name, inMethod(body), cl, n, cli);
    }

    private static void expectN(String name, String body, int n) {
        expect(name, inMethod(body), analyzeBranchings(body), n, analyzeNesting(body));
    }

    private static int analyzeBranchings(String body) {
        return analyze(inMethod(body)).absoluteComplexity();
    }

    private static int analyzeNesting(String body) {
        return analyze(inMethod(body)).maxNesting();
    }

    private static void expect(String name, String source, int cl, int n, int cli) {
        expect(name, source, GilbOptions.METHODOLOGY, cl, n, cli);
    }

    private static void expect(String name, String source, GilbOptions options,
                               int cl, int n, int cli) {
        GilbMetrics metrics = ANALYZER.analyze(source, options);
        boolean same = metrics.absoluteComplexity() == cl && metrics.operatorCount() == n
                && metrics.maxNesting() == cli && metrics.problems().isEmpty();
        if (same) {
            pass(name);
        } else {
            fail(name + ": ожидалось CL=" + cl + " N=" + n + " CLI=" + cli + ", получено CL="
                    + metrics.absoluteComplexity() + " N=" + metrics.operatorCount() + " CLI="
                    + metrics.maxNesting() + (metrics.problems().isEmpty()
                    ? "" : ", ошибки: " + metrics.problems()));
        }
        checkInvariants(name + " (инварианты)", metrics);
    }

    /** Связи между частями результата, которые обязаны выполняться всегда. */
    private static void checkInvariants(String name, GilbMetrics metrics) {
        int byKind = metrics.statementCount().values().stream().mapToInt(Integer::intValue).sum();
        int byMethod = metrics.methods().stream().mapToInt(GilbMetrics.MethodStats::operators).sum();
        int branchingsByMethod = metrics.methods().stream()
                .mapToInt(GilbMetrics.MethodStats::branchings).sum();
        int nestingByMethod = metrics.methods().stream()
                .mapToInt(GilbMetrics.MethodStats::maxNesting).max().orElse(0);
        boolean holds = byKind == metrics.operatorCount()
                && byMethod == metrics.operatorCount()
                && branchingsByMethod == metrics.absoluteComplexity()
                && nestingByMethod == metrics.maxNesting()
                && metrics.maxNesting() <= Math.max(0, metrics.absoluteComplexity() - 1);
        if (holds) {
            totalChecks++;
            sectionChecks++;
        } else {
            fail(name + ": нарушены связи N=" + metrics.operatorCount() + " по видам " + byKind
                    + ", по методам " + byMethod + "; CL по методам " + branchingsByMethod
                    + " при CL=" + metrics.absoluteComplexity());
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        if (expected.equals(actual)) {
            pass(name);
        } else {
            fail(name + ": ожидалось " + expected + ", получено " + actual);
        }
    }

    private static void checkEquals(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 1e-9) {
            pass(name);
        } else {
            fail(name + ": ожидалось " + expected + ", получено " + actual);
        }
    }

    private static void checkTrue(String name, boolean condition) {
        if (condition) {
            pass(name);
        } else {
            fail(name);
        }
    }

    private static void pass(String name) {
        totalChecks++;
        sectionChecks++;
        if (verbose) {
            System.out.println("    ok    " + name);
        }
    }

    private static void fail(String message) {
        totalChecks++;
        sectionChecks++;
        totalFailures++;
        sectionFailures++;
        System.out.println("    ОШИБКА " + message);
    }

    private static void section(String name) {
        endSection();
        sectionName = name;
        sectionChecks = 0;
        sectionFailures = 0;
        if (verbose) {
            System.out.println("\n" + name);
        }
    }

    private static void endSection() {
        if (sectionName != null) {
            System.out.printf("[%s] %s — %d проверок%n",
                    sectionFailures == 0 ? " OK " : "FAIL", sectionName, sectionChecks);
            sectionName = null;
        }
    }

    // ===================================================== генератор программ

    private sealed interface Node permits Simple, Sequence, Conditional, Loop, Choice {
    }

    /** Один оператор в одну строку. */
    private record Simple(String code) implements Node {
    }

    private record Sequence(List<Node> items) implements Node {
    }

    /** if с веткой else или без неё; else-ветка, являясь if, печатается как else if. */
    private record Conditional(String condition, Node thenPart, Node elsePart) implements Node {
    }

    private enum LoopKind { WHILE, DO_WHILE, FOR, FOR_EVER, FOR_EACH }

    private record Loop(LoopKind kind, Node body) implements Node {
    }

    /** switch: {@code bodies} — по одному на метку и, если есть, последним — default. */
    private record Choice(int labels, boolean hasDefault, boolean arrows,
                          List<Node> bodies) implements Node {
    }

    /** Счётчики конструкции: операторы, ветвления и наибольший уровень (−1, если ветвлений нет). */
    private record Measure(int operators, int branchings, int maxLevel) {
        private static final Measure NONE = new Measure(0, 0, -1);

        private Measure plus(Measure other) {
            return new Measure(operators + other.operators, branchings + other.branchings,
                    Math.max(maxLevel, other.maxLevel));
        }
    }

    /** Те же правила, что в README, записанные независимо от анализатора. */
    private static Measure measure(Node node, int level) {
        return switch (node) {
            case Simple simple -> new Measure(1, 0, -1);
            case Sequence sequence -> {
                Measure total = Measure.NONE;
                for (Node item : sequence.items()) {
                    total = total.plus(measure(item, level));
                }
                yield total;
            }
            case Conditional conditional -> {
                Measure inner = measure(conditional.thenPart(), level + 1);
                if (conditional.elsePart() != null) {
                    inner = inner.plus(measure(conditional.elsePart(), level + 1));
                }
                yield new Measure(1 + inner.operators(), 1 + inner.branchings(),
                        Math.max(level, inner.maxLevel()));
            }
            case Loop loop -> {
                Measure body = measure(loop.body(), level + 1);
                yield new Measure(1 + body.operators(), 1 + body.branchings(),
                        Math.max(level, body.maxLevel()));
            }
            case Choice choice -> {
                int operators = 1;
                int branchings = choice.labels();
                int maxLevel = choice.labels() > 0 ? level + choice.labels() - 1 : -1;
                for (int index = 0; index < choice.bodies().size(); index++) {
                    boolean isDefault = choice.hasDefault() && index == choice.bodies().size() - 1;
                    int bodyLevel = isDefault ? level + choice.labels() : level + index + 1;
                    Measure body = measure(choice.bodies().get(index), bodyLevel);
                    operators += body.operators() + (choice.arrows() ? 0 : 1); // break;
                    branchings += body.branchings();
                    maxLevel = Math.max(maxLevel, body.maxLevel());
                }
                yield new Measure(operators, branchings, maxLevel);
            }
        };
    }

    /** Готовая программа и ожидаемые для неё значения. */
    private record RandomProgram(String source, int operators, int branchings,
                                 int maxNesting, int methods) {

        private static RandomProgram generate(long seed) {
            Random random = new Random(seed);
            Printer printer = new Printer(random);
            int methods = 1 + random.nextInt(3);
            int operators = 0;
            int branchings = 0;
            int maxLevel = -1;
            StringBuilder text = new StringBuilder("class Generated {\n");
            for (int method = 0; method < methods; method++) {
                Node body = printer.sequence(1 + random.nextInt(5), 0);
                Measure measure = measure(body, 0);
                operators += measure.operators();
                branchings += measure.branchings();
                maxLevel = Math.max(maxLevel, measure.maxLevel());
                text.append("    void method").append(method)
                        .append("(int a, int b, boolean c, int[] items) {\n");
                printer.print(body, 2, text);
                text.append("    }\n");
            }
            text.append("}\n");
            return new RandomProgram(text.toString(), operators, branchings,
                    Math.max(maxLevel, 0), methods);
        }
    }

    /** Строит случайные деревья и печатает их с разным оформлением. */
    private static final class Printer {
        private static final int MAX_DEPTH = Integer.getInteger("gilb.depth", 5);
        private static final String[] CONDITIONS = {
            "a > 0", "b != 3 && c", "!c", "a % 2 == 0 || b < 0", "items.length > a"};
        private static final String[] NOISE = {
            "// if (a) { while (b) { for (;;) switch (c) { case 1: } } }",
            "/* else if (x) { do { } while (y); } */",
            "/** switch (a) { case 1: default: } */"};

        private final Random random;
        private int counter;

        private Printer(Random random) {
            this.random = random;
        }

        private Node sequence(int length, int depth) {
            List<Node> items = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                items.add(statement(depth));
            }
            return new Sequence(items);
        }

        private Node statement(int depth) {
            int roll = random.nextInt(depth >= MAX_DEPTH ? 4 : 12);
            return switch (roll) {
                case 0, 1, 2, 3 -> simple();
                case 4, 5 -> conditional(depth);
                case 6 -> chain(depth);
                case 7, 8 -> loop(depth);
                case 9, 10 -> choice(depth);
                default -> sequence(2 + random.nextInt(2), depth + 1);
            };
        }

        private Node simple() {
            int id = ++counter;
            return new Simple(switch (random.nextInt(8)) {
                case 0 -> "a = a + " + id + ";";
                case 1 -> "b++;";
                case 2 -> "System.out.println(a);";
                case 3 -> "items[0] += " + id + ";";
                case 4 -> "int v" + id + " = a;";
                case 5 -> "String s" + id + " = \"if (a) while (b) for (;;) { switch (c) { } }\";";
                case 6 -> "char k" + id + " = '{';";
                default -> "double d" + id + " = 0.5e" + (id % 5) + ";";
            });
        }

        private Node conditional(int depth) {
            Node thenPart = body(depth + 1);
            Node elsePart = random.nextInt(3) == 0 ? body(depth + 1) : null;
            return new Conditional(condition(), thenPart, elsePart);
        }

        /** if / else if / else if … / else. */
        private Node chain(int depth) {
            int links = 2 + random.nextInt(3);
            Node tail = random.nextBoolean() ? body(depth + 1) : null;
            for (int link = 0; link < links; link++) {
                tail = new Conditional(condition(), body(depth + 1), tail);
            }
            return tail;
        }

        private Node loop(int depth) {
            LoopKind kind = LoopKind.values()[random.nextInt(LoopKind.values().length)];
            return new Loop(kind, body(depth + 1));
        }

        private Node choice(int depth) {
            int labels = random.nextInt(5);
            boolean hasDefault = random.nextBoolean() || labels == 0;
            List<Node> bodies = new ArrayList<>();
            for (int index = 0; index < labels + (hasDefault ? 1 : 0); index++) {
                bodies.add(body(depth + 1));
            }
            return new Choice(labels, hasDefault, random.nextBoolean(), bodies);
        }

        private Node body(int depth) {
            return depth >= MAX_DEPTH || random.nextInt(3) > 0
                    ? sequence(1 + random.nextInt(2), depth) : statement(depth);
        }

        private String condition() {
            return CONDITIONS[random.nextInt(CONDITIONS.length)];
        }

        // --- печать

        private void print(Node node, int indent, StringBuilder out) {
            switch (node) {
                case Simple simple -> line(out, indent, simple.code());
                case Sequence sequence -> {
                    for (Node item : sequence.items()) {
                        if (random.nextInt(6) == 0) {
                            line(out, indent, NOISE[random.nextInt(NOISE.length)]);
                        }
                        print(item, indent, out);
                    }
                }
                case Conditional conditional -> printConditional(conditional, indent, out, false);
                case Loop loop -> printLoop(loop, indent, out);
                case Choice choice -> printChoice(choice, indent, out);
            }
        }

        /** Тело в фигурных скобках или, если это возможно и случайно выпало, без них. */
        private void printBody(Node body, int indent, StringBuilder out, boolean mustBrace) {
            Node single = singleSimple(body);
            boolean declaration = single instanceof Simple simple && isDeclaration(simple);
            if (!mustBrace && single != null && !declaration && random.nextBoolean()) {
                print(single, indent + 1, out);
            } else {
                line(out, indent, "{");
                print(body, indent + 1, out);
                line(out, indent, "}");
            }
        }

        private static Node singleSimple(Node node) {
            if (node instanceof Sequence sequence && sequence.items().size() == 1) {
                return singleSimple(sequence.items().get(0));
            }
            return node instanceof Simple ? node : null;
        }

        private static boolean isDeclaration(Simple simple) {
            String code = simple.code();
            return code.startsWith("int ") || code.startsWith("String ")
                    || code.startsWith("char ") || code.startsWith("double ");
        }

        private void printConditional(Conditional node, int indent, StringBuilder out,
                                      boolean afterElse) {
            String head = "if (" + node.condition() + ")";
            if (afterElse) {
                out.setLength(out.length() - 1); // «else» остаётся на строке с if
                out.append(' ').append(head).append('\n');
            } else {
                line(out, indent, head);
            }
            boolean hasElse = node.elsePart() != null;
            printBody(node.thenPart(), indent, out, hasElse); // иначе else «повиснет»
            if (hasElse) {
                line(out, indent, "else");
                if (node.elsePart() instanceof Conditional chained && random.nextBoolean()) {
                    printConditional(chained, indent, out, true);
                } else {
                    printBody(node.elsePart(), indent, out, false);
                }
            }
        }

        private void printLoop(Loop node, int indent, StringBuilder out) {
            int id = ++counter;
            switch (node.kind()) {
                case WHILE -> {
                    line(out, indent, "while (" + condition() + ")");
                    printBody(node.body(), indent, out, false);
                }
                case DO_WHILE -> {
                    line(out, indent, "do");
                    printBody(node.body(), indent, out, true);
                    line(out, indent, "while (" + condition() + ");");
                }
                case FOR -> {
                    line(out, indent, "for (int i" + id + " = 0; i" + id + " < a; i" + id + "++)");
                    printBody(node.body(), indent, out, false);
                }
                case FOR_EVER -> {
                    line(out, indent, "for (;;)");
                    printBody(node.body(), indent, out, false);
                }
                case FOR_EACH -> {
                    line(out, indent, "for (int e" + id + " : items)");
                    printBody(node.body(), indent, out, false);
                }
            }
        }

        /** default печатается в случайном месте: его тело всё равно последний else цепочки. */
        private void printChoice(Choice node, int indent, StringBuilder out) {
            line(out, indent, "switch (a) {");
            int slots = node.bodies().size();
            int defaultSlot = node.hasDefault() ? random.nextInt(slots) : -1;
            for (int slot = 0; slot < slots; slot++) {
                boolean isDefault = slot == defaultSlot;
                int index = isDefault ? slots - 1 : slot - (defaultSlot >= 0 && slot > defaultSlot ? 1 : 0);
                String label = isDefault ? "default" : "case " + (index + 1);
                if (node.arrows()) {
                    line(out, indent + 1, label + " -> {");
                    print(node.bodies().get(index), indent + 2, out);
                    line(out, indent + 1, "}");
                } else {
                    line(out, indent + 1, label + ":");
                    print(node.bodies().get(index), indent + 2, out);
                    line(out, indent + 2, "break;");
                }
            }
            line(out, indent, "}");
        }

        private static void line(StringBuilder out, int indent, String text) {
            out.append("    ".repeat(indent)).append(text).append('\n');
        }
    }
}
