import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Рассчитывает метрику Джилба (CL, cl, CLI) и цикломатическое число Маккейба
 * для исходного текста на Java.
 *
 * <p>Текст разбирается штатным парсером javac (Compiler Tree API), поэтому
 * ключевые слова в комментариях, строках и текстовых блоках не влияют на
 * результат. Затем дерево обходится один раз, и каждый узел классифицируется
 * по правилам, изложенным в README:
 *
 * <ul>
 *   <li><b>оператор</b> (знаменатель cl) — любой оператор по JLS, гл. 14;</li>
 *   <li><b>ветвление</b> (числитель CL) — {@code if}, цикл или метка
 *       {@code case}; {@code switch} заменяется цепочкой вложенных
 *       {@code if}, селектор и {@code default} условием не считаются.</li>
 * </ul>
 */
public final class GilbAnalyzer {
    private static final String WRAPPER_CLASS = "Fragment__";
    private static final String WRAPPER_METHOD = "fragment__";
    private static final long STACK_BYTES = 128L << 20;

    /** Анализ с настройками по методике. */
    public GilbMetrics analyze(String source) {
        return analyze(source, GilbOptions.METHODOLOGY);
    }

    public GilbMetrics analyze(String source, GilbOptions options) {
        String text = source == null ? "" : stripByteOrderMark(source);
        // Глубоко вложенный код рекурсивно обходится парсером и сканером,
        // поэтому работаем в потоке с большим стеком.
        AtomicReference<GilbMetrics> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            try {
                result.set(analyzeText(text, options));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "gilb-analyzer", STACK_BYTES);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Анализ прерван", interrupted);
        }
        if (failure.get() instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Не удалось разобрать код", failure.get());
        }
        return result.get();
    }

    /** Блокнот Windows сохраняет UTF-8 с меткой BOM (U+FEFF); javac её не принимает. */
    private static String stripByteOrderMark(String text) {
        return !text.isEmpty() && text.charAt(0) == 0xFEFF ? text.substring(1) : text;
    }

    private static GilbMetrics analyzeText(String text, GilbOptions options) {
        ParsedSource parsed = parseBestInterpretation(text);
        MetricScanner scanner = new MetricScanner(parsed, options);
        scanner.scan(new TreePath(parsed.unit()), null);
        return scanner.toMetrics();
    }

    // ---------------------------------------------------------------- разбор

    /**
     * Пробует прочитать текст как файл, как набор членов класса и как набор
     * операторов; берёт первую трактовку без ошибок, иначе — с наименьшим их
     * числом. Так анализатор принимает и целую программу, и вырезанный метод,
     * и несколько строк кода.
     */
    private static ParsedSource parseBestInterpretation(String text) {
        ParsedSource best = null;
        for (GilbMetrics.ParseMode mode : GilbMetrics.ParseMode.values()) {
            ParsedSource candidate = parse(text, mode);
            if (candidate.problems().isEmpty()) {
                return candidate;
            }
            if (best == null || candidate.problems().size() < best.problems().size()) {
                best = candidate;
            }
        }
        return best;
    }

    private static ParsedSource parse(String text, GilbMetrics.ParseMode mode) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "Нужен JDK: в этой среде нет компилятора javac (модуль jdk.compiler)");
        }
        String wrapped = wrap(text, mode);
        int lineOffset = mode == GilbMetrics.ParseMode.COMPILATION_UNIT ? 0 : 1;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                List.of("-proc:none"), null, List.of(new SourceFile(wrapped)));
        try {
            CompilationUnitTree unit = task.parse().iterator().next();
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            return new ParsedSource(unit, positions, mode, lineOffset,
                    problems(diagnostics, lineOffset));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String wrap(String text, GilbMetrics.ParseMode mode) {
        return switch (mode) {
            case COMPILATION_UNIT -> text;
            case CLASS_MEMBERS -> "class " + WRAPPER_CLASS + " {\n" + text + "\n}\n";
            case STATEMENTS -> "class " + WRAPPER_CLASS + " { void " + WRAPPER_METHOD
                    + "() {\n" + text + "\n} }\n";
        };
    }

    private static List<GilbMetrics.SyntaxProblem> problems(
            DiagnosticCollector<JavaFileObject> diagnostics, int lineOffset) {
        List<GilbMetrics.SyntaxProblem> problems = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                int line = (int) Math.max(1, diagnostic.getLineNumber() - lineOffset);
                problems.add(new GilbMetrics.SyntaxProblem(
                        line, diagnostic.getMessage(Locale.ENGLISH)));
            }
        }
        return problems;
    }

    private record ParsedSource(CompilationUnitTree unit, SourcePositions positions,
                                GilbMetrics.ParseMode mode, int lineOffset,
                                List<GilbMetrics.SyntaxProblem> problems) {
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        private SourceFile(String source) {
            super(URI.create("string:///AnalyzedSource.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    // ----------------------------------------------------------------- подсчёт

    /** Счётчики одной подпрограммы (метода, конструктора, блока инициализации). */
    private static final class Frame {
        private final String name;
        private final int line;
        private int operators;
        private int branchings;
        private int maxNesting;

        private Frame(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    /**
     * Обход дерева. Поле {@code level} — сколько ветвлений охватывает текущую
     * точку; в этот момент ветвление и записывается как его уровень.
     */
    private static final class MetricScanner extends TreePathScanner<Void, Void> {
        private final GilbOptions options;
        private final ParsedSource parsed;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;

        private final List<GilbMetrics.Branching> branchings = new ArrayList<>();
        private final Map<GilbMetrics.StatementKind, Integer> statements =
                new EnumMap<>(GilbMetrics.StatementKind.class);
        private final Deque<Frame> openFrames = new ArrayDeque<>();
        private final List<Frame> closedFrames = new ArrayList<>();
        /** Второй и последующие declarator'ы одного объявления: {@code int a, b;}. */
        private final Set<Tree> continuationDeclarators =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Frame outsideMethods = new Frame("(вне подпрограмм)", 0);
        private int operatorCount;
        private int level;

        private MetricScanner(ParsedSource parsed, GilbOptions options) {
            this.parsed = parsed;
            this.options = options;
            this.unit = parsed.unit();
            this.positions = parsed.positions();
            openFrames.push(outsideMethods);
        }

        private GilbMetrics toMetrics() {
            List<Frame> frames = new ArrayList<>(closedFrames);
            if (outsideMethods.operators > 0 || outsideMethods.branchings > 0) {
                frames.add(outsideMethods);
            }
            frames.sort(Comparator.comparingInt(frame -> frame.line));
            List<GilbMetrics.MethodStats> methods = new ArrayList<>();
            for (Frame frame : frames) {
                methods.add(new GilbMetrics.MethodStats(frame.name, frame.line,
                        frame.operators, frame.branchings, frame.maxNesting));
            }
            return new GilbMetrics(options, parsed.mode(), operatorCount, branchings,
                    statements, methods, parsed.problems());
        }

        // --- регистрация найденного

        private void countOperator(GilbMetrics.StatementKind kind) {
            operatorCount++;
            statements.merge(kind, 1, Integer::sum);
            openFrames.peek().operators++;
        }

        private void countBranching(GilbMetrics.BranchingKind kind, Tree at, int atLevel) {
            branchings.add(new GilbMetrics.Branching(kind, lineOf(at), atLevel));
            Frame frame = openFrames.peek();
            frame.branchings++;
            frame.maxNesting = Math.max(frame.maxNesting, atLevel);
        }

        private int lineOf(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long line = start < 0 ? 1 : unit.getLineMap().getLineNumber(start);
            return (int) Math.max(1, line - parsed.lineOffset());
        }

        private Tree parentOfCurrent() {
            return getCurrentPath().getParentPath().getLeaf();
        }

        // --- подпрограммы и блоки

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (node.getBody() == null) {
                return null; // abstract / native / метод интерфейса без тела
            }
            openFrames.push(new Frame(describe(node), lineOf(node)));
            scan(node.getBody(), unused);
            closedFrames.add(openFrames.pop());
            return null;
        }

        private String describe(MethodTree method) {
            String owner = enclosingClassName();
            String name = method.getName().contentEquals("<init>")
                    ? owner : method.getName().toString();
            if (name.equals(WRAPPER_METHOD)) {
                return "(операторы фрагмента)";
            }
            StringBuilder parameters = new StringBuilder();
            for (VariableTree parameter : method.getParameters()) {
                if (parameters.length() > 0) {
                    parameters.append(", ");
                }
                parameters.append(parameter.getType());
            }
            String prefix = owner.equals(WRAPPER_CLASS) ? "" : owner + ".";
            return prefix + name + "(" + parameters + ")";
        }

        private String enclosingClassName() {
            for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (path.getLeaf() instanceof ClassTree owner) {
                    String name = owner.getSimpleName().toString();
                    return name.isEmpty() ? "<anonymous>" : name;
                }
            }
            return "";
        }

        @Override
        public Void visitBlock(BlockTree node, Void unused) {
            markContinuationDeclarators(node.getStatements());
            Tree parent = parentOfCurrent();
            if (parent instanceof ClassTree) { // блок инициализации класса
                String name = node.isStatic()
                        ? "(static-блок инициализации)" : "(блок инициализации)";
                openFrames.push(new Frame(name, lineOf(node)));
                super.visitBlock(node, unused);
                closedFrames.add(openFrames.pop());
                return null;
            }
            if (options.blocksAreOperators() && isStatementPosition(parent)) {
                countOperator(GilbMetrics.StatementKind.BLOCK);
            }
            return super.visitBlock(node, unused);
        }

        /**
         * Блок — оператор только там, где грамматика ждёт Statement. Тело
         * метода, лямбды, try/catch/finally, synchronized и {@code ->}-правило
         * switch — это Block, а не Statement.
         */
        private static boolean isStatementPosition(Tree parent) {
            return switch (parent.getKind()) {
                case BLOCK, IF, WHILE_LOOP, DO_WHILE_LOOP, FOR_LOOP,
                        ENHANCED_FOR_LOOP, LABELED_STATEMENT -> true;
                case CASE -> ((CaseTree) parent).getCaseKind() == CaseTree.CaseKind.STATEMENT;
                default -> false;
            };
        }

        /**
         * javac превращает {@code int a = 1, b = 2;} в два VariableTree с общим
         * началом. По JLS 14.4 это один оператор, поэтому второй и следующие
         * declarator'ы запоминаются, чтобы не считать их повторно.
         */
        private void markContinuationDeclarators(List<? extends StatementTree> list) {
            if (list == null) {
                return;
            }
            long previousStart = -1;
            for (StatementTree statement : list) {
                if (statement instanceof VariableTree variable) {
                    long start = positions.getStartPosition(unit, variable);
                    if (start == previousStart) {
                        continuationDeclarators.add(variable);
                    }
                    previousStart = start;
                } else {
                    previousStart = -1;
                }
            }
        }

        // --- операторы, не являющиеся ветвлениями

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (options.declarationsAreOperators() && isDeclarationStatement(node)) {
                countOperator(GilbMetrics.StatementKind.DECLARATION);
            }
            return super.visitVariable(node, unused);
        }

        /** Поля, параметры, переменные for/catch/try в N не входят. */
        private boolean isDeclarationStatement(VariableTree node) {
            Tree.Kind parent = parentOfCurrent().getKind();
            return (parent == Tree.Kind.BLOCK || parent == Tree.Kind.CASE)
                    && !continuationDeclarators.contains(node);
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree node, Void unused) {
            if (!isForHeaderExpression(node)) {
                countOperator(GilbMetrics.StatementKind.EXPRESSION);
            }
            return super.visitExpressionStatement(node, unused);
        }

        /**
         * Выражения в init и update заголовка for — часть оператора for, а не
         * отдельные операторы. Тело for без скобок (то же родительское дерево)
         * обычным оператором остаётся.
         */
        private boolean isForHeaderExpression(ExpressionStatementTree node) {
            return parentOfCurrent() instanceof ForLoopTree loop
                    && (loop.getInitializer().contains(node) || loop.getUpdate().contains(node));
        }

        @Override
        public Void visitBreak(BreakTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.BREAK);
            return super.visitBreak(node, unused);
        }

        @Override
        public Void visitContinue(ContinueTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.CONTINUE);
            return super.visitContinue(node, unused);
        }

        @Override
        public Void visitReturn(ReturnTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.RETURN);
            return super.visitReturn(node, unused);
        }

        @Override
        public Void visitThrow(ThrowTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.THROW);
            return super.visitThrow(node, unused);
        }

        @Override
        public Void visitTry(TryTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.TRY);
            return super.visitTry(node, unused);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.SYNCHRONIZED);
            return super.visitSynchronized(node, unused);
        }

        @Override
        public Void visitAssert(AssertTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.ASSERT);
            return super.visitAssert(node, unused);
        }

        @Override
        public Void visitYield(YieldTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.YIELD);
            return super.visitYield(node, unused);
        }

        @Override
        public Void visitLabeledStatement(LabeledStatementTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.LABELED);
            return super.visitLabeledStatement(node, unused);
        }

        @Override
        public Void visitEmptyStatement(EmptyStatementTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.EMPTY);
            return super.visitEmptyStatement(node, unused);
        }

        // --- ветвления: if

        @Override
        public Void visitIf(IfTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.IF);
            countBranching(GilbMetrics.BranchingKind.IF, node, level);
            scan(node.getCondition(), unused);
            scanNested(node.getThenStatement(), true);
            // else if — это if внутри ветки else, поэтому он на уровень глубже
            scanNested(node.getElseStatement(), true);
            return null;
        }

        // --- ветвления: циклы

        @Override
        public Void visitWhileLoop(WhileLoopTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.WHILE);
            boolean nested = recordLoop(GilbMetrics.BranchingKind.WHILE, node);
            scan(node.getCondition(), unused);
            scanNested(node.getStatement(), nested);
            return null;
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.DO_WHILE);
            boolean nested = recordLoop(GilbMetrics.BranchingKind.DO_WHILE, node);
            scanNested(node.getStatement(), nested);
            scan(node.getCondition(), unused);
            return null;
        }

        @Override
        public Void visitForLoop(ForLoopTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.FOR);
            boolean nested = recordLoop(GilbMetrics.BranchingKind.FOR, node);
            scan(node.getInitializer(), unused);
            scan(node.getCondition(), unused);
            scan(node.getUpdate(), unused);
            scanNested(node.getStatement(), nested);
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.FOR_EACH);
            boolean nested = recordLoop(GilbMetrics.BranchingKind.FOR_EACH, node);
            scan(node.getVariable(), unused);
            scan(node.getExpression(), unused);
            scanNested(node.getStatement(), nested);
            return null;
        }

        /** Записывает цикл как ветвление, если так настроено; true — тело вложено. */
        private boolean recordLoop(GilbMetrics.BranchingKind kind, Tree loop) {
            if (!options.loopsAreBranchings()) {
                return false;
            }
            countBranching(kind, loop, level);
            return true;
        }

        private void scanNested(Tree body, boolean deeper) {
            if (deeper) {
                level++;
            }
            scan(body, null);
            if (deeper) {
                level--;
            }
        }

        // --- ветвления: switch и ?:

        @Override
        public Void visitSwitch(SwitchTree node, Void unused) {
            countOperator(GilbMetrics.StatementKind.SWITCH);
            scanSwitch(node.getExpression(), node.getCases());
            return null;
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            scanSwitch(node.getExpression(), node.getCases());
            return null;
        }

        @Override
        public Void visitCase(CaseTree node, Void unused) {
            markContinuationDeclarators(node.getStatements());
            return super.visitCase(node, unused);
        }

        /**
         * switch с k метками {@code case} эквивалентен цепочке из k вложенных
         * if: метка j — это if на уровне {@code base + j − 1}, а оператор(ы) под
         * ней лежат внутри этого if. Ветка {@code default} — последний
         * {@code else} цепочки; селектор и {@code default} ветвлениями не
         * считаются.
         */
        private void scanSwitch(ExpressionTree selector, List<? extends CaseTree> cases) {
            scan(selector, null);
            int base = level;
            int labelCount = 0;
            for (CaseTree entry : cases) {
                for (CaseLabelTree label : entry.getLabels()) {
                    if (label.getKind() != Tree.Kind.DEFAULT_CASE_LABEL) {
                        labelCount++;
                    }
                }
            }
            int seen = 0;
            for (CaseTree entry : cases) {
                boolean hasDefault = false;
                for (CaseLabelTree label : entry.getLabels()) {
                    if (label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) {
                        hasDefault = true;
                    } else {
                        countBranching(GilbMetrics.BranchingKind.CASE, label, base + seen);
                        seen++;
                    }
                }
                level = hasDefault ? base + labelCount : base + seen;
                scan(entry, null);
            }
            level = base;
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
            if (!options.ternaryIsBranching()) {
                return super.visitConditionalExpression(node, unused);
            }
            countBranching(GilbMetrics.BranchingKind.TERNARY, node, level);
            scan(node.getCondition(), unused);
            level++;
            scan(node.getTrueExpression(), unused);
            scan(node.getFalseExpression(), unused);
            level--;
            return null;
        }
    }
}
