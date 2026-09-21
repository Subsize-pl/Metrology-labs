import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;

/** Graphical front end and command-line entry point for the analyzer. */
public final class HalsteadAnalyzerApp {
    private final JavaHalsteadAnalyzer analyzer = new JavaHalsteadAnalyzer();
    private final JTextArea sourceArea = new JTextArea();
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"№", "Оператор", "f1j", "№", "Операнд", "f2i"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable tokenTable = new JTable(tableModel);
    private final JLabel baseMetricsLabel = new JLabel("Базовые метрики: —");
    private final JLabel derivedMetricsLabel = new JLabel("Расширенные метрики: —");
    private final JPanel mainPanel;

    public HalsteadAnalyzerApp() {
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        sourceArea.setTabSize(4);
        tokenTable.setRowHeight(23);
        tokenTable.setAutoCreateRowSorter(true);
        tokenTable.setTableHeader(null);
        mainPanel = buildMainPanel();
    }

    private JPanel buildMainPanel() {
        JButton openButton = new JButton("Открыть Java-файл");
        openButton.addActionListener(event -> chooseSourceFile());

        JButton analyzeButton = new JButton("Рассчитать метрики");
        analyzeButton.addActionListener(event -> analyzeAndDisplay());

        JPanel toolbar = new JPanel();
        toolbar.add(openButton);
        toolbar.add(analyzeButton);

        JPanel metricsPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        metricsPanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        metricsPanel.add(baseMetricsLabel);
        metricsPanel.add(derivedMetricsLabel);

        JScrollPane sourceScroll = new JScrollPane(sourceArea);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Исходный код Java"));
        JScrollPane tableScroll = new JScrollPane(tokenTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder(
                "Частоты операторов и операндов"));
        JPanel tableHeader = new JPanel(new GridLayout(1, 6));
        for (String title : new String[]{"№", "Оператор", "f1j", "№", "Операнд", "f2i"}) {
            JLabel headerLabel = new JLabel(title, JLabel.CENTER);
            headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
            headerLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            tableHeader.add(headerLabel);
        }
        tablePanel.add(tableHeader, BorderLayout.NORTH);
        tablePanel.add(tableScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, sourceScroll, tablePanel);
        splitPane.setResizeWeight(0.52);
        splitPane.setDividerLocation(700);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);
        panel.add(metricsPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void chooseSourceFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                loadSource(chooser.getSelectedFile().toPath());
                analyzeAndDisplay();
            } catch (IOException exception) {
                showError("Не удалось прочитать файл", exception);
            }
        }
    }

    private void loadSource(Path sourcePath) throws IOException {
        sourceArea.setText(Files.readString(sourcePath, StandardCharsets.UTF_8));
        sourceArea.setCaretPosition(0);
    }

    private HalsteadMetrics analyzeAndDisplay() {
        HalsteadMetrics metrics = analyzer.analyze(sourceArea.getText());
        fillTable(metrics);
        baseMetricsLabel.setText(String.format(
                "Базовые: η1=%d; η2=%d; N1=%d; N2=%d; Σf1j=%d; Σf2i=%d",
                metrics.getUniqueOperatorCount(), metrics.getUniqueOperandCount(),
                metrics.getTotalOperatorCount(), metrics.getTotalOperandCount(),
                metrics.getTotalOperatorCount(), metrics.getTotalOperandCount()));
        derivedMetricsLabel.setText(String.format(
                "Расширенные: словарь η=%d; длина N=%d; объём V=%.3f бит",
                metrics.getVocabulary(), metrics.getLength(), metrics.getVolume()));
        return metrics;
    }

    private void fillTable(HalsteadMetrics metrics) {
        tableModel.setRowCount(0);
        List<Map.Entry<String, Integer>> operators = new ArrayList<>(
                metrics.getOperatorFrequencies().entrySet());
        List<Map.Entry<String, Integer>> operands = new ArrayList<>(
                metrics.getOperandFrequencies().entrySet());
        int rowCount = Math.max(operators.size(), operands.size());

        for (int row = 0; row < rowCount; row++) {
            Map.Entry<String, Integer> operator =
                    row < operators.size() ? operators.get(row) : null;
            Map.Entry<String, Integer> operand =
                    row < operands.size() ? operands.get(row) : null;
            tableModel.addRow(new Object[]{
                    operator == null ? "" : row + 1,
                    operator == null ? "" : operator.getKey(),
                    operator == null ? "" : operator.getValue(),
                    operand == null ? "" : row + 1,
                    operand == null ? "" : operand.getKey(),
                    operand == null ? "" : operand.getValue()
            });
        }
    }

    private static void showError(String message, Exception exception) {
        JOptionPane.showMessageDialog(null,
                message + ": " + exception.getMessage(),
                "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private static void printMetrics(HalsteadMetrics metrics) {
        System.out.println("ОПЕРАТОРЫ");
        printFrequencyTable(metrics.getOperatorFrequencies());
        System.out.println("ОПЕРАНДЫ");
        printFrequencyTable(metrics.getOperandFrequencies());
        System.out.printf("η1=%d%n", metrics.getUniqueOperatorCount());
        System.out.printf("η2=%d%n", metrics.getUniqueOperandCount());
        System.out.printf("N1=%d%n", metrics.getTotalOperatorCount());
        System.out.printf("N2=%d%n", metrics.getTotalOperandCount());
        System.out.printf("η=%d%n", metrics.getVocabulary());
        System.out.printf("N=%d%n", metrics.getLength());
        System.out.printf("V=%.3f бит%n", metrics.getVolume());
    }

    private static void printFrequencyTable(Map<String, Integer> frequencies) {
        int index = 1;
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            System.out.printf("%2d. %-22s %d%n",
                    index++, entry.getKey(), entry.getValue());
        }
    }

    private static void saveScreenshot(Path source, Path output) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            try {
                HalsteadAnalyzerApp app = new HalsteadAnalyzerApp();
                app.loadSource(source);
                app.analyzeAndDisplay();
                app.mainPanel.setSize(new Dimension(1400, 900));
                app.mainPanel.doLayout();
                layoutRecursively(app.mainPanel);

                BufferedImage image = new BufferedImage(
                        1400, 900, BufferedImage.TYPE_INT_RGB);
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

    private static void layoutRecursively(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof java.awt.Container child) {
                layoutRecursively(child);
            }
        }
    }

    private static void showWindow() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // The cross-platform look and feel remains available as a safe fallback.
        }
        HalsteadAnalyzerApp app = new HalsteadAnalyzerApp();
        JFrame frame = new JFrame("Анализатор метрик Холстеда для Java");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(app.mainPanel);
        frame.setMinimumSize(new Dimension(1050, 700));
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && "--analyze".equals(arguments[0])) {
            String source = Files.readString(Path.of(arguments[1]),
                    StandardCharsets.UTF_8);
            printMetrics(new JavaHalsteadAnalyzer().analyze(source));
        } else if (arguments.length == 3
                && "--screenshot".equals(arguments[0])) {
            saveScreenshot(Path.of(arguments[1]), Path.of(arguments[2]));
        } else if (arguments.length == 0) {
            SwingUtilities.invokeLater(HalsteadAnalyzerApp::showWindow);
        } else {
            System.out.println("Использование:");
            System.out.println("  java HalsteadAnalyzerApp");
            System.out.println("  java HalsteadAnalyzerApp --analyze <file.java>");
            System.out.println("  java HalsteadAnalyzerApp --screenshot <file.java> <out.png>");
        }
    }
}
