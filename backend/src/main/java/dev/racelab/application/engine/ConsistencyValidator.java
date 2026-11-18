package dev.racelab.application.engine;

import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.Violation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Evaluates business invariants. A rolled-back transaction is never an inconsistency by itself: only the
 * final state read from the database, compared with the operations that were really committed, decides.
 */
@Component
public class ConsistencyValidator {

    public List<Violation> validate(List<Invariant> invariants) {
        return invariants.stream().filter(i -> !i.holds()).map(ConsistencyValidator::toViolation).toList();
    }

    private static Violation toViolation(Invariant i) {
        String expected = switch (i.check()) {
            case EQUALS -> plain(i.expected());
            case AT_MOST -> "≤ " + plain(i.expected());
            case AT_LEAST -> "≥ " + plain(i.expected());
        };
        return new Violation(i.kind(), i.rule(), i.description(), expected, plain(i.actual()),
                plain(i.actual().subtract(i.expected())), i.transactions(), i.severity(), i.possibleCause(),
                i.recommendedStrategies());
    }

    /** Builds a state row; {@code ok} compares actual and expected numerically. */
    public static StateRow row(String resource, String label, String attribute, Number initial, Number actual,
                               Number expected) {
        boolean ok = expected == null || new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString())) == 0;
        return new StateRow(resource, label, attribute, str(initial), str(actual), str(expected), ok);
    }

    /** State row with an explicit verdict (e.g. "≥ 1"). */
    public static StateRow row(String resource, String label, String attribute, Object initial, Object actual,
                               String expected, boolean ok) {
        return new StateRow(resource, label, attribute, str(initial), str(actual), expected, ok);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? plain(b) : v.toString();
    }

    static String plain(BigDecimal v) {
        return v.toPlainString();
    }
}
