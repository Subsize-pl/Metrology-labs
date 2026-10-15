import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Метрика Джилба для кода на Java: CL — число ветвлений (if, else if, циклы, метки case),
 * N — число операторов, cl = CL / N, CLI — наибольшая вложенность ветвлений (внешнее — 0).
 * switch считается как цепочка вложенных if.
 */
public final class GilbAnalyzer {

    /** Ветвление: строка, вид, условие и уровень вложенности. */
    public record Branching(int line, String kind, String condition, int level) {
    }

    public record Result(int operators, List<Branching> branchings,
                         Map<String, Integer> operatorKinds, List<String> errors) {
        public int absolute() {
            return branchings.size();
        }

        public double relative() {
            return operators == 0 ? 0 : (double) absolute() / operators;
        }

        public int maxNesting() {
            return branchings.stream().mapToInt(Branching::level).max().orElse(0);
        }
    }

    private GilbAnalyzer() {
    }

    /** Сначала читаем как целый файл, если не вышло — как набор операторов без класса. */
    public static Result analyze(String source) {
        // парсер рекурсивный, на глубокой вложенности нужен большой стек
        Object[] box = new Object[1];
        Thread worker = new Thread(null, () -> {
            try {
                box[0] = analyzeText(source);
            } catch (Throwable e) {
                box[0] = e;
            }
        }, "gilb-analyzer", 1L << 30);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (box[0] instanceof Result result) {
            return result;
        }
        throw new IllegalStateException("Не удалось разобрать код: " + box[0]);
    }

    private static Result analyzeText(String source) {
        String text = source.startsWith("﻿") ? source.substring(1) : source;
        Result whole = analyze(text, "", 0);
        if (whole.errors().isEmpty()) {
            return whole;
        }
        Result fragment = analyze(text, "class Fragment { void fragment() {\n", 1);
        return fragment.errors().size() < whole.errors().size() ? fragment : whole;
    }

    private static Result analyze(String text, String prefix, int lineShift) {
        String code = prefix.isEmpty() ? text : prefix + text + "\n} }";
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Нужен JDK (модуль jdk.compiler), а не JRE");
        }
        var task = (JavacTask) compiler.getTask(null, null, diagnostics,
                List.of("-proc:none"), null, List.of(new Source(code)));
        CompilationUnitTree unit;
        try {
            unit = task.parse().iterator().next();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        var counter = new Counter(code, unit, Trees.instance(task).getSourcePositions(), lineShift);
        counter.scan(new TreePath(unit), null);

        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add("строка " + Math.max(1, d.getLineNumber() - lineShift) + ": "
                        + d.getMessage(Locale.getDefault()));
            }
        }
        return new Result(counter.operators, counter.branchings, counter.kinds, errors);
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String code) {
            super(URI.create("string:///Analyzed.java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** Один проход по дереву: считает операторы и ветвления. */
    private static final class Counter extends TreePathScanner<Void, Void> {
        private final String code;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final int lineShift;

        final List<Branching> branchings = new ArrayList<>();
        final Map<String, Integer> kinds = new LinkedHashMap<>();
        int operators;
        private int level;
        private long lastDeclarationStart = -1;

        Counter(String code, CompilationUnitTree unit, SourcePositions positions, int lineShift) {
            this.code = code;
            this.unit = unit;
            this.positions = positions;
            this.lineShift = lineShift;
        }

        private void operator(String kind) {
            operators++;
            kinds.merge(kind, 1, Integer::sum);
        }

        private void branching(String kind, Tree at, String condition) {
            long start = positions.getStartPosition(unit, at);
            int line = (int) Math.max(1, unit.getLineMap().getLineNumber(start) - lineShift);
            branchings.add(new Branching(line, kind, condition, level));
        }

        private String text(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return "";
            }
            String s = code.substring((int) start, (int) end).replaceAll("\\s+", " ").trim();
            return s.startsWith("(") && s.endsWith(")") ? s.substring(1, s.length() - 1) : s;
        }

        private Tree parent() {
            return getCurrentPath().getParentPath().getLeaf();
        }

        /** Тело ветвления на уровень глубже. */
        private void nested(Tree body) {
            level++;
            scan(body, null);
            level--;
        }

        // ветвления

        @Override
        public Void visitIf(IfTree node, Void v) {
            operator("if");
            boolean elseIf = parent() instanceof IfTree outer && outer.getElseStatement() == node;
            branching(elseIf ? "else if" : "if", node, text(node.getCondition()));
            nested(node.getThenStatement());
            nested(node.getElseStatement()); // else if — это if внутри else, поэтому глубже
            return null;
        }

        @Override
        public Void visitForLoop(ForLoopTree node, Void v) {
            operator("for");
            branching("for", node, node.getCondition() == null ? "true" : text(node.getCondition()));
            nested(node.getStatement());
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void v) {
            operator("for-each");
            branching("for-each", node, node.getVariable().getName() + " : " + text(node.getExpression()));
            nested(node.getStatement());
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree node, Void v) {
            operator("while");
            branching("while", node, text(node.getCondition()));
            nested(node.getStatement());
            return null;
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree node, Void v) {
            operator("do-while");
            branching("do-while", node, text(node.getCondition()));
            nested(node.getStatement());
            return null;
        }

        @Override
        public Void visitSwitch(SwitchTree node, Void v) {
            operator("switch");
            switchAsIfs(node.getExpression(), node.getCases());
            return null;
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void v) {
            switchAsIfs(node.getExpression(), node.getCases());
            return null;
        }

        /** k-я метка case — это if на уровне base + k - 1, default — последний else. */
        private void switchAsIfs(ExpressionTree selector, List<? extends CaseTree> cases) {
            int base = level;
            int labels = 0;
            for (CaseTree c : cases) {
                for (CaseLabelTree label : c.getLabels()) {
                    if (!(label instanceof DefaultCaseLabelTree)) {
                        labels++;
                    }
                }
            }
            int seen = 0;
            for (CaseTree c : cases) {
                boolean isDefault = false;
                for (CaseLabelTree label : c.getLabels()) {
                    if (label instanceof DefaultCaseLabelTree) {
                        isDefault = true;
                    } else {
                        level = base + seen++;
                        branching("case", label, text(selector) + " == " + text(label));
                    }
                }
                level = base + (isDefault ? labels : seen);
                scan(c.getStatements() != null ? c.getStatements() : List.of(c.getBody()), null);
            }
            level = base;
        }

        // остальные операторы

        @Override
        public Void visitVariable(VariableTree node, Void v) {
            // поля, параметры и переменные в заголовках for/catch не считаем;
            // int a = 1, b = 2; — один оператор
            Tree p = parent();
            long start = positions.getStartPosition(unit, node);
            if ((p instanceof BlockTree || p instanceof CaseTree) && start != lastDeclarationStart) {
                operator("объявление переменной");
            }
            lastDeclarationStart = start;
            return super.visitVariable(node, v);
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree node, Void v) {
            // заголовок for не обходим, так что i = 0 и i++ сюда не попадают
            operator("выражение (присваивание, ++, вызов)");
            return super.visitExpressionStatement(node, v);
        }

        @Override
        public Void visitBreak(BreakTree node, Void v) {
            operator("break");
            return null;
        }

        @Override
        public Void visitContinue(ContinueTree node, Void v) {
            operator("continue");
            return null;
        }

        @Override
        public Void visitReturn(ReturnTree node, Void v) {
            operator("return");
            return super.visitReturn(node, v);
        }

        @Override
        public Void visitThrow(ThrowTree node, Void v) {
            operator("throw");
            return super.visitThrow(node, v);
        }

        @Override
        public Void visitTry(TryTree node, Void v) {
            operator("try");
            return super.visitTry(node, v);
        }

        @Override
        public Void visitYield(YieldTree node, Void v) {
            operator("yield");
            return super.visitYield(node, v);
        }

        @Override
        public Void visitAssert(AssertTree node, Void v) {
            operator("assert");
            return super.visitAssert(node, v);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree node, Void v) {
            operator("synchronized");
            return super.visitSynchronized(node, v);
        }

        @Override
        public Void visitLabeledStatement(LabeledStatementTree node, Void v) {
            operator("метка");
            return super.visitLabeledStatement(node, v);
        }
    }
}
