package hu.advjava.mcpsudoku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * SudokuSolverDLX
 * Knuth's Algorithm X + Dancing Links for 9x9 Sudoku.
 *
 * Model:
 *   - 9x9 grid, digits 1..9.
 *   - 4 constraint groups (total 324 columns):
 *       1) Cell constraints:   (r,c) has exactly one digit.            81 columns
 *       2) Row constraints:    (r,d) appears once in row r.            81 columns
 *       3) Col constraints:    (c,d) appears once in column c.         81 columns
 *       4) Box constraints:    (b,d) appears once in 3x3 box b.        81 columns
 *   - 729 rows (options): each is (r,c,d) and covers 4 columns above.
 *
 * API:
 *   boolean solve(int[][] board); // modifies board in-place with one solution
 *   long    countSolutions(int[][] board, long limit);
 *   void    solveAll(int[][] board, Consumer<int[][]> onSolution);
 */
public final class SudokuSolverDLX {

    // Board is 9x9, values 0..9 (0 = empty)
    private static final int N = 9;
    private static final int N2 = N * N;        // 81
    private static final int OPTIONS = N * N * N; // 729
    private static final int COLS = 4 * N2;     // 324

    // DLX node structure
    private static class Node {
        Node L, R, U, D;
        Column C;    // Column header
        int rowId;   // which (r,c,d) option this node belongs to
    }

    private static final class Column extends Node {
        int size;    // number of nodes in this column
        //int name;    // column index [0..323], for debugging
    }

    // Pool allocation (avoid GC churn)
    private final Column header = new Column();
    private final Column[] columns = new Column[COLS];
    private final Node[] nodePool;
    private int poolPtr = 0;

    // Map option row index -> (r, c, d)
    private final int[] optRowR = new int[OPTIONS];
    private final int[] optRowC = new int[OPTIONS];
    private final int[] optRowD = new int[OPTIONS];

    // Solution stack (row indices picked)
    private final int[] solution = new int[OPTIONS];
    private int solutionPtr = 0;
    private boolean randomize = false;

    // For enumerating solutions
    private long solutionCount;
    private long solutionLimit;
    private Consumer<int[][]> solutionConsumer;

    public SudokuSolverDLX(boolean randomize) {
        this.randomize = randomize;
        // Upper bound: each option has 4 ones -> total nodes = 729 * 4 = 2916, plus column headers
        this.nodePool = new Node[OPTIONS * 4 + COLS + 10];
        for (int i = 0; i < nodePool.length; i++) nodePool[i] = new Node();
    }


    /* ===================== Public API ===================== */

    public boolean checkValid(int[][] board) {
        return IntStream.range(0, 9).allMatch(x -> {
            if (!Arrays.stream(board[x]).allMatch(z -> z >= 0 && z <= 9)) return false;
            List<Integer> chunk = Arrays.stream(board[x]).boxed().toList();
            int zeros = (int)chunk.stream().filter(z -> z == 0).count();
            if (new HashSet<>(chunk).size() + (zeros != 0 ? zeros-1 : zeros) != 9) return false;
            chunk = IntStream.range(0, 9).map(z -> board[z][x]).boxed().toList();
            zeros = (int)chunk.stream().filter(z -> z == 0).count();
            if (new HashSet<>(chunk).size() + (zeros != 0 ? zeros-1 : zeros) != 9) return false;
            chunk = IntStream.range(0, 9).map(z -> board[(x / 3) * 3 + z / 3][(x % 3) * 3 + z % 3]).boxed().toList();
            zeros = (int)chunk.stream().filter(z -> z == 0).count();
            if (new HashSet<>(chunk).size() + (zeros != 0 ? zeros-1 : zeros) != 9) return false;
            return true;
        });
    }

    public boolean solve(int[][] board) {
        if (!checkValid(board)) return false;
        solutionPtr = 0;
        poolPtr = 0;
        checkBoard(board);
        buildDLX();
        // Apply givens: select the corresponding rows (cover their constraints)
        applyGivens(board);

        boolean solved = searchOne();
        if (solved) writeSolutionToBoard(board);
        return solved;
    }

    /** Count up to 'limit' solutions (fast). If limit <= 0, counts all (potentially huge). */
    public long countSolutions(int[][] board, long limit) {
        if (!checkValid(board)) return 0;
        solutionPtr = 0;
        poolPtr = 0;
        checkBoard(board);
        buildDLX();
        applyGivens(board);

        this.solutionCount = 0;
        this.solutionLimit = (limit <= 0) ? Long.MAX_VALUE : limit;
        this.solutionConsumer = null;
        try {
            searchCount();
        } catch (StopSignal e) {}
        return solutionCount;
    }

    /** Enumerate all solutions; calls consumer with a fresh 9x9 board per solution. */
    public void solveAll(int[][] board, Consumer<int[][]> onSolution) {
        if (!checkValid(board)) return;
        checkBoard(board);
        buildDLX();
        applyGivens(board);

        this.solutionCount = 0;
        this.solutionLimit = Long.MAX_VALUE;
        this.solutionConsumer = onSolution;
        searchEnumerate();
    }

    /* ===================== Core building ===================== */

    private void buildDLX() {
        // init header
        header.L = header.R = header;
        header.U = header.D = header;
        header.C = null;
        header.size = 0;

        // create columns [0..323]
        for (int c = 0; c < COLS; c++) {
            Column col = new Column();
            columns[c] = col;
            //col.name = c;
            col.size = 0;
            // circular vertical list
            col.U = col.D = col;
            col.C = col;
            // insert col into header's horizontal list
            col.L = header.L;
            col.R = header;
            header.L.R = col;
            header.L = col;
        }

        poolPtr = 0;

        // Build option rows (r,c,d) with 4 nodes each
        int rowId = 0;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                for (int d = 0; d < N; d++) {
                    int[] cols = {
                        cellCol(r, c),
                        rowCol(r, d),
                        colCol(c, d),
                        boxCol(boxIndex(r, c), d)
                    };
                    Node rowHead = null;
                    Node prev = null;
                    for (int j = 0; j < 4; j++) {
                        Node n = newNode();
                        Column col = columns[cols[j]];
                        n.C = col;
                        n.rowId = rowId;

                        // link into column (at bottom)
                        n.D = col;
                        n.U = col.U;
                        col.U.D = n;
                        col.U = n;
                        col.size++;

                        // link horizontally into the option row
                        if (rowHead == null) {
                            rowHead = n;
                            n.L = n.R = n;
                        } else {
                            n.L = prev;
                            n.R = rowHead;
                            prev.R = n;
                            rowHead.L = n;
                        }
                        prev = n;
                    }
                    optRowR[rowId] = r;
                    optRowC[rowId] = c;
                    optRowD[rowId] = d + 1; // store digit 1..9
                    rowId++;
                }
            }
        }
    }

    private Node newNode() {
        Node n = nodePool[poolPtr++];
        n.L = n.R = n.U = n.D = null;
        n.C = null;
        n.rowId = -1;
        return n;
    }

    /* ===================== Constraints mapping ===================== */

    // Column index helpers
    private static int cellCol(int r, int c) {         // (r,c) once
        return r * N + c;                               // 0..80
    }

    private static int rowCol(int r, int d) {          // (r,d) once
        return N2 + r * N + d;                          // 81..161
    }

    private static int colCol(int c, int d) {          // (c,d) once
        return 2 * N2 + c * N + d;                      // 162..242
    }

    private static int boxCol(int b, int d) {          // (b,d) once
        return 3 * N2 + b * N + d;                      // 243..323
    }

    private static int boxIndex(int r, int c) {
        return (r / 3) * 3 + (c / 3);                  // 0..8
    }

    private static void checkBoard(int[][] board) {
        if (board == null || board.length != 9) throw new IllegalArgumentException("Board must be 9x9");
        for (int i = 0; i < 9; i++) {
            if (board[i] == null || board[i].length != 9)
                throw new IllegalArgumentException("Board must be 9x9");
        }
    }

    /* ===================== Apply givens ===================== */

    @Deprecated
    private void applyGivens(int[][] board) {
        // For each given (r,c) = d (1..9), we "select" the corresponding option row:
        //   rowId = (r*81) + (c*9) + (d-1)
        // Selecting a row = cover its row nodes' columns, and append rowId to partial solution.
        List<Integer> rowIdxs = new ArrayList<>(IntStream.range(0, 9).boxed().toList());
        List<Integer> colIdxs = new ArrayList<>(IntStream.range(0, 9).boxed().toList());
        if (randomize) Collections.shuffle(rowIdxs);
        for (int r : rowIdxs) {
            if (randomize) Collections.shuffle(colIdxs);
            for (int c : colIdxs) {
                int d = board[r][c];
                if (d >= 1 && d <= 9) {
                    int rowId = (r * N2) + (c * N) + (d - 1);
                    // find any node in that option’s row
                    Node any = findAnyNodeForRow(rowId);
                    if (any == null) throw new IllegalStateException("Internal error: row not found");
                    selectRow(any);
                }
            }
        }
    }

    @Deprecated
    private Node findAnyNodeForRow(int rowId) {
        // We can find by scanning the column of (r,c) constraint. That column contains exactly 9 options for that cell,
        // one of which matches our digit. But scanning all is fine since this runs only 81 times at most.
        // Simpler: scan all columns’ vertical lists (still fast here). We'll do a targeted approach:

        // The (r,c) constraint column index for this rowId:
        int r = optRowR[rowId], c = optRowC[rowId];
        Column col = columns[cellCol(r, c)];
        for (Node n = col.D; n != col; n = n.D) {
            if (n.rowId == rowId) return n;
        }
        return null;
    }

    /* ===================== DLX cover/uncover ===================== */

    @Deprecated
    private void cover(Column c) {
        c.R.L = c.L; c.L.R = c.R; // remove column header from header row
        for (Node i = c.D; i != c; i = i.D) {
            for (Node j = i.R; j != i; j = j.R) {
                j.D.U = j.U;
                j.U.D = j.D;
                j.C.size--;
            }
        }
    }

    @Deprecated
    private void uncover(Column c) {
        for (Node i = c.U; i != c; i = i.U) {
            for (Node j = i.L; j != i; j = j.L) {
                j.C.size++;
                j.D.U = j;
                j.U.D = j;
            }
        }
        c.R.L = c;
        c.L.R = c;
    }

//    @Deprecated
    private void selectRow(Node row) {
        // choose the row by covering all its columns (Algorithm X primary operation)
        solution[solutionPtr++] = row.rowId;
//        for (Node j = row.R; j != row; j = j.R) cover(j.C);
        Stream.iterate(row.R, j -> j != row, j -> j.R).forEach(node -> cover(node.C));
        cover(row.C);
    }

    /*private void deselectRow(Node row) {
        uncover(row.C);
        for (Node j = row.L; j != row; j = j.L) uncover(j.C);
        solutionPtr--;
    }*/

    @Deprecated
    private Column chooseColumnHeuristic() {
        // choose column with smallest size (min branching)
        Column best = null;
        int min = Integer.MAX_VALUE;
        for (Column c = (Column) header.R; c != header; c = (Column) c.R) {
            if (c.size < min) {
                min = c.size;
                best = c;
                if (min == 0) break;
            }
        }
        return best;
    }

    /* ===================== Search variants ===================== */

    //The following are typical recursive versions that are replaced below with iterative ones easier to convert to streams
    /*private boolean searchOne() {
        if (header.R == header) return true; // all columns covered -> solution

        Column c = chooseColumnHeuristic();
        if (c == null || c.size == 0) return false;
        cover(c);

        for (Node r = c.D; r != c; r = r.D) {
            // select row r
            for (Node j = r.R; j != r; j = j.R) cover(j.C);

            solution[solutionPtr++] = r.rowId;

            if (searchOne()) return true;

            solutionPtr--;
            // undo
            for (Node j = r.L; j != r; j = j.L) uncover(j.C);
        }
        uncover(c);
        return false;
    }

    private void searchCount() {
        if (header.R == header) {
            solutionCount++;
            if (solutionCount >= solutionLimit) throw StopSignal.INSTANCE;
            return;
        }

        Column c = chooseColumnHeuristic();
        if (c == null || c.size == 0) return;

        cover(c);
        for (Node r = c.D; r != c; r = r.D) {
            for (Node j = r.R; j != r; j = j.R) cover(j.C);
            solution[solutionPtr++] = r.rowId;

            try {
                searchCount();
            } catch (StopSignal stop) {
                // bubble up the stop
                uncoverChain(r, c);
                throw stop;
            }

            solutionPtr--;
            for (Node j = r.L; j != r; j = j.L) uncover(j.C);
        }
        uncover(c);
    }

    private void searchEnumerate() {
        if (header.R == header) {
            // Emit a copy of the solved board
            int[][] sol = new int[N][N];
            Arrays.stream(sol).forEach(row -> Arrays.fill(row, 0));
            applySolutionToBoard(sol);
            solutionConsumer.accept(sol);
            solutionCount++;
            return;
        }

        Column c = chooseColumnHeuristic();
        if (c == null || c.size == 0) return;

        cover(c);
        for (Node r = c.D; r != c; r = r.D) {
            for (Node j = r.R; j != r; j = j.R) cover(j.C);
            solution[solutionPtr++] = r.rowId;

            searchEnumerate();

            solutionPtr--;
            for (Node j = r.L; j != r; j = j.L) uncover(j.C);
        }
        uncover(c);
    }*/
    private static class Frame {
        final Column c;
        final Node r;
        public Frame(Column c, Node r) { this.c = c; this.r = r; }
    }

    @Deprecated
    private boolean searchOne() {
        final Stack<Frame> stack = new Stack<>();
        stack.push(null); // descent marker

        while (!stack.isEmpty()) {
            // Solved?
            if (header.R == header) {
                return true;
            }

            // Pop a task (either a frame to resume or a null meaning "descend")
            Frame f = stack.pop();

            Column c;
            Node r;

            if (f == null) {
                // DESCEND: choose column, cover it, start with its first row
                c = chooseColumnHeuristic();
                if (c == null || c.size == 0) {
                    // dead end at root
                    continue;
                }
                cover(c);
                r = c.D;
            } else {
                // RESUME in column f.c after having tried row f.r last time
                c = f.c;

                // undo row f.r selection
                solutionPtr--;
                for (Node j = f.r.L; j != f.r; j = j.L) {
                    uncover(j.C);
                }

                // next row
                r = f.r.D;

                // exhausted this column? uncover and continue at parent
                if (r == f.c) {
                    uncover(f.c);
                    continue;
                }
            }

            // choose row r in column c: cover other columns present in this row
            for (Node j = r.R; j != r; j = j.R) {
                cover(j.C);
            }

            // record solution row and push state to resume later
            solution[solutionPtr++] = r.rowId;
            stack.push(new Frame(c, r)); // resume frame here next time
            stack.push(null);            // then descend again
        }

        // Exhausted search
        return false;
    }

    @Deprecated
    private void searchCount() {
        final Stack<Frame> stack = new Stack<>();
        stack.push(null);

        try {
            while (!stack.isEmpty()) {
                // Found a solution: count + backtrack one step so we move off the solved state
                if (header.R == header) {
                    solutionCount++;
                    if (solutionCount >= solutionLimit) {
                        throw StopSignal.INSTANCE; // early stop requested
                    }

                    // Pop until we find a real frame (skip null markers)
                    Frame f = null;
                    while (!stack.isEmpty()) {
                        Frame top = stack.pop();
                        if (top != null) { f = top; break; }
                    }
                    if (f == null) {
                        // nothing to backtrack – root exhausted
                        break;
                    }
                    // Undo row f.r
                    solutionPtr--;
                    for (Node j = f.r.L; j != f.r; j = j.L) {
                        uncover(j.C);
                    }
                    // Advance to next row in column f.c
                    Node next = f.r.D;
                    if (next == f.c) {
                        // Column f.c exhausted – uncover column and continue up
                        uncover(f.c);
                    } else {
                        // Try next row in the same column next tick (keep column covered)
                        stack.push(new Frame(f.c, next));
                    }
                    // continue loop
                    continue;
                }

                // Normal step
                Frame f = stack.pop();

                Column c;
                Node r;

                if (f == null) {
                    c = chooseColumnHeuristic();
                    if (c == null || c.size == 0) {
                        // dead end at root
                        continue;
                    }
                    cover(c);
                    r = c.D;
                } else {
                    c = f.c;
                    // undo previous row selection at this depth
                    solutionPtr--;
                    for (Node j = f.r.L; j != f.r; j = j.L) {
                        uncover(j.C);
                    }
                    r = f.r.D;
                    if (r == f.c) {
                        // exhausted column – uncover and go up
                        uncover(c);
                        continue;
                    }
                }

                // select row r
                for (Node j = r.R; j != r; j = j.R) {
                    cover(j.C);
                }
                solution[solutionPtr++] = r.rowId;

                // push resume + descent
                stack.push(new Frame(c, r));
                stack.push(null);
            }
        } catch (StopSignal stop) {
            // Best-effort unwind (from top of stack down)
            for (int i = stack.size() - 1; i >= 0; i--) {
                Frame f = stack.get(i);
                if (f != null) {
                    // undo a partially selected row and its column if needed
                    uncoverChain(f.r, f.c);
                }
            }
        }
    }

    @Deprecated
    private void searchEnumerate() {
        final Stack<Frame> stack = new Stack<>();
        stack.push(null);

        while (!stack.isEmpty()) {
            // On solution: emit solved board and keep searching
            if (header.R == header) {
                int[][] sol = new int[N][N];
                for (int r = 0; r < N; r++) Arrays.fill(sol[r], 0);
                applySolutionToBoard(sol);
                solutionConsumer.accept(sol);
                solutionCount++;

                // Now backtrack one level so we don’t stay at solved state
                // Pop until a real frame
                Frame f = null;
                while (!stack.isEmpty()) {
                    Frame top = stack.pop();
                    if (top != null) { f = top; break; }
                }
                if (f == null) break; // root exhausted

                // undo row f.r
                solutionPtr--;
                for (Node j = f.r.L; j != f.r; j = j.L) {
                    uncover(j.C);
                }

                // advance to next row in same column (keep column covered)
                Node next = f.r.D;
                if (next == f.c) {
                    // column exhausted – uncover and continue up (next loop tick will pop parent)
                    uncover(f.c);
                } else {
                    stack.push(new Frame(f.c, next));
                }
                continue;
            }

            // Normal step
            Frame f = stack.pop();

            Column c;
            Node r;

            if (f == null) {
                c = chooseColumnHeuristic();
                if (c == null || c.size == 0) {
                    // dead end at root
                    continue;
                }
                cover(c);
                r = c.D;
            } else {
                c = f.c;
                // undo previous row selection at this level
                solutionPtr--;
                for (Node j = f.r.L; j != f.r; j = j.L) {
                    uncover(j.C);
                }
                r = f.r.D;
                if (r == f.c) {
                    // exhausted – uncover column, go up
                    uncover(c);
                    continue;
                }
            }

            // select row r
            for (Node j = r.R; j != r; j = j.R) {
                cover(j.C);
            }
            solution[solutionPtr++] = r.rowId;

            // push resume + descent
            stack.push(new Frame(c, r));
            stack.push(null);
        }
    }

//    @Deprecated
    private void uncoverChain(Node r, Column c) {
        // helper for early stop unwinding
//        for (Node j = r.L; j != r; j = j.L) uncover(j.C);
        Stream.iterate(r.L,  j -> j != r, j -> j.L).forEach(node -> uncover(node.C));
        uncover(c);
    }

    private static final class StopSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final StopSignal INSTANCE = new StopSignal();
        private StopSignal() { super(null, null, false, false); }
    }

    /* ===================== Solution materialization ===================== */

//    @Deprecated
    private void writeSolutionToBoard(int[][] board) {
        // start by clearing; then place all selected rows (givens + found)
//        for (int i = 0; i < N; i++) Arrays.fill(board[i], 0);
        IntStream.range(0, N).forEach(i -> Arrays.fill(board[i], 0));
        applySolutionToBoard(board);
    }

//    @Deprecated
    private void applySolutionToBoard(int[][] board) {
//        for (int i = 0; i < solutionPtr; i++) {
//            int rowId = solution[i];
//            int r = optRowR[rowId];
//            int c = optRowC[rowId];
//            int d = optRowD[rowId];
//            board[r][c] = d;
//        }
        IntStream.range(0, solutionPtr).forEach(i -> {
            int rowId = solution[i];
            int r = optRowR[rowId];
            int c = optRowC[rowId];
            int d = optRowD[rowId];
            board[r][c] = d;
        });
    }

    /* ===================== Utilities ===================== */

    public static int countDeprecated() {
        var cl = SudokuSolverDLX.class;
        var annotCl = Deprecated.class;

        int count = 0;
        for (var method : cl.getDeclaredMethods()) {
            if (method.getDeclaredAnnotation(annotCl) != null)    ++count;
        }
        for (var fld : cl.getDeclaredFields()) {
            if (fld.getDeclaredAnnotation(annotCl) != null)    ++count;
        }
        return count;
    }
}
