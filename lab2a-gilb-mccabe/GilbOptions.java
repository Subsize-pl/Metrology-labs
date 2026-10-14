/**
 * Что считать ветвлением (числитель CL) и оператором (знаменатель N).
 *
 * <p>{@link #METHODOLOGY} — трактовка методических указаний кафедры. Остальные
 * сочетания нужны, чтобы на защите быстро перейти к иной трактовке и увидеть,
 * как меняется результат.
 *
 * @param loopsAreBranchings       for / for-each / while / do-while — ветвления
 *                                 (страница «Задание 2»: «операторы условия,
 *                                 циклы, операторы множественного выбора»)
 * @param ternaryIsBranching       тернарный {@code ?:} — ветвление; по JLS это
 *                                 выражение (гл. 15), а не оператор (гл. 14)
 * @param declarationsAreOperators объявление локальной переменной — оператор
 *                                 (JLS 14.4: «executable statement»)
 * @param blocksAreOperators       блок {@code { }} — оператор; в JLS 14.5 он
 *                                 оператор, в Java Tutorial — лишь группировка
 */
public record GilbOptions(
        boolean loopsAreBranchings,
        boolean ternaryIsBranching,
        boolean declarationsAreOperators,
        boolean blocksAreOperators) {

    /** Значения по методике: циклы — ветвления; {@code ?:} и блоки не считаются. */
    public static final GilbOptions METHODOLOGY =
            new GilbOptions(true, false, true, false);
}
