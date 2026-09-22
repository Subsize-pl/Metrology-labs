import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

public final class JavaHalsteadAnalyzer {
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue", "default",
            "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile",
            "while", "record", "sealed", "permits", "non-sealed", "var",
            "yield"));

    private static final Set<String> CONTROL_WITH_PARENTHESES =
            new HashSet<>(Arrays.asList(
                    "if", "for", "while", "switch", "catch", "synchronized"));

    private static final String[] SYMBOLS = {
            ">>>=", "<<=", ">>=", "...", "::", "->", "++", "--", "==",
            "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<", ">>>", ">>", "+", "-", "*", "/",
            "%", "=", "<", ">", "!", "~", "&", "|", "^", "?", ":",
            ".", ",", ";", "@", "(", ")", "[", "]", "{", "}"};

    public HalsteadMetrics analyze(String source) {
        List<Token> tokens = tokenize(source);
        SyntaxContext syntax = inspectSyntax(source, tokens);
        Map<String, Integer> operators = new LinkedHashMap<>();
        Map<String, Integer> operands = new LinkedHashMap<>();
        Deque<String> parenthesisKinds = new ArrayDeque<>();

        for (int index = 0; index < tokens.size(); index++) {
            if (syntax.ignored[index]) {
                continue;
            }

            Token current = tokens.get(index);
            Token previous = previousVisibleToken(tokens, syntax.ignored, index);
            Token next = nextVisibleToken(tokens, syntax.ignored, index);

            if (current.type == TokenType.IDENTIFIER
                    && next != null && "(".equals(next.text)) {
                increment(operators, current.text + "()");
                if (syntax.isInsideAssignment(current.start)) {
                    increment(operands, current.text);
                }
                continue;
            }

            if ("(".equals(current.text)) {
                if (previous != null && previous.type == TokenType.IDENTIFIER) {
                    parenthesisKinds.push("method");
                } else if (previous != null
                        && CONTROL_WITH_PARENTHESES.contains(previous.text)) {
                    parenthesisKinds.push("control");
                } else {
                    parenthesisKinds.push("group");
                    increment(operators, "()");
                }
                continue;
            }

            if (")".equals(current.text)) {
                if (!parenthesisKinds.isEmpty()) {
                    parenthesisKinds.pop();
                }
                continue;
            }

            if ("[".equals(current.text)) {
                increment(operators, "[]");
                continue;
            }
            if ("]".equals(current.text)) {
                continue;
            }
            if ("{".equals(current.text)) {
                increment(operators, "{}");
                continue;
            }
            if ("}".equals(current.text)) {
                continue;
            }

            if (current.type == TokenType.IDENTIFIER
                    || current.type == TokenType.LITERAL) {
                increment(operands, current.text);
            } else {
                increment(operators, current.text);
            }
        }

        return new HalsteadMetrics(operators, operands);
    }

    private static Token previousVisibleToken(List<Token> tokens,
                                              boolean[] ignored,
                                              int index) {
        for (int current = index - 1; current >= 0; current--) {
            if (!ignored[current]) {
                return tokens.get(current);
            }
        }
        return null;
    }

    private static Token nextVisibleToken(List<Token> tokens,
                                          boolean[] ignored,
                                          int index) {
        for (int current = index + 1; current < tokens.size(); current++) {
            if (!ignored[current]) {
                return tokens.get(current);
            }
        }
        return null;
    }

    private SyntaxContext inspectSyntax(String source, List<Token> tokens) {
        SyntaxContext context = new SyntaxContext(tokens);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return context;
        }

        JavaFileObject sourceFile = new SourceFile(source);
        try {
            JavacTask task = (JavacTask) compiler.getTask(
                    null, null, diagnostic -> { },
                    List.of("-proc:none"), null, List.of(sourceFile));
            CompilationUnitTree unit = task.parse().iterator().next();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            new DeclarationScanner(unit, positions, context).scan(unit, null);
        } catch (IOException | RuntimeException ignored) {
            // Если синтаксическое дерево не построено, остаётся лексический анализ.
        }
        return context;
    }

    private List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        while (position < source.length()) {
            char current = source.charAt(position);

            if (Character.isWhitespace(current)) {
                position++;
                continue;
            }

            if (startsWith(source, position, "//")) {
                position = skipLineComment(source, position + 2);
                continue;
            }
            if (startsWith(source, position, "/*")) {
                position = skipBlockComment(source, position + 2);
                continue;
            }

            if (current == '"') {
                ScanResult literal = scanQuoted(source, position, '"');
                tokens.add(new Token(literal.text, TokenType.LITERAL,
                        position, literal.nextPosition));
                position = literal.nextPosition;
                continue;
            }
            if (current == '\'') {
                ScanResult literal = scanQuoted(source, position, '\'');
                tokens.add(new Token(literal.text, TokenType.LITERAL,
                        position, literal.nextPosition));
                position = literal.nextPosition;
                continue;
            }

            if (Character.isJavaIdentifierStart(current)) {
                int end = position + 1;
                while (end < source.length()
                        && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                String word = source.substring(position, end);
                TokenType type = KEYWORDS.contains(word)
                        ? TokenType.OPERATOR : TokenType.IDENTIFIER;
                tokens.add(new Token(word, type, position, end));
                position = end;
                continue;
            }

            if (Character.isDigit(current)) {
                int end = scanNumber(source, position);
                tokens.add(new Token(source.substring(position, end),
                        TokenType.LITERAL, position, end));
                position = end;
                continue;
            }

            String symbol = findLongestSymbol(source, position);
            if (symbol != null) {
                tokens.add(new Token(symbol, TokenType.OPERATOR,
                        position, position + symbol.length()));
                position += symbol.length();
            } else {
                // Неизвестный символ сохраняется как оператор, а не теряется.
                tokens.add(new Token(String.valueOf(current), TokenType.OPERATOR,
                        position, position + 1));
                position++;
            }
        }
        return tokens;
    }

    private static int skipLineComment(String source, int position) {
        while (position < source.length() && source.charAt(position) != '\n') {
            position++;
        }
        return position;
    }

    private static int skipBlockComment(String source, int position) {
        while (position + 1 < source.length()
                && !startsWith(source, position, "*/")) {
            position++;
        }
        return Math.min(source.length(), position + 2);
    }

    private static ScanResult scanQuoted(String source, int start, char quote) {
        int position = start + 1;
        boolean escaped = false;
        while (position < source.length()) {
            char current = source.charAt(position++);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                break;
            }
        }
        return new ScanResult(source.substring(start, position), position);
    }

    private static int scanNumber(String source, int start) {
        int position = start + 1;
        while (position < source.length()) {
            char current = source.charAt(position);
            if (Character.isLetterOrDigit(current) || current == '_'
                    || current == '.') {
                position++;
            } else if ((current == '+' || current == '-') && position > start
                    && (source.charAt(position - 1) == 'e'
                    || source.charAt(position - 1) == 'E'
                    || source.charAt(position - 1) == 'p'
                    || source.charAt(position - 1) == 'P')) {
                position++;
            } else {
                break;
            }
        }
        return position;
    }

    private static String findLongestSymbol(String source, int position) {
        for (String symbol : SYMBOLS) {
            if (startsWith(source, position, symbol)) {
                return symbol;
            }
        }
        return null;
    }

    private static boolean startsWith(String source, int position,
                                      String expected) {
        return source.regionMatches(position, expected, 0, expected.length());
    }

    private static void increment(Map<String, Integer> frequencies,
                                  String token) {
        frequencies.merge(token, 1, Integer::sum);
    }

    private enum TokenType {
        IDENTIFIER,
        LITERAL,
        OPERATOR
    }

    private static final class Token {
        private final String text;
        private final TokenType type;
        private final int start;
        private final int end;

        private Token(String text, TokenType type, int start, int end) {
            this.text = text;
            this.type = type;
            this.start = start;
            this.end = end;
        }
    }

    private static final class ScanResult {
        private final String text;
        private final int nextPosition;

        private ScanResult(String text, int nextPosition) {
            this.text = text;
            this.nextPosition = nextPosition;
        }
    }

    private static final class SourceRange {
        private final long start;
        private final long end;

        private SourceRange(long start, long end) {
            this.start = start;
            this.end = end;
        }

        private boolean contains(int position) {
            return position >= start && position < end;
        }
    }

    private static final class SyntaxContext {
        private final List<Token> tokens;
        private final boolean[] ignored;
        private final List<SourceRange> assignmentRanges = new ArrayList<>();

        private SyntaxContext(List<Token> tokens) {
            this.tokens = tokens;
            ignored = new boolean[tokens.size()];
        }

        private void ignore(long start, long end) {
            if (start < 0 || end < start) {
                return;
            }
            for (int index = 0; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if (token.start >= start && token.end <= end) {
                    ignored[index] = true;
                }
            }
        }

        private void ignoreToken(int index) {
            if (index >= 0 && index < ignored.length) {
                ignored[index] = true;
            }
        }

        private void addAssignmentRange(long start, long end) {
            if (start >= 0 && end >= start) {
                assignmentRanges.add(new SourceRange(start, end));
            }
        }

        private boolean isInsideAssignment(int position) {
            for (SourceRange range : assignmentRanges) {
                if (range.contains(position)) {
                    return true;
                }
            }
            return false;
        }

        private int findLastToken(String text, long start, long end) {
            for (int index = tokens.size() - 1; index >= 0; index--) {
                Token token = tokens.get(index);
                if (token.start >= start && token.end <= end
                        && text.equals(token.text)) {
                    return index;
                }
            }
            return -1;
        }

        private int findLastIdentifier(String name, long start, long end) {
            for (int index = tokens.size() - 1; index >= 0; index--) {
                Token token = tokens.get(index);
                if (token.start >= start && token.end <= end
                        && token.type == TokenType.IDENTIFIER
                        && name.equals(token.text)) {
                    return index;
                }
            }
            return -1;
        }

        private long findClassBodyStart(long start, long end) {
            for (Token token : tokens) {
                if (token.start >= start && token.end <= end
                        && "{".equals(token.text)) {
                    return token.start;
                }
            }
            return -1;
        }

        private void ignoreFollowingDelimiter(long end) {
            for (int index = 0; index < tokens.size(); index++) {
                Token token = tokens.get(index);
                if (token.start >= end) {
                    if (";".equals(token.text) || ",".equals(token.text)) {
                        ignoreToken(index);
                    }
                    return;
                }
            }
        }

        private void ignorePreviousComma(long start) {
            for (int index = tokens.size() - 1; index >= 0; index--) {
                Token token = tokens.get(index);
                if (token.end <= start) {
                    if (",".equals(token.text)) {
                        ignoreToken(index);
                    }
                    return;
                }
            }
        }
    }

    private static final class DeclarationScanner
            extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final SyntaxContext context;

        private DeclarationScanner(CompilationUnitTree unit,
                                   SourcePositions positions,
                                   SyntaxContext context) {
            this.unit = unit;
            this.positions = positions;
            this.context = context;
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            long start = start(node);
            long end = end(node);
            context.ignore(start, end);
            context.ignoreFollowingDelimiter(end);
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            long declarationStart = start(node);
            long declarationEnd = end(node);
            long bodyStart = context.findClassBodyStart(
                    declarationStart, declarationEnd);
            if (bodyStart >= 0) {
                context.ignore(declarationStart, bodyStart);
            } else {
                context.ignore(declarationStart, declarationEnd);
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            long declarationStart = start(node);
            long declarationEnd = end(node);
            if (node.getBody() == null) {
                context.ignore(declarationStart, declarationEnd);
                context.ignoreFollowingDelimiter(declarationEnd);
            } else {
                context.ignore(declarationStart, start(node.getBody()));
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            long declarationStart = start(node);
            long declarationEnd = end(node);
            if (node.getInitializer() == null) {
                context.ignore(declarationStart, declarationEnd);
                context.ignoreFollowingDelimiter(declarationEnd);
            } else {
                long initializerStart = start(node.getInitializer());
                long initializerEnd = end(node.getInitializer());
                int assignment = context.findLastToken(
                        "=", declarationStart, initializerStart);
                if (assignment >= 0) {
                    Token equalsToken = context.tokens.get(assignment);
                    int variableName = context.findLastIdentifier(
                            node.getName().toString(),
                            declarationStart, equalsToken.start);
                    context.ignore(declarationStart, equalsToken.start);
                    if (variableName >= 0) {
                        context.ignored[variableName] = false;
                    }
                    context.ignorePreviousComma(declarationStart);
                }
                context.addAssignmentRange(initializerStart, initializerEnd);
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            context.addAssignmentRange(
                    start(node.getExpression()), end(node.getExpression()));
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node,
                                            Void unused) {
            context.addAssignmentRange(
                    start(node.getExpression()), end(node.getExpression()));
            return super.visitCompoundAssignment(node, unused);
        }

        private long start(Tree tree) {
            return positions.getStartPosition(unit, tree);
        }

        private long end(Tree tree) {
            return positions.getEndPosition(unit, tree);
        }
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
}
