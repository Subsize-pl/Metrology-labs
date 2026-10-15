import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** Окно анализатора: код слева, метрики и таблицы справа. */
public class GilbAnalyzerApp extends JFrame {
    private final JTextArea code = new JTextArea();
    private final JLabel metrics = new JLabel();
    private final DefaultTableModel branchings =
            new DefaultTableModel(new Object[] {"№", "Строка", "Оператор", "Условие", "Уровень"}, 0);
    private final DefaultTableModel operators =
            new DefaultTableModel(new Object[] {"Оператор", "Количество"}, 0);
    private final JTextArea errors = new JTextArea(3, 20);

    public GilbAnalyzerApp() {
        super("Метрика Джилба — Java");
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        code.setFont(mono);
        code.setTabSize(4);
        errors.setEditable(false);
        errors.setForeground(new java.awt.Color(0xB00020));
        metrics.setFont(metrics.getFont().deriveFont(Font.BOLD, 14f));
        metrics.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JButton open = new JButton("Открыть файл…");
        open.addActionListener(e -> chooseFile());
        JButton analyze = new JButton("Анализировать");
        analyze.addActionListener(e -> analyze());
        JPanel buttons = new JPanel();
        buttons.add(open);
        buttons.add(analyze);

        JPanel left = new JPanel(new BorderLayout());
        left.add(buttons, BorderLayout.NORTH);
        left.add(new JScrollPane(code), BorderLayout.CENTER);

        JTable branchTable = new JTable(branchings);
        branchTable.getColumnModel().getColumn(0).setMaxWidth(40);
        branchTable.getColumnModel().getColumn(1).setMaxWidth(60);
        branchTable.getColumnModel().getColumn(4).setMaxWidth(70);
        JPanel tables = new JPanel(new GridLayout(2, 1, 0, 6));
        tables.add(titled("Ветвления", new JScrollPane(branchTable)));
        tables.add(titled("Операторы языка", new JScrollPane(new JTable(operators))));

        JPanel right = new JPanel(new BorderLayout());
        right.add(metrics, BorderLayout.NORTH);
        right.add(tables, BorderLayout.CENTER);
        right.add(titled("Синтаксические ошибки", new JScrollPane(errors)), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        add(split);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(new Dimension(1300, 800));
    }

    private static JPanel titled(String title, JScrollPane content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content);
        return panel;
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(new File("."));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            load(chooser.getSelectedFile().toPath());
        }
    }

    void load(Path file) {
        try {
            code.setText(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            code.setText("// не удалось прочитать " + file + ": " + e.getMessage());
        }
        code.setCaretPosition(0);
        analyze();
    }

    void analyze() {
        GilbAnalyzer.Result r = GilbAnalyzer.analyze(code.getText());
        metrics.setText(String.format(Locale.ROOT,
                "<html>Абсолютная сложность CL = %d<br>Относительная сложность cl = CL / N = %d / %d = %.3f"
                        + "<br>Максимальная вложенность CLI = %d</html>",
                r.absolute(), r.absolute(), r.operators(), r.relative(), r.maxNesting()));

        branchings.setRowCount(0);
        int n = 1;
        for (GilbAnalyzer.Branching b : r.branchings()) {
            branchings.addRow(new Object[] {n++, b.line(), b.kind(), b.condition(), b.level()});
        }
        operators.setRowCount(0);
        r.operatorKinds().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> operators.addRow(new Object[] {e.getKey(), e.getValue()}));
        operators.addRow(new Object[] {"Всего операторов N", r.operators()});

        errors.setText(r.errors().isEmpty() ? "нет" : String.join("\n", r.errors()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--screenshot")) {
            screenshot(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        Path file = Path.of(args.length > 0 ? args[0] : "OrderStats.java");
        SwingUtilities.invokeLater(() -> {
            GilbAnalyzerApp app = new GilbAnalyzerApp();
            if (Files.exists(file)) {
                app.load(file);
            }
            app.setVisible(true);
        });
    }

    private static void screenshot(Path in, Path out) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GilbAnalyzerApp app = new GilbAnalyzerApp();
            app.load(in);
            app.pack();
            app.setSize(1300, 800);
            app.setVisible(true);
        });
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = (JFrame) JFrame.getFrames()[0];
            BufferedImage img = new BufferedImage(frame.getContentPane().getWidth(),
                    frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
            frame.getContentPane().paint(img.getGraphics());
            try {
                ImageIO.write(img, "png", out.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
