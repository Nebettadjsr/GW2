package model;

public record MaterialStack(
        int itemId,
        int category,
        int count,
        String binding
) {}