package hu.advjava.mcpsudoku;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class SudokuSolverTest {
    @Test
    public void checkCondition() {

        Arrays.stream(ExampleSudoku.values()).forEach( exampleSudoku -> {

                IntStream rowIndices = IntStream.range(0, 9);
                Stream<int[]> coordinates = rowIndices.boxed().flatMap(r -> IntStream.range(0, 9).mapToObj(c -> new int[]{r, c}));
                int[][] original = exampleSudoku.getBoard(), solution = exampleSudoku.getSolution(), originalSolved = SudokuSolver.deepCopy(original);
                SudokuSolver.State isSolved = SudokuSolver.solve(originalSolved, false);

            assertAll(
            () -> {
                coordinates.forEach(coordinate -> {
                    int row = coordinate[0], col = coordinate[1];
                    if (original[row][col] != 0) {
                        assertEquals(solution[row][col], original[row][col]);
                    }
                });
            },

            // The given board is solvable.
            () -> assertEquals(SudokuSolver.State.SOLVED, isSolved),
            // The solution matches the given sample solution.
            () -> assertTrue(Arrays.deepEquals(solution, originalSolved))
            );
        });

    }
    @Test
    public void checkDeprecatedCount() {
        assertTrue(SudokuSolverDLX.countDeprecated() <= 8);
    }

}
