package io.github.patchatlas.run;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RunListPage(List<RunSummary> items, Optional<String> nextCursor) {

    public RunListPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
