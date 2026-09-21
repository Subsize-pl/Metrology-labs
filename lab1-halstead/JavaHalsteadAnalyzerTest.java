public final class JavaHalsteadAnalyzerTest {
    public static void main(String[] args) {
        String source = "int sum = 0; // ignored + token\n"
                + "sum += value; if (sum >= 2) print(sum);";
        HalsteadMetrics metrics = new JavaHalsteadAnalyzer().analyze(source);

        require(metrics.getOperatorFrequencies().containsKey("+="),
                "Составной оператор += должен распознаваться целиком");
        require(metrics.getOperatorFrequencies().containsKey(">="),
                "Оператор >= должен распознаваться целиком");
        require(metrics.getOperatorFrequencies().containsKey("print()"),
                "Имя вызываемого метода должно быть оператором");
        require(metrics.getOperandFrequencies().get("print") == 1,
                "Имя вызываемого метода должно одновременно быть операндом");
        require(!metrics.getOperatorFrequencies().containsKey("+"),
                "Оператор из комментария не должен учитываться");
        require(metrics.getOperandFrequencies().get("sum") == 4,
                "Частота операнда sum должна быть равна 4");
        System.out.println("Все тесты пройдены.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
