package hu.advjava.mcpsudoku;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SudokuSolver {
	public static enum Difficulty {
		NONE(39,81),
		EASY(36,38),
		MEDIUM(33,35),
		HARD(29,32),
		EXPERT(26,28),
		MASTER(23,25),
		EXTREME(17,22),
		IMPOSSIBLE(0,16);

        //TODO: add parameters, constructor, getters

        private long minValue;
        private long maxValue;

        private Difficulty(long minValue, long maxValue) {
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public long getMinValue() {
            return minValue;
        }
        public long getMaxValue() {
            return maxValue;
        }

        // returns a case-insensitive match to the names in the enumeration, and if no match is found a NoSuchElementException is thrown.
		public static Difficulty stringToDifficulty(String difficulty) {
                Optional<String> _difficutly = Arrays.stream(Difficulty.values()).map(Enum::name).filter((e) -> e.compareToIgnoreCase(difficulty) == 0).findAny();
                return Difficulty.valueOf(_difficutly.get());
		}

//        which takes a long matches a filled cell number to the ranges provided, but in this case if no match is found, return NONE.
		public static Difficulty numToDifficulty(long num) {
            return Arrays.stream(Difficulty.values()).filter((e) -> e.minValue <= num && e.maxValue >= num).findAny().get();
		}
	}

	public static enum State {
		SOLVED(-1),
		INVALID(0),
		SOLVEDUNIQUE(1),
		SOLVEDTWO(2),
		SOLVEDTHREE(3),
		SOLVEDMANY(Integer.MAX_VALUE);

		//TODO: add parameters, constructor, getters
        private long value;
        private State(int value) {
           this.value = value;
        }

//        takes the long representing the number of solutions and if less than 0 returns SOLVED.
//        Otherwise match 0, 1, 2, 3 solutions to the appropriate enumeration item. Any value 4 or more returns SOLVEDMANY.
        public static State stateFromSols(long sols) {
            if (sols < 0) return SOLVED;
            if (sols >= 4) return SOLVEDMANY;
            return Arrays.stream(State.values()).filter((e) -> e.value == sols).findAny().get();
		}
	}

//    Write deepCopy which makes a deep copy of a puzzle board int[][] by using the efficient Arrays.copyOf and streams.
	/** Defensive deep copy to avoid aliasing */
    public static int[][] deepCopy(int[][] src) {
        return Arrays.stream(src).map(e -> Arrays.copyOf(e, e.length)).toArray(int[][]::new);
    }

    /**
     * Load a Sudoku board from a file (.txt = text, .sud = binary)
     */
    public static int[][] load(File file) throws IOException, ClassNotFoundException {
    	return null; //TODO
    }

    /**
     * Save a Sudoku board to a file (.txt = text, .sud = binary)
     */
    public static void save(File file, int[][] board) throws IOException {
    	//TODO
    }

    //    Write countFilledCells to count the number of filled (non-zero) cells.
    public static long countFilledCells(int[][] board) {
    	return Arrays.stream(board).map(
                e -> Arrays.stream(e).mapToLong((cell) -> cell == 0 ? 1 : 0).sum())
                .reduce(0L, Long::sum);
    }

//    Write findContent which should be generally applicable, possibly to tasks other than this exercise.
//    It takes a lambda that can possibly create some content when it is called (but possibly creates nothing useful).
//    The method calls the lambda time and again, and discards all "nothing" values and returns the first actual content.
//    You may assume that the lambda eventually produces at least one valid bit of content.
    public static ????? findContent(?????? lambda) {
    	// TODO
    }

    public static int[][] generate(String difficulty) {
    	try {
    		var diff = Difficulty.stringToDifficulty(difficulty);
	    	var ssdlx = new SudokuSolverDLX(true);

	    	return findContent(() ->  SudokuSolver.maybeGenerateBoard(diff, ssdlx));
    	} catch (Exception e) { return new int[9][9]; }
    }

//    It uses a helper: maybeGenerateBoard tries to make a board in the following way.

//    Then it makes a stream using iterate.
//            Initially, the iteration state is a deepCopy of the generated board.
//    Our goal is to remove enough cells so that we match the requirements of the difficulty level.
//    In each iteration:
//    Take the next cell by the iterator and erase its value on the board (set it as zero).
//    See if the board is still uniquely solvable: use the DLX solution counter with a limit of 2, and if it returns 1, it is.
//    If the board has no unique solution anymore, put the digit back from the original solution.
//    Return the board itself so that the condition in the following step is easy to check.
//    Make sure to take at most 9×9 steps: we don't have more board cells.
//    If the board in the state is of the expected difficulty, we're done.
    private static /* maybe a board, maybe nothing */ maybeGenerateBoard(Difficulty diff, SudokuSolverDLX ssdlx) {
//    It creates an empty 9×9 board and fills it with a random complete solution using solve in ssdlx.
        int[][] board = new int[9][9];
        boolean isFilledBoard = ssdlx.solve(board);
//    It creates all possible cell indexes in a list (cells) and puts them in a random order. Then it puts its iterator into a variable.
        int[][] randomBoard = new int[9][9];


        Stream.iterate(0, i -> i < 10, i -> i + 1).flatMap(row ->
                IntStream.range(0, 10).mapToObj(col -> new int[]{row, col})
        );

    }

	public static State solveCount(int[][] board) {
    	return State.stateFromSols(new SudokuSolverDLX(false).countSolutions.applyAsLong(board, 4L));
    }

    record Remaining(int cellIdx, Iterator<Integer> digits) {}

	public static State solve(int[][] board, boolean randomize) {
        var cells  = // TODO [0,0], [0,1], ..., [8,8] as a modifiable list
        		     // in random order if `randomize` is on
		var digits = // 1,...,9 as a list; in random order if `randomize` is on

        // Trivial case: no blanks → already solved
        if (cells.isEmpty()) return State.SOLVED;

        var stack = new Stack<Remaining>();
        stack.add(new Remaining(0, nums.iterator()));

		return findContent(() -> maybeSolve(board, cells, digits, stack));
	}

    private static /* maybe a State, maybe nothing */ maybeSolve(int[][] board, List<int[]> cells, List<Integer> nums, Stack<Integer> stack) {
    	// TODO
    }

//    Write isSafe which tells if num is anywhere on the given row row,
//    anywhere on the given column col or anywhere in the 3x3 box that starts at (row-row%3, col-row%3).
    private static boolean isSafe(int[][] board, int row, int col, int num) {
        int numRow = num / 9, numCol = num % 9, minRow = row-(row%3), minCol = col-(col%3);
        BiPredicate<Integer, Integer> inRange = (_numRow, _numCol) -> (_numRow >= minRow && _numRow <= minRow + 2 && _numCol >= minCol && _numCol <= minCol + 2);
        BiPredicate<Integer, Integer> safeArea = (_numRow, _numCol) -> _numRow == row || _numCol == col || inRange.test(_numRow, _numCol);

        return safeArea.test(numRow, numCol);
    }
}
