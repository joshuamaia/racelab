package dev.racelab.domain.model;

/** Where the workers of an experiment run. DISTRIBUTED alternates workers between two independent JVMs. */
public enum Topology { SINGLE_JVM, DISTRIBUTED }
