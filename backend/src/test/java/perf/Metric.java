package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One measured KPI with its unit and measurement boundary, or an explicit "unavailable" with the
 * reason (STORY-PERF-005 AC2: never an invented value). Exactly one of {@code value} and
 * {@code unavailableReason} is present.
 */
public record Metric(String name, Double value, String unit, String boundary, String unavailableReason) {

    public Metric {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("metric name is required");
        if (boundary == null || boundary.isBlank()) throw new IllegalArgumentException("boundary is required");
        boolean measured = value != null;
        boolean unavailable = unavailableReason != null && !unavailableReason.isBlank();
        if (measured == unavailable) {
            throw new IllegalArgumentException("metric " + name + " needs exactly one of a value or an unavailable reason");
        }
        if (measured && (value.isNaN() || value.isInfinite())) {
            throw new IllegalArgumentException("metric " + name + " is not finite");
        }
        if (measured && (unit == null || unit.isBlank())) {
            throw new IllegalArgumentException("metric " + name + " needs a unit");
        }
    }

    public static Metric of(String name, double value, String unit, String boundary) {
        return new Metric(name, value, unit, boundary, null);
    }

    public static Metric unavailable(String name, String unit, String boundary, String reason) {
        return new Metric(name, null, unit, boundary, reason);
    }

    public boolean available() {
        return value != null;
    }

    ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        if (available()) {
            node.put("value", value);
        } else {
            node.putNull("value");
            node.put("unavailableReason", unavailableReason);
        }
        if (unit != null) node.put("unit", unit);
        node.put("boundary", boundary);
        return node;
    }
}
