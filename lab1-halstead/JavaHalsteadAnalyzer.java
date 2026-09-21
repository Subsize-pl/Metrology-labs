import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Map<String, Integer> operators = new LinkedHashMap<>();
        Map<String, Integer> operands = new LinkedHashMap<>();
        Deque<String> parenthesisKinds = new ArrayDeque<>();

        for (int index = 0; index < tokens.size(); index++) {
            Token current = tokens.get(index);
            Token previous = index > 0 ? tokens.get(index - 1) : null;
            Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;

            if (current.type == TokenType.IDENTIFIER
                    && next != null && "(".equals(next.text)) {
                increment(operators, current.text + "()");
                increment(operands, current.text);
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
                tokens.add(new Token(literal.text, TokenType.LITERAL));
                position = literal.nextPosition;
                continue;
            }
            if (current == '\'') {
                ScanResult literal = scanQuoted(source, position, '\'');
                tokens.add(new Token(literal.text, TokenType.LITERAL));
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
                tokens.add(new Token(word, type));
                position = end;
                continue;
            }

            if (Character.isDigit(current)) {
                int end = scanNumber(source, position);
                tokens.add(new Token(source.substring(position, end),
                        TokenType.LITERAL));
                position = end;
                continue;
            }

            String symbol = findLongestSymbol(source, position);
            if (symbol != null) {
                tokens.add(new Token(symbol, TokenType.OPERATOR));
                position += symbol.length();
            } else {
                // Preserve an unknown character as an operator instead of hiding it.
                tokens.add(new Token(String.valueOf(current), TokenType.OPERATOR));
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

        private Token(String text, TokenType type) {
            this.text = text;
            this.type = type;
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
}
