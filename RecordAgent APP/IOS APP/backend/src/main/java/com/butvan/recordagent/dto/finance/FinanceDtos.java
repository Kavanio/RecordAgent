package com.butvan.recordagent.dto.finance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** 账户与收支流水的 API 契约；金额统一使用分。 */
public final class FinanceDtos {
    private FinanceDtos() {}

    public record CreateAccount(
            @NotBlank @Size(max = 120) String name, @NotNull @Min(0) Long openingBalanceInCents) {}

    public record Account(UUID id, String name, long balanceInCents, long version) {}

    public record CreateTransaction(
            @NotNull UUID accountId,
            @NotBlank String type,
            @Size(max = 80) String category,
            @Size(max = 10000) String note,
            @NotNull @Min(1) Long amountInCents,
            @NotNull LocalDate date,
            @NotNull LocalTime time,
            @NotBlank String timeZone) {}

    public record Transaction(
            UUID id,
            UUID accountId,
            String type,
            String category,
            String note,
            long amountInCents,
            LocalDate date,
            LocalTime time,
            long balanceInCents) {}
}
