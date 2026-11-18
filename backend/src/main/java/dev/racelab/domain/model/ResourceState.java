package dev.racelab.domain.model;

import java.util.Map;

public record ResourceState(String resource, String label, String type, Map<String, Object> attributes) {
}
