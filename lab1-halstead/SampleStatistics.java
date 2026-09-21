import java.util.Arrays;
import java.util.Scanner;
public class SampleStatistics {
    private static final int MAX_VALUES = 20; // Ограничение защищает от слишком большого ввода.
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Введите целые числа через пробел:");
        String input = scanner.nextLine();
        int[] values = parseNumbers(input); // Вызов пользовательской подпрограммы.
        if (values.length == 0) { System.out.println("Нет корректных чисел."); return; }
        int sum = calculateSum(values);
        int minimum = findMinimum(values);
        int maximum = findMaximum(values);
        int evenCount = countEven(values);
        double average = (double) sum / values.length;
        String category = classifyAverage(average);
        System.out.println("Массив: " + Arrays.toString(values));
        System.out.println("Сумма: " + sum);
        System.out.println("Минимум: " + minimum);
        System.out.println("Максимум: " + maximum);
        System.out.println("Среднее: " + average);
        if (evenCount > 0) { System.out.println("Чётных: " + evenCount); } else { System.out.println("Чётных нет."); }
        System.out.println("Категория: " + category);
        demonstrateOperators(sum, minimum, maximum);
    }
    private static int[] parseNumbers(String input) {
        String[] parts = input.trim().split("\\s+");
        int limit = Math.min(parts.length, MAX_VALUES);
        int[] temporary = new int[limit];
        int count = 0;
        for (String part : parts) {
            if (count >= limit) { break; }
            try {
                temporary[count++] = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                System.out.println("Пропущено: " + part);
            }
        }
        return Arrays.copyOf(temporary, count);
    }
    private static int calculateSum(int[] values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum;
    }
    private static int findMinimum(int[] values) {
        int minimum = values[0];
        int index = 0;
        while (index < values.length) {
            minimum = values[index] < minimum ? values[index] : minimum;
            index++;
        }
        return minimum;
    }
    private static int findMaximum(int[] values) {
        int maximum = values[0];
        int index = 0;
        do {
            if (values[index] > maximum && values[index] != 0) {
                maximum = values[index];
            }
            index++;
        } while (index < values.length);
        return maximum;
    }
    private static int countEven(int[] values) {
        int evenCount = 0;
        for (int value : values) {
            if (value % 2 != 0) {
                continue;
            }
            evenCount++;
        }
        return evenCount;
    }
    private static String classifyAverage(double average) {
        int rounded = (int) Math.round(average);
        switch (Integer.signum(rounded)) {
            case -1:
                return "отрицательное";
            case 0:
                return "нулевое";
            default:
                return "положительное";
        }
    }
    private static void demonstrateOperators(int sum, int min, int max) {
        int range = max - min;
        int product = range * 2;
        product--;
        int mask = (sum & 0xFF) | (max ^ min);
        int inverted = ~mask;
        mask <<= 1; // Демонстрация составного оператора сдвига.
        mask >>= 1;
        boolean ordered = min <= max || !(sum == 0);
        System.out.println("Маска: " + mask + ", инверсия: " + inverted + ", произведение: " + product + ", порядок: " + ordered);
    }
}
