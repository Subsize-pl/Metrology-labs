// Статистика заказов за день
public class OrderStats {
    public static void main(String[] args) {
        int[] amounts = { 120, 45, 0, 310, 75, 980, 15, 260, 540 };
        int[] regions = { 1, 2, 3, 1, 4, 2, 1, 3, 5 };
        int revenue = 0;    // выручка
        int large = 0;      // крупные заказы (от 250)
        int small = 0;      // мелкие заказы (до 50)
        int empty = 0;      // отменённые заказы (сумма 0)
        int delivery = 0;   // стоимость доставки
        String rating;      // оценка дня

        // for по всем заказам
        for (int i = 0; i < amounts.length; i++) {
            int amount = amounts[i];
            if (amount == 0) {
                empty++;
                continue;
            }
            revenue += amount;
            // размер заказа
            if (amount >= 250) {
                large++;
                // доставка по региону
                switch (regions[i]) {
                    case 1: delivery += 3; break;
                    case 2: delivery += 5; break;
                    case 3:
                        // условие внутри switch
                        if (amount < 500) delivery += 10;
                        break;
                    default: delivery += 20;
                }
            } else if (amount < 50) {
                small++;
                if (regions[i] > 2) delivery += 15;
            }
        }

        // for-each: самый крупный заказ
        int maxAmount = 0;
        for (int amount : amounts) {
            if (amount > maxAmount) maxAmount = amount;
        }

        // while: бонус за каждые 500
        int bonus = 0;
        while (revenue - bonus * 500 >= 500) {
            bonus++;
        }

        // do-while: число цифр выручки
        int digits = 0;
        int rest = revenue;
        do {
            rest /= 10;
            digits++;
        } while (rest > 0);

        // оценка дня
        if (large > small) {
            rating = "высокий";
        } else if (large == small) {
            if (delivery > 30) rating = "средний+";
            else rating = "средний";
        } else {
            rating = "низкий";
        }

        System.out.println("Выручка: " + revenue + ", максимум: " + maxAmount
                + ", цифр: " + digits);
        System.out.println("Крупных: " + large + ", мелких: " + small
                + ", отменено: " + empty);
        System.out.println("Доставка: " + delivery + ", бонусов: " + bonus
                + ", уровень: " + rating);
    }
}
