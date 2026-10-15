import java.nio.file.Files;
import java.nio.file.Path;

/** Проверки анализатора. Код возврата 0 — всё сошлось. */
public final class GilbAnalyzerTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        // рис. 3 методички: CL = 4, N = 11, CLI = 3
        check("рис. 3 методички", """
                class F { double y; void run(java.util.Scanner in) {
                    double x = in.nextDouble();
                    if (x < 0) y = 0;
                    else if (x == 0) y = 1;
                    else if (x < 0.5) y = 2;
                    else if (x < 1) y = 3;
                    else y = 4;
                    System.out.println(y);
                } }""", 4, 11, 3);

        // рис. 2: switch из 5 ветвей, CL = 4, CLI = 3
        check("switch из 5 ветвей", """
                switch (k) {
                    case 1: a = 1; break;
                    case 2: a = 2; break;
                    case 3: a = 3; break;
                    case 4: a = 4; break;
                    default: a = 0;
                }""", 4, 10, 3);

        check("if внутри case 3 → уровень 3", """
                switch (k) {
                    case 1: break;
                    case 2: break;
                    case 3: if (a > 0) a--; break;
                }""", 4, 6, 3);
        check("switch без case", "switch (k) { default: a = 1; }", 0, 2, 0);
        check("стрелочный switch, case 1, 2", "switch (k) { case 1, 2 -> a = 1; default -> a = 2; }", 2, 3, 1);
        check("циклы друг за другом — уровень 0",
                "for (;;) break; while (a > 0) a--; do a++; while (a < 5); for (int x : arr) s += x;",
                4, 8, 0);
        check("вложенные циклы", "for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i == j) s++;",
                3, 4, 2);
        check("нет ветвлений", "int a = 1, b = 2; a = a + b; System.out.println(a);", 0, 3, 0);
        check("ключевые слова в строках и комментариях",
                "String s = \"if while for\"; // if (x) for(;;)\n/* switch case */ s = s + 1;", 0, 2, 0);
        check("?: и && — не ветвления", "int a = b > 0 && c > 0 ? 1 : 2;", 0, 1, 0);
        check("пустой текст", "", 0, 0, 0);
        check("ошибка синтаксиса не роняет анализ", "if (a > 0 { a = 1; }", 1, 2, 0);

        // большие входы
        check("вложенность 3000", "if (a > 0) {\n".repeat(3000) + "a++;\n" + "}\n".repeat(3000), 3000, 3001, 2999);
        check("else-if цепочка 5000", "if (a == 0) a++;\n" + "else if (a == 1) a++;\n".repeat(5000), 5001, 10002, 5000);
        check("20000 строк", "a++;\nif (a > 1) a--;\n".repeat(10000), 10000, 30000, 0);

        // сама программа лабораторной
        check("OrderStats.java", Files.readString(Path.of("OrderStats.java")), 16, 51, 5);

        System.out.println(failures == 0 ? "Все проверки пройдены" : "Расхождений: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, String code, int cl, int n, int cli) {
        GilbAnalyzer.Result r = GilbAnalyzer.analyze(code);
        boolean ok = r.absolute() == cl && r.operators() == n && r.maxNesting() == cli;
        if (!ok) {
            failures++;
        }
        System.out.printf("%s %-42s CL=%d N=%d CLI=%d%s%n", ok ? "ok  " : "FAIL", name,
                r.absolute(), r.operators(), r.maxNesting(),
                ok ? "" : "   (ожидалось CL=" + cl + " N=" + n + " CLI=" + cli + ")");
    }
}
