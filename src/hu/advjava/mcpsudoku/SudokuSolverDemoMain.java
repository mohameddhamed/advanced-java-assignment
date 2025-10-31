package hu.advjava.mcpsudoku;

import static java.util.Arrays.stream;

import java.util.Arrays;

public class SudokuSolverDemoMain {
    // Example usage:
    public static void main(String[] args) {
        var puzzle = ExampleSudoku.EASY_1.getBoard();

        var solver = new SudokuSolverDLX(false);
        boolean ok = solver.solve.test(puzzle);
        System.out.println("Solved: " + ok);
        if (ok) {
            stream(puzzle).map(Arrays::toString).forEach(IO::println);
        }

        // Count solutions example (limit to 2 to avoid huge runs)
        // long count = solver.countSolutions(puzzle, 2);
        // System.out.println("Solutions counted: " + count);
    }
}
