package com.runninggu.server.itinerary.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReorderItineraryBlocksRequest(@NotNull List<@NotNull Long> blockIds) {}
