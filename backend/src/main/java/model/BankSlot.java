package model;

public record BankSlot(
        int slot,
        Integer itemId,
        Integer count,
        String binding,
        String boundTo,
        Integer charges,
        Integer statsId,
        String statsAttrsJson // JSON string for Postgres jsonb column
) {}