package dev.racelab.application.engine;

import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.ViolationKind;

import java.math.BigDecimal;
import java.util.List;

/**
 * A business invariant evaluated against values read from the database after the experiment.
 * Examples: {@code initial balance − confirmed withdrawals = final balance},
 * {@code confirmed reservations per seat ≤ 1}, {@code doctors on call ≥ 1}.
 */
public record Invariant(
        String rule,
        String description,
        Check check,
        BigDecimal expected,
        BigDecimal actual,
        Severity severity,
        String possibleCause,
        List<StrategyType> recommendedStrategies,
        List<String> transactions,
        ViolationKind kind) {

    public enum Check { EQUALS, AT_MOST, AT_LEAST }

    public static Builder rule(String rule) {
        return new Builder(rule);
    }

    public boolean holds() {
        int cmp = actual.compareTo(expected);
        return switch (check) {
            case EQUALS -> cmp == 0;
            case AT_MOST -> cmp <= 0;
            case AT_LEAST -> cmp >= 0;
        };
    }

    public static final class Builder {
        private final String rule;
        private String description = "";
        private Check check = Check.EQUALS;
        private BigDecimal expected;
        private BigDecimal actual;
        private Severity severity = Severity.HIGH;
        private String cause = "";
        private List<StrategyType> strategies = List.of();
        private List<String> transactions = List.of();
        private ViolationKind kind = ViolationKind.BUSINESS_INVARIANT;

        private Builder(String rule) {
            this.rule = rule;
        }

        public Builder description(String v) {
            description = v;
            return this;
        }

        public Builder equalTo(Number expectedValue, Number actualValue) {
            check = Check.EQUALS;
            expected = toDecimal(expectedValue);
            actual = toDecimal(actualValue);
            return this;
        }

        public Builder atMost(Number limit, Number actualValue) {
            check = Check.AT_MOST;
            expected = toDecimal(limit);
            actual = toDecimal(actualValue);
            return this;
        }

        public Builder atLeast(Number limit, Number actualValue) {
            check = Check.AT_LEAST;
            expected = toDecimal(limit);
            actual = toDecimal(actualValue);
            return this;
        }

        public Builder severity(Severity v) {
            severity = v;
            return this;
        }

        public Builder cause(String v) {
            cause = v;
            return this;
        }

        public Builder recommend(StrategyType... v) {
            strategies = List.of(v);
            return this;
        }

        public Builder transactions(List<String> v) {
            transactions = List.copyOf(v);
            return this;
        }

        public Builder kind(ViolationKind v) {
            kind = v;
            return this;
        }

        public Invariant build() {
            return new Invariant(rule, description, check, expected, actual, severity, cause, strategies, transactions, kind);
        }

        private static BigDecimal toDecimal(Number n) {
            return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
        }
    }
}
