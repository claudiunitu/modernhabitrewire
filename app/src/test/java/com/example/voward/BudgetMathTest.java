package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BudgetMathTest {
    @Test
    public void dailyGrantClampsBalanceAndCarryover() {
        assertEquals(1_800, BudgetMath.addDailyAllowancesBounded(0, 1_800, 1, 1));
        assertEquals(900, BudgetMath.addDailyAllowancesBounded(900, 1_800, 0, .5));
        assertEquals(900, BudgetMath.addDailyAllowancesBounded(5_000, 1_800, 0, .5));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(-1, 1_800, 0, 1));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(10, -1, 2, 1));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(10, 0, 2, 1));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(10, 1_800, 2, -1));
    }

    @Test
    public void dailyGrantSaturatesOnArithmeticOverflow() {
        assertEquals(Long.MAX_VALUE, BudgetMath.addDailyAllowancesBounded(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, BudgetMath.addDailyAllowancesBounded(
                Long.MAX_VALUE - 1, Long.MAX_VALUE, 1, Double.POSITIVE_INFINITY));
    }

    @Test
    public void waitCalculationSanitizesEveryInput() {
        assertEquals(1, BudgetMath.calculateReentryWaitSeconds(0, 0, 0));
        assertEquals(3_600, BudgetMath.calculateReentryWaitSeconds(10_000, 0, 0));
        assertEquals(30, BudgetMath.calculateReentryWaitSeconds(30, Double.NaN, 100));
        assertEquals(30, BudgetMath.calculateReentryWaitSeconds(30, -.5, 100));
        assertEquals(30, BudgetMath.calculateReentryWaitSeconds(30, 1, -5));
        assertEquals(51, BudgetMath.calculateReentryWaitSeconds(30, 1, 1));
        assertEquals(3_600, BudgetMath.calculateReentryWaitSeconds(
                3_600, 1, Integer.MAX_VALUE));
    }

    @Test
    public void sessionQuotesAndElapsedCostsNeverGoNegative() {
        assertEquals(0, BudgetMath.quoteSessionSeconds(0, 10));
        assertEquals(0, BudgetMath.quoteSessionSeconds(10, 0));
        assertEquals(10, BudgetMath.quoteSessionSeconds(10, 20));
        assertEquals(20, BudgetMath.quoteSessionSeconds(30, 20));
        assertEquals(0, BudgetMath.elapsedCostSeconds(-1));
        assertEquals(0, BudgetMath.elapsedCostSeconds(999));
        assertEquals(1, BudgetMath.elapsedCostSeconds(1_000));
        assertEquals(Long.MAX_VALUE / 1_000, BudgetMath.elapsedCostSeconds(Long.MAX_VALUE));
    }

    @Test
    public void signedAdditionSaturatesAndSubtractionNeverCreatesDebt() {
        assertEquals(Long.MAX_VALUE, BudgetMath.addSignedDelta(Long.MAX_VALUE, 1));
        assertEquals(Long.MIN_VALUE, BudgetMath.addSignedDelta(Long.MIN_VALUE, -1));
        assertEquals(7, BudgetMath.addSignedDelta(10, -3));
        assertEquals(10, BudgetMath.subtractCost(10, -1));
        assertEquals(0, BudgetMath.subtractCost(-10, 2));
        assertEquals(0, BudgetMath.subtractCost(10, 10));
        assertEquals(4, BudgetMath.subtractCost(10, 6));
    }
}
