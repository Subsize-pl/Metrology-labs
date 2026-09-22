import java.util.Map;

public final class JavaHalsteadAnalyzerTest {
    public static void main(String[] args) {
        declarationsAreIgnoredButInitializersAreCounted();
        assignedMethodIsBothOperatorAndOperand();
        standaloneMethodIsOnlyOperator();
        commentsAndCompoundOperatorsAreHandled();
        System.out.println("Все тесты пройдены.");
    }

    private static void declarationsAreIgnoredButInitializersAreCounted() {
        String source = "import java.util.List;\n"
                + "public final class Example {\n"
                + "  private int onlyDeclared;\n"
                + "  private static final int dhb = 10;\n"
                + "  public void run(String argument) { int local; }\n"
                + "}\n";
        HalsteadMetrics metrics = analyze(source);
        Map<String, Integer> operators = metrics.getOperatorFrequencies();
        Map<String, Integer> operands = metrics.getOperandFrequencies();

        for (String declarationToken : new String[]{
                "import", "public", "private", "static", "final",
                "class", "void", "int"}) {
            require(!operators.containsKey(declarationToken),
                    "Токен объявления не должен учитываться: "
                            + declarationToken);
        }
        for (String declarationName : new String[]{
                "java", "util", "List", "Example", "onlyDeclared",
                "run", "String", "argument", "local"}) {
            require(!operands.containsKey(declarationName),
                    "Имя из объявления не должно учитываться: "
                            + declarationName);
        }
        require(operands.get("dhb") == 1,
                "Инициализируемая переменная dhb должна быть операндом");
        require(operands.get("10") == 1,
                "Значение инициализатора должно быть операндом");
        require(operators.get("=") == 1,
                "Оператор присваивания в инициализаторе должен учитываться");
    }

    private static void assignedMethodIsBothOperatorAndOperand() {
        String source = "class Example {\n"
                + "  int calculate() { return 1; }\n"
                + "  void run() { int result = calculate(); }\n"
                + "}\n";
        HalsteadMetrics metrics = analyze(source);

        require(metrics.getOperatorFrequencies().get("calculate()") == 1,
                "Вызов calculate() должен быть оператором");
        require(metrics.getOperandFrequencies().get("calculate") == 1,
                "Метод справа от присваивания должен быть операндом");
        require(metrics.getOperandFrequencies().get("result") == 1,
                "Инициализируемая переменная должна быть операндом");
    }

    private static void standaloneMethodIsOnlyOperator() {
        String source = "class Example {\n"
                + "  void print(int value) { }\n"
                + "  void run() { print(10); }\n"
                + "}\n";
        HalsteadMetrics metrics = analyze(source);

        require(metrics.getOperatorFrequencies().get("print()") == 1,
                "Самостоятельный вызов print() должен быть оператором");
        require(!metrics.getOperandFrequencies().containsKey("print"),
                "Самостоятельный вызов не должен быть операндом");
    }

    private static void commentsAndCompoundOperatorsAreHandled() {
        String source = "class Example {\n"
                + "  void run() {\n"
                + "    int sum = 0; // ignored + token\n"
                + "    sum += 2; if (sum >= 2) print(sum);\n"
                + "  }\n"
                + "  void print(int value) { }\n"
                + "}\n";
        HalsteadMetrics metrics = analyze(source);

        require(metrics.getOperatorFrequencies().containsKey("+="),
                "Составной оператор += должен распознаваться целиком");
        require(metrics.getOperatorFrequencies().containsKey(">="),
                "Оператор >= должен распознаваться целиком");
        require(metrics.getOperatorFrequencies().containsKey("print()"),
                "Имя вызываемого метода должно быть оператором");
        require(!metrics.getOperandFrequencies().containsKey("print"),
                "Вызов без присваивания не должен быть операндом");
        require(!metrics.getOperatorFrequencies().containsKey("+"),
                "Оператор из комментария не должен учитываться");
    }

    private static HalsteadMetrics analyze(String source) {
        return new JavaHalsteadAnalyzer().analyze(source);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
