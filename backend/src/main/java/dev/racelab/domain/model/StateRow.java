package dev.racelab.domain.model;

/** One line of the "Initial | Current | Expected | Status" table. */
public record StateRow(String resource, String label, String attribute, String initial, String actual,
                       String expected, boolean ok) {
}
