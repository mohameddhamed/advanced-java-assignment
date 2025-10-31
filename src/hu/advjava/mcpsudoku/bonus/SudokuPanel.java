package hu.advjava.mcpsudoku.bonus;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import hu.advjava.mcpsudoku.SudokuSolver;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;

public class SudokuPanel extends JPanel {
	private static final long serialVersionUID = 1L;
	static int _clientId = 0;
	private final SudokuGrid grid;
    private final JComboBox<String> puzzleBox;
    private final JButton solveBtn, genBtn, loadBtn, saveBtn, closeBtn;
    private final JLabel lblState;

    private final HttpClient client;
    private final String baseUrl;
    private final int clientId;

    /**
     * SudokuGrid renders 9 sub-grids (3x3) each as its own JTable(3,3)
     * with thick borders between blocks. Provides getBoard/setBoard helpers.
     */
    private static class SudokuGrid extends JPanel {
		private static final long serialVersionUID = 1L;
		private final JTable[][] blocks = new JTable[3][3];

        SudokuGrid() {
            super(new GridLayout(3, 3, 4, 4));
            setBackground(new Color(230, 230, 230));
            setBorder(new CompoundBorder(
                    new LineBorder(Color.DARK_GRAY, 2),
                    new EmptyBorder(4, 4, 4, 4)
            ));

            for (int br = 0; br < 3; br++) {
                for (int bc = 0; bc < 3; bc++) {
                    JTable t = makeMiniTable();
                    blocks[br][bc] = t;

                    JPanel holder = new JPanel(new BorderLayout());
                    holder.setBorder(new LineBorder(Color.BLACK, 2)); // thick border per block
                    //holder.add(t.getTableHeader(), BorderLayout.NORTH); // hidden via UI config
                    holder.add(t, BorderLayout.CENTER);
                    add(holder);
                }
            }
        }

        private JTable makeMiniTable() {
            DefaultTableModel model = new DefaultTableModel(3, 3) {
                @Override public boolean isCellEditable(int r, int c) { return true; }
            };
            JTable t = new JTable(model);
            t.setTableHeader(null);
            t.setRowHeight(48);
            t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
            t.setCellSelectionEnabled(true);
            t.setGridColor(new Color(180, 180, 180));
            t.setShowGrid(true);

            // Center renderer
            DefaultTableCellRenderer center = new DefaultTableCellRenderer();
            center.setHorizontalAlignment(SwingConstants.CENTER);
            t.setDefaultRenderer(Object.class, center);

            // Editor that accepts only digits 1-9, clears on invalid/empty
            JTextField tf = new JTextField();
            tf.setHorizontalAlignment(SwingConstants.CENTER);
            tf.addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) {
                    char ch = e.getKeyChar();
                    if (ch == KeyEvent.VK_BACK_SPACE || ch == KeyEvent.VK_DELETE) return;
                    if (ch < '1' || ch > '9') e.consume();
                }
            });
            t.setDefaultEditor(Object.class, new DefaultCellEditor(tf));

            // Square appearance
            t.setIntercellSpacing(new Dimension(1, 1));
            for (int i = 0; i < 3; i++) t.getColumnModel().getColumn(i).setPreferredWidth(36);

            return t;
        }

        int[][] getBoard() {
            int[][] board = new int[9][9];
            for (int br = 0; br < 3; br++) for (int bc = 0; bc < 3; bc++) {
                JTable t = blocks[br][bc];
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
                    Object v = t.getValueAt(r, c);
                    int row = br * 3 + r;
                    int col = bc * 3 + c;
                    if (v == null || v.toString().isBlank()) board[row][col] = 0;
                    else {
                        try {
                            int n = Integer.parseInt(v.toString());
                            board[row][col] = (n >= 1 && n <= 9) ? n : 0;
                        } catch (NumberFormatException ex) {
                            board[row][col] = 0;
                        }
                    }
                }
            }
            return board;
        }

        void setBoard(int[][] board) {
            for (int br = 0; br < 3; br++) for (int bc = 0; bc < 3; bc++) {
                JTable t = blocks[br][bc];
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
                    int row = br * 3 + r;
                    int col = bc * 3 + c;
                    int val = board[row][col];
                    t.setValueAt(val == 0 ? "" : Integer.toString(val), r, c);
                }
            }
        }
    }

    public SudokuPanel(String baseUrl, Runnable onClose) {
        this.baseUrl = baseUrl;
        this.clientId = ++_clientId;
        this.client = HttpClient.newHttpClient();

        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // Grid (3x3 of 3x3 tables)
        grid = new SudokuGrid();
        add(grid, BorderLayout.CENTER);

        // Controls
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        puzzleBox = new JComboBox<>(new String[]{"Empty", "Easy", "Medium", "Hard", "Expert", "Master", "Extreme"});
        solveBtn = new JButton("Solve");
        genBtn = new JButton("Generate");
        lblState = new JLabel();
        lblState.setFont(new Font(lblState.getFont().getName(), lblState.getFont().getStyle(), 8));
        loadBtn = new JButton("Load");
        saveBtn = new JButton("Save");
        closeBtn = new JButton("Close");

        controls.add(new JLabel("Puzzle:"));
        controls.add(puzzleBox);
        controls.add(genBtn);
        controls.add(solveBtn);
        controls.add(lblState);
        controls.add(loadBtn);
        controls.add(saveBtn);
        controls.add(closeBtn);
        add(controls, BorderLayout.SOUTH);

        puzzleBox.addActionListener(_ -> applyPreset(Objects.toString(puzzleBox.getSelectedItem(), "Empty")));
        solveBtn.addActionListener(_ -> solveSudoku());
        genBtn.addActionListener(_ -> generateSudoku());
        loadBtn.addActionListener(_ -> loadSudoku());
        saveBtn.addActionListener(_ -> saveSudoku());
        closeBtn.addActionListener(_ -> onClose.run());

        // Initialize MCP session once (keep SSE stream open as your server supports)
        lblState.setText("Initializing...");
        sendRequest(getInitRequest());

        // Start with empty board
        applyPreset("Empty");
    }

    // ---------- MCP Calls ----------
    private void solveSudoku() {
    	int[][] board = grid.getBoard();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        for (int r = 0; r < 9; r++) {
            JsonArrayBuilder row = Json.createArrayBuilder();
            for (int c = 0; c < 9; c++) row.add(board[r][c]);
            arr.add(row);
        }
        JsonObject req = Json.createObjectBuilder()
                .add("jsonrpc", "2.0").add("id", clientId)
                .add("method", "tools/call")
                .add("params", Json.createObjectBuilder()
                		.add("name", "solve_sudoku")
                		.add("arguments", Json.createObjectBuilder().add("board", arr).build()).build()).build();
        lblState.setText("Solving...");
        sendRequest(req);
    }

    private void generateSudoku() {
        JsonObject req = Json.createObjectBuilder()
                .add("jsonrpc", "2.0").add("id", clientId)
                .add("method", "tools/call")
                .add("params", Json.createObjectBuilder()
                		.add("name", "generate_sudoku")
                		.add("arguments", Json.createObjectBuilder().add("difficulty", Objects.toString(puzzleBox.getSelectedItem(), "Empty")).build()).build()).build();
        lblState.setText("Generating...");
        sendRequest(req);
    }

	private JsonObject getInitRequest() {
		return Json.createObjectBuilder().add("jsonrpc", "2.0")
				.add("id",  clientId).add("method", "initialize")
				.add("params", Json.createObjectBuilder()
						.add("protocolVersion", "2025-10-06")
						.add("capabilities", Json.createObjectBuilder().build())
						.add("clientInfo", Json.createObjectBuilder()
								.add("name", "SudokuClient")
								.add("title", "Sudoku Client")
								.add("version", "1.0.0").build()).build()).build();

	}
    private void sendRequest(JsonObject obj) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/mcp"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(obj.toString(), StandardCharsets.UTF_8))
                .build();
        client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(resp -> resp.body().forEach(line -> {
                    if (line.startsWith("data: ")) {
                        String json = line.substring(6);
                        try (var reader = Json.createReader(new java.io.StringReader(json))) {
                        	JsonObject result = reader.readObject().getJsonObject("result");
                            if (result == null) return;
                            // Case A: direct board (tools/call) -> content[0].text (JSON string)
                            // Case B: resources/read → contents[0].text (JSON string)
                            if (result.containsKey("content") || result.containsKey("contents")) {
                                JsonArray contents = result.getJsonArray(result.containsKey("content") ? "content" : "contents");
                                if (!contents.isEmpty()) {
                                    JsonObject item = contents.getJsonObject(0);
                                    String text = item.getString("text", null);
                                    if (text != null && !text.isBlank()) {
                                        // 'text' itself is JSON: { "board": [...] }
                                        try (var inner = Json.createReader(new java.io.StringReader(text))) {
                                            JsonObject payload = inner.readObject();
                                            if (payload.containsKey("board")) {
                                                JsonArray board = payload.getJsonArray("board");
                                                SwingUtilities.invokeLater(() -> updateBoard(board));
                                                if (payload.containsKey("state")) lblState.setText(payload.getString("state"));
                                                else lblState.setText(result.containsKey("content") ? "Generated" : "Loaded");
                                            }
                                        }
                                    }
                                }
                                return;
                            } else lblState.setText("Initialized");
                        }
                    }
                }));
    }

    private void updateBoard(JsonArray arr) {
        int[][] board = new int[9][9];
        for (int r = 0; r < 9; r++) {
            JsonArray row = arr.getJsonArray(r);
            for (int c = 0; c < 9; c++) board[r][c] = row.getInt(c);
        }
        grid.setBoard(board);
    }
    // preset -> resource URI exposed by MCP server
    private String presetToUri(String name) {
        return switch (name) {
            case "Easy"    -> "sudoku://examples/easy-1";
            case "Medium"  -> "sudoku://examples/medium-1";
            case "Hard"    -> "sudoku://examples/hard-1";
            case "Expert"  -> "sudoku://examples/expert-1";
            case "Master"  -> "sudoku://examples/master-1";
            case "Extreme" -> "sudoku://examples/extreme-1";
            default        -> null;
        };
    }
    /*private JsonObject resourcesList() {
        return Json.createObjectBuilder()
            .add("jsonrpc", "2.0")
            .add("id",  1000)
            .add("method", "resources/list")
            .build();
    }*/
    // build a JSON-RPC request for resources/read
    private JsonObject resourcesRead(String uri) {
        return Json.createObjectBuilder()
            .add("jsonrpc", "2.0")
            .add("id",  1001)
            .add("method", "resources/read")
            .add("params", Json.createObjectBuilder()
                .add("uri", uri)
            ).build();
    }
    // ---------- Presets ----------
    //Generated from https://sudoku.com/
    private void applyPreset(String name) {
        String uri = presetToUri(name);
        if (uri == null) {
            grid.setBoard(new int[9][9]); // Empty / unknown preset
            return;
        }
        // Ask server for the resource content
        lblState.setText("Loading...");
        sendRequest(resourcesRead(uri));
    }

    // ---------- Load/Save with File Chooser ----------

    private void loadSudoku() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Sudoku");
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Binary Sudoku (*.sud)", "sud"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Text Sudoku (*.txt)", "txt"));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                // Implement this:
                //  - if .sud => binary decode
                //  - if .txt => text decode
            	lblState.setText("Loading");
            	int[][] board = SudokuSolver.load(file);
            	lblState.setText("Loaded");
                if (board == null) throw new IllegalStateException("Loaded board is null");
                if (board.length != 9 || board[0].length != 9) throw new IllegalArgumentException("Invalid board size");
                grid.setBoard(board);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load: " + ex.getMessage(),
                        "Load Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveSudoku() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Sudoku");
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Binary Sudoku (*.sud)", "sud"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Text Sudoku (*.txt)", "txt"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            String name = file.getName().toLowerCase();
            // Auto-append extension if missing
            if (!name.endsWith(".sud") && !name.endsWith(".txt")) {
                var filter = chooser.getFileFilter();
                if (filter instanceof FileNameExtensionFilter) {
                    String ext = ((FileNameExtensionFilter) filter).getExtensions()[0];
                    file = new File(file.getParentFile(), file.getName() + "." + ext);
                } else {
                    // default to .txt
                    file = new File(file.getParentFile(), file.getName() + ".txt");
                }
            }
            try {
            	lblState.setText("Saving");
                SudokuSolver.save(file, grid.getBoard()); // You implement: picks binary/text by extension
            	lblState.setText("Saved");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to save: " + ex.getMessage(),
                        "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}