import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

/** Окно программы и консольные режимы анализатора метрики Джилба. */
public final class GilbAnalyzerApp {
    private static final String SAMPLE_FILE = "NumberReport.java";
    private static final int LIVE_DELAY_MS = 300;

    private static final Color ACCENT = new Color(0x245C7A);
    private static final Color MUTED = new Color(0x57646E);
    private static final Color BORDER = new Color(0xC3CED5);
    private static final Color OK = new Color(0x3F7A56);
    private static final Color WARNING = new Color(0xA8630F);
    private static final Color IF_BACKGROUND = new Color(0xFBEFD5);
    private static final Color LOOP_BACKGROUND = new Color(0xDDEBF5);
    private static final Color CASE_BACKGROUND = new Color(0xDDF0E3);
    private static final Color TERNARY_BACKGROUND = new Color(0xEBE1F5);

    private final GilbAnalyzer analyzer = new GilbAnalyzer();
    private final JTextArea sourceArea = new JTextArea();

    private final JCheckBox loopsBox = optionBox("Циклы — ветвления", true,
            "Страница «Задание 2»: ветвления — это операторы условия, циклы и switch");
    private final JCheckBox ternaryBox = optionBox("Тернарный ?: — ветвление", false,
            "По JLS ?: — выражение (гл. 15), а не оператор (гл. 14); по умолчанию не считается");
    private final JCheckBox declarationsBox = optionBox("Объявления — операторы", true,
            "JLS 14.4: объявление локальной переменной — исполняемый оператор");
    private final JCheckBox blocksBox = optionBox("Блоки { } — операторы", false,
            "JLS 14.5 относит блок к операторам, Java Tutorial — лишь к группировке");

    private final MetricCard absoluteCard = new MetricCard("CL", "абсолютная сложность")
            .withTooltip("Сумма ветвлений: if, циклы и метки case "
                    + "(селектор switch и default не считаются)");
    private final MetricCard relativeCard = new MetricCard("cl", "относительная сложность")
            .withTooltip("cl = CL / N, где N — число операторов языка по JLS, гл. 14");
    private final MetricCard nestingCard = new MetricCard("CLI", "максимальная вложенность")
            .withTooltip("Уровень не вложенного ветвления — 0. switch с n ветвями "
                    + "эквивалентен n − 1 вложенным if и даёт вложенность n − 2");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");

    private final DefaultTableModel branchingModel =
            readOnlyModel("№", "Строка", "Ветвление", "Уровень", "Код");
    private final DefaultTableModel statementModel =
            readOnlyModel("Оператор", "JLS §", "Кол-во");
    private final DefaultTableModel methodModel = readOnlyModel(
            "Подпрограмма", "Строка", "Операторов", "CL", "Вложенность", "Z = CL + 1");
    private final JTextArea calculationArea = new JTextArea();
    private final Timer liveTimer;
    private final JPanel mainPanel;

    public GilbAnalyzerApp() {
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        sourceArea.setTabSize(4);
        calculationArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        calculationArea.setEditable(false);
        calculationArea.setMargin(new Insets(8, 10, 8, 10));
        summaryLabel.setForeground(MUTED);

        // Результат пересчитывается сам, пока в тексте вносят правки.
        liveTimer = new Timer(LIVE_DELAY_MS, event -> analyzeAndDisplay());
        liveTimer.setRepeats(false);
        sourceArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                liveTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                liveTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                liveTimer.restart();
            }
        });
        for (JCheckBox box : List.of(loopsBox, ternaryBox, declarationsBox, blocksBox)) {
            box.addActionListener(event -> analyzeAndDisplay());
        }
        mainPanel = buildMainPanel();
    }

    // ------------------------------------------------------------ интерфейс

    private JPanel buildMainPanel() {
        JButton openButton = new JButton("Открыть файл…");
        openButton.addActionListener(event -> chooseSourceFile());
        JButton analyzeButton = new JButton("Рассчитать");
        analyzeButton.addActionListener(event -> analyzeAndDisplay());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(openButton);
        toolbar.add(analyzeButton);
        toolbar.add(loopsBox);
        toolbar.add(ternaryBox);
        toolbar.add(declarationsBox);
        toolbar.add(blocksBox);

        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.setRowHeaderView(new LineNumbers(sourceArea));
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Исходный код Java"));

        JPanel cards = new JPanel(new GridLayout(1, 3, 8, 0));
        cards.add(absoluteCard);
        cards.add(relativeCard);
        cards.add(nestingCard);
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(cards, BorderLayout.NORTH);
        header.add(summaryLabel, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Ветвления", scrollOf(branchingTable()));
        tabs.addTab("Операторы N", scrollOf(table(statementModel)));
        tabs.addTab("Подпрограммы", scrollOf(table(methodModel)));
        tabs.addTab("Расчёт", new JScrollPane(calculationArea));

        JPanel resultPanel = new JPanel(new BorderLayout(0, 8));
        resultPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        resultPanel.add(header, BorderLayout.NORTH);
        resultPanel.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourceScroll, resultPanel);
        split.setResizeWeight(0.5);
        split.setDividerLocation(660);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JTable branchingTable() {
        JTable table = table(branchingModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(420);
        table.getSelectionModel().addListSelectionListener(event -> {
            int row = table.getSelectedRow();
            if (!event.getValueIsAdjusting() && row >= 0) {
                jumpToLine((Integer) branchingModel.getValueAt(row, 1));
            }
        });
        return table;
    }

    private static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        return table;
    }

    /** Заголовок задаётся явно, чтобы он был виден и на снимке без показа окна. */
    private static JScrollPane scrollOf(JTable table) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader());
        return scroll;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JCheckBox optionBox(String text, boolean selected, String tooltip) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setToolTipText(tooltip);
        return box;
    }

    // ---------------------------------------------------------------- логика

    private GilbOptions currentOptions() {
        return new GilbOptions(loopsBox.isSelected(), ternaryBox.isSelected(),
                declarationsBox.isSelected(), blocksBox.isSelected());
    }

    private void chooseSourceFile() {
        JFileChooser chooser = new JFileChooser(Path.of("").toAbsolutePath().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Исходники Java (*.java)", "java"));
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                loadSource(chooser.getSelectedFile().toPath());
                analyzeAndDisplay();
            } catch (IOException exception) {
                JOptionPane.showMessageDialog(mainPanel,
                        "Не удалось прочитать файл: " + exception.getMessage(),
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void loadSource(Path path) throws IOException {
        sourceArea.setText(readSource(path));
        sourceArea.setCaretPosition(0);
    }

    /** UTF-8, а если файл в нём не читается — Windows-1251 (типично для учебных файлов). */
    static String readSource(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }

    private GilbMetrics analyzeAndDisplay() {
        liveTimer.stop();
        String source = sourceArea.getText();
        GilbMetrics metrics;
        try {
            metrics = analyzer.analyze(source, currentOptions());
        } catch (RuntimeException failure) {
            statusLabel.setForeground(WARNING);
            statusLabel.setText("Анализ не выполнен: " + failure.getMessage());
            return null;
        }
        display(metrics, source);
        return metrics;
    }

    private void display(GilbMetrics metrics, String source) {
        int cl = metrics.absoluteComplexity();
        int n = metrics.operatorCount();
        absoluteCard.show(String.valueOf(cl), "число ветвлений");
        if (n == 0) {
            relativeCard.show("—", "операторов нет");
        } else {
            relativeCard.show(GilbReport.ratio(metrics.relativeComplexity()), cl + " / " + n);
        }
        nestingCard.show(String.valueOf(metrics.maxNesting()), deepestHint(metrics));
        summaryLabel.setText("Операторов N = " + n + "  ·  подпрограмм: " + metrics.methods().size()
                + "  ·  цикломатическое число Маккейба Z = " + metrics.cyclomaticNumber());

        fillBranchings(metrics, source);
        fillStatements(metrics);
        fillMethods(metrics);
        calculationArea.setText(GilbReport.calculation(metrics));
        calculationArea.setCaretPosition(0);
        highlightBranchingLines(metrics);
        showStatus(metrics, source);
    }

    private static String deepestHint(GilbMetrics metrics) {
        if (metrics.branchings().isEmpty()) {
            return "ветвлений нет";
        }
        if (metrics.maxNesting() == 0) {
            return "вложенных ветвлений нет";
        }
        for (GilbMetrics.Branching branching : metrics.branchings()) {
            if (branching.level() == metrics.maxNesting()) {
                return "глубже всего: строка " + branching.line()
                        + " (" + branching.kind().title() + ")";
            }
        }
        return "";
    }

    private void fillBranchings(GilbMetrics metrics, String source) {
        String[] lines = source.split("\n", -1);
        branchingModel.setRowCount(0);
        int index = 1;
        for (GilbMetrics.Branching branching : metrics.branchings()) {
            String code = branching.line() <= lines.length
                    ? lines[branching.line() - 1].strip() : "";
            branchingModel.addRow(new Object[]{index++, branching.line(),
                    branching.kind().title(), branching.level(), code});
        }
    }

    private void fillStatements(GilbMetrics metrics) {
        statementModel.setRowCount(0);
        for (Map.Entry<GilbMetrics.StatementKind, Integer> entry
                : metrics.statementCount().entrySet()) {
            statementModel.addRow(new Object[]{entry.getKey().description(),
                    entry.getKey().section(), entry.getValue()});
        }
        statementModel.addRow(new Object[]{"Итого N", "", metrics.operatorCount()});
    }

    private void fillMethods(GilbMetrics metrics) {
        methodModel.setRowCount(0);
        for (GilbMetrics.MethodStats method : metrics.methods()) {
            methodModel.addRow(new Object[]{method.name(), method.line(), method.operators(),
                    method.branchings(), method.maxNesting(), method.cyclomaticNumber()});
        }
    }

    private void showStatus(GilbMetrics metrics, String source) {
        if (metrics.problems().isEmpty()) {
            statusLabel.setForeground(OK);
            statusLabel.setText(source.isBlank()
                    ? "Код пуст — откройте файл или вставьте текст программы"
                    : "Разбор: " + metrics.mode().title() + " · синтаксических ошибок нет");
        } else {
            GilbMetrics.SyntaxProblem first = metrics.problems().get(0);
            statusLabel.setForeground(WARNING);
            statusLabel.setText("Синтаксических ошибок: " + metrics.problems().size()
                    + " (строка " + first.line() + ": " + first.message()
                    + ") — метрики приблизительные");
        }
    }

    // ------------------------------------------------------------ подсветка

    private void highlightBranchingLines(GilbMetrics metrics) {
        Highlighter highlighter = sourceArea.getHighlighter();
        highlighter.removeAllHighlights();
        int lastLine = -1;
        for (GilbMetrics.Branching branching : metrics.branchings()) {
            if (branching.line() == lastLine) {
                continue;
            }
            lastLine = branching.line();
            try {
                int start = sourceArea.getLineStartOffset(branching.line() - 1);
                highlighter.addHighlight(start, start + 1,
                        new LineBackground(backgroundOf(branching.kind())));
            } catch (BadLocationException outdated) {
                // Текст изменился после разбора — подсветка этой строки не нужна.
            }
        }
    }

    private static Color backgroundOf(GilbMetrics.BranchingKind kind) {
        return switch (kind) {
            case IF -> IF_BACKGROUND;
            case CASE -> CASE_BACKGROUND;
            case TERNARY -> TERNARY_BACKGROUND;
            case WHILE, DO_WHILE, FOR, FOR_EACH -> LOOP_BACKGROUND;
        };
    }

    private void jumpToLine(int line) {
        try {
            int start = sourceArea.getLineStartOffset(line - 1);
            int end = sourceArea.getLineEndOffset(line - 1);
            sourceArea.select(start, Math.max(start, end - 1));
            Rectangle2D area = sourceArea.modelToView2D(start);
            sourceArea.scrollRectToVisible(area.getBounds());
        } catch (BadLocationException outdated) {
            // Строки уже нет: таблица обновится при следующем пересчёте.
        }
    }

    /** Заливает всю ширину строки, а не только её текст. */
    private static final class LineBackground implements Highlighter.HighlightPainter {
        private final Color color;

        private LineBackground(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int start, int end, Shape bounds, JTextComponent component) {
            try {
                Rectangle2D line = component.modelToView2D(start);
                g.setColor(color);
                g.fillRect(0, (int) line.getY(), component.getWidth(), (int) line.getHeight());
            } catch (BadLocationException outdated) {
                // Строка исчезла между разбором и перерисовкой.
            }
        }
    }

    /** Колонка номеров строк рядом с редактором. */
    @SuppressWarnings("serial")
    private static final class LineNumbers extends JComponent {
        private final JTextArea area;

        private LineNumbers(JTextArea area) {
            this.area = area;
            setOpaque(true);
            setBackground(new Color(0xEDF1F3));
            setForeground(MUTED);
            area.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    refresh();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    refresh();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    refresh();
                }
            });
        }

        private void refresh() {
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(area.getFont());
            int digits = Math.max(3, String.valueOf(area.getLineCount()).length());
            return new Dimension(metrics.charWidth('0') * digits + 14, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(getForeground());
            g.setFont(area.getFont());
            FontMetrics metrics = g.getFontMetrics();
            int lineHeight = metrics.getHeight();
            int top = area.getInsets().top;
            Rectangle clip = g.getClipBounds();
            int first = Math.max(0, (clip.y - top) / lineHeight);
            int last = Math.min(area.getLineCount() - 1, (clip.y + clip.height - top) / lineHeight);
            for (int line = first; line <= last; line++) {
                String number = String.valueOf(line + 1);
                g.drawString(number, getWidth() - 7 - metrics.stringWidth(number),
                        top + line * lineHeight + metrics.getAscent());
            }
        }
    }

    /** Плитка с крупным значением метрики. */
    @SuppressWarnings("serial")
    private static final class MetricCard extends JPanel {
        private final JLabel valueLabel = new JLabel("—", SwingConstants.CENTER);
        private final JLabel hintLabel = new JLabel(" ", SwingConstants.CENTER);

        private MetricCard(String symbol, String title) {
            super(new BorderLayout(0, 2));
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            JLabel caption = new JLabel(symbol + " · " + title, SwingConstants.CENTER);
            caption.setForeground(MUTED);
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 34f));
            valueLabel.setForeground(ACCENT);
            hintLabel.setForeground(MUTED);
            hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11f));
            add(caption, BorderLayout.NORTH);
            add(valueLabel, BorderLayout.CENTER);
            add(hintLabel, BorderLayout.SOUTH);
        }

        private void show(String value, String hint) {
            valueLabel.setText(value);
            hintLabel.setText(hint);
        }

        private MetricCard withTooltip(String tooltip) {
            for (JComponent part : List.of(this, valueLabel, hintLabel)) {
                part.setToolTipText(tooltip);
            }
            return this;
        }
    }

    // ------------------------------------------------------- точки входа

    private static void showWindow(Path initialFile) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Останется стандартное оформление Swing.
        }
        GilbAnalyzerApp app = new GilbAnalyzerApp();
        if (initialFile != null) {
            try {
                app.loadSource(initialFile);
            } catch (IOException exception) {
                app.statusLabel.setForeground(WARNING);
                app.statusLabel.setText("Не удалось прочитать " + initialFile + ": "
                        + exception.getMessage());
            }
        }
        app.analyzeAndDisplay();
        JFrame frame = new JFrame("Метрики Джилба и Маккейба — анализатор Java");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(app.mainPanel);
        frame.setMinimumSize(new Dimension(1000, 650));
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void saveScreenshot(Path source, Path output, GilbOptions options)
            throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            try {
                GilbAnalyzerApp app = new GilbAnalyzerApp();
                app.loopsBox.setSelected(options.loopsAreBranchings());
                app.ternaryBox.setSelected(options.ternaryIsBranching());
                app.declarationsBox.setSelected(options.declarationsAreOperators());
                app.blocksBox.setSelected(options.blocksAreOperators());
                app.loadSource(source);
                app.analyzeAndDisplay();
                app.mainPanel.setSize(new Dimension(1400, 900));
                layoutRecursively(app.mainPanel);

                BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                app.mainPanel.printAll(graphics);
                graphics.dispose();
                Files.createDirectories(output.toAbsolutePath().getParent());
                ImageIO.write(image, "png", output.toFile());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /** Флаги {@code --no-loops --ternary --no-declarations --blocks} → настройки. */
    private static GilbOptions parseOptions(List<String> flags) {
        return new GilbOptions(
                !flags.contains("--no-loops"),
                flags.contains("--ternary"),
                !flags.contains("--no-declarations"),
                flags.contains("--blocks"));
    }

    private static void printUsage() {
        System.out.println("Использование:");
        System.out.println("  java GilbAnalyzerApp                      окно программы");
        System.out.println("  java GilbAnalyzerApp <file.java>          окно с открытым файлом");
        System.out.println("  java GilbAnalyzerApp --analyze <file.java> [флаги]");
        System.out.println("  java GilbAnalyzerApp --screenshot <file.java> <out.png> [флаги]");
        System.out.println("Флаги: --no-loops  --ternary  --no-declarations  --blocks");
    }

    public static void main(String[] arguments) throws Exception {
        List<String> args = List.of(arguments);
        if (args.isEmpty()) {
            Path sample = Path.of(SAMPLE_FILE);
            SwingUtilities.invokeLater(() -> showWindow(Files.isRegularFile(sample) ? sample : null));
        } else if ("--analyze".equals(args.get(0)) && args.size() >= 2) {
            Path file = Path.of(args.get(1));
            try {
                String source = readSource(file);
                GilbOptions options = parseOptions(args.subList(2, args.size()));
                System.out.println(GilbReport.console(
                        new GilbAnalyzer().analyze(source, options), source));
            } catch (IOException exception) {
                System.err.println("Не удалось прочитать файл " + file + ": " + exception);
                System.exit(2);
            }
        } else if ("--screenshot".equals(args.get(0)) && args.size() >= 3) {
            saveScreenshot(Path.of(args.get(1)), Path.of(args.get(2)),
                    parseOptions(args.subList(3, args.size())));
        } else if (args.size() == 1 && !args.get(0).startsWith("--")) {
            Path file = Path.of(args.get(0));
            SwingUtilities.invokeLater(() -> showWindow(file));
        } else {
            printUsage();
        }
    }
}
