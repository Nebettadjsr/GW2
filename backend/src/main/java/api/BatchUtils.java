package api;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class BatchUtils {
    private BatchUtils() {}

    /** Returns consecutive sublists of size <= batchSize. */
    public static <T> List<List<T>> chunk(List<T> list, int batchSize) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            out.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return out;
    }

    public static String idsParam(List<Integer> ids) {
        return ids.stream()
                .filter(x -> x != null && x > 0)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}