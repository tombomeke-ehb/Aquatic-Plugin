package dev.aquatic.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Small weighted random picker. */
final class WeightedList<T> {

    private final List<T> values = new ArrayList<>();
    private final List<Double> weights = new ArrayList<>();
    private double total;

    void add(T value, double weight) {
        if (value == null || weight <= 0) {
            return;
        }
        values.add(value);
        weights.add(weight);
        total += weight;
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    int size() {
        return values.size();
    }

    List<T> values() {
        return values;
    }

    /** @return a random entry, or null when the list is empty. */
    T pick(Random random) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < values.size(); i++) {
            r -= weights.get(i);
            if (r < 0) {
                return values.get(i);
            }
        }
        return values.get(values.size() - 1);
    }
}
