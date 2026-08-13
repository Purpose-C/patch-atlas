package io.github.patchatlas.observability;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * 当前部署的价格依据。不是 Run 创建时冻结的历史报价。
 */
public record PricingReference(
        String provider,
        String model,
        BigDecimal inputUsdPerMillionTokens,
        BigDecimal outputUsdPerMillionTokens,
        LocalDate effectiveDate,
        String source) {

    public static final String USD = "USD";
    public static final String OPENAI = "openai";
    public static final int MAX_SOURCE_LENGTH = 512;

    public PricingReference {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(inputUsdPerMillionTokens, "inputUsdPerMillionTokens");
        Objects.requireNonNull(outputUsdPerMillionTokens, "outputUsdPerMillionTokens");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(source, "source");
    }

    public static Optional<PricingReference> parse(PricingFields fields) {
        Objects.requireNonNull(fields, "fields");
        boolean anyPresent = present(fields.provider())
                || present(fields.model())
                || present(fields.inputUsdPerMillionTokens())
                || present(fields.outputUsdPerMillionTokens())
                || present(fields.effectiveDate())
                || present(fields.source());
        if (!anyPresent) {
            return Optional.empty();
        }
        requirePresent(fields.provider(), "provider");
        requirePresent(fields.model(), "model");
        requirePresent(fields.inputUsdPerMillionTokens(), "input-usd-per-million-tokens");
        requirePresent(fields.outputUsdPerMillionTokens(), "output-usd-per-million-tokens");
        requirePresent(fields.effectiveDate(), "effective-date");
        requirePresent(fields.source(), "source");

        String provider = fields.provider().trim();
        if (!OPENAI.equals(provider)) {
            throw new IllegalArgumentException("pricing provider must be openai");
        }
        String model = fields.model().trim();
        if (model.isEmpty() || model.length() > 128) {
            throw new IllegalArgumentException("pricing model must be 1–128 characters");
        }
        BigDecimal inputRate = nonNegativeDecimal(fields.inputUsdPerMillionTokens(), "input rate");
        BigDecimal outputRate = nonNegativeDecimal(fields.outputUsdPerMillionTokens(), "output rate");
        LocalDate effectiveDate = parseDate(fields.effectiveDate());
        String source = fields.source().trim();
        if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("pricing source must be 1–512 characters");
        }
        return Optional.of(new PricingReference(provider, model, inputRate, outputRate, effectiveDate, source));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void requirePresent(String value, String name) {
        if (!present(value)) {
            throw new IllegalArgumentException("pricing requires " + name + " when any pricing field is set");
        }
    }

    private static BigDecimal nonNegativeDecimal(String raw, String name) {
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a decimal number", ex);
        }
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("pricing effective-date must be ISO YYYY-MM-DD", ex);
        }
    }
}
