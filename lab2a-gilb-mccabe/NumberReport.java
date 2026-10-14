import java.util.Arrays;

public class NumberReport {
    public static void main(String[] args) {
        int[] data = parse(args);
        if (data.length == 0) {
            System.out.println("Нет корректных чисел");
            return;
        }
        int total = sum(data);
        System.out.println("Сумма: " + total + ", максимум: " + max(data));
        System.out.println("Простых: " + countPrimes(data) + ", цифр: " + digits(total));
        System.out.println(grade(total / data.length) + ", " + dayName(data[0]));
    }

    private static int[] parse(String[] args) {
        int[] values = new int[args.length];
        int count = 0;
        for (String arg : args) {
            try {
                values[count++] = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                System.out.println("Пропущено: " + arg);
            }
        }
        return Arrays.copyOf(values, count);
    }

    private static int sum(int[] data) {
        int total = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] < 0) {
                continue;
            }
            total += data[i];
        }
        return total;
    }

    private static int max(int[] data) {
        int best = data[0];
        int i = 1;
        while (i < data.length) {
            if (data[i] > best) {
                best = data[i];
            }
            i++;
        }
        return best;
    }

    private static int countPrimes(int[] data) {
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            boolean prime = data[i] > 1;
            for (int d = 2; d * d <= data[i]; d++) {
                if (data[i] % d == 0) {
                    prime = false;
                    break;
                }
            }
            if (prime) {
                count++;
            }
        }
        return count;
    }

    private static String grade(int average) {
        if (average >= 9) {
            return "отлично";
        } else if (average >= 7) {
            return "хорошо";
        } else if (average >= 4) {
            return "удовлетворительно";
        } else {
            return "плохо";
        }
    }

    private static String dayName(int number) {
        switch (Math.abs(number) % 7) {
            case 1: return "понедельник";
            case 2: return "вторник";
            case 3: return "среда";
            case 4: return "четверг";
            default: return "другой день";
        }
    }

    private static int digits(int number) {
        int count = 0;
        do {
            number /= 10;
            count++;
        } while (number != 0);
        return count;
    }
}
