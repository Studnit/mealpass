package com.mealtoken.service;

import java.time.Instant;
import java.time.LocalDate;

public record RedemptionResult(Long attendanceId, String referenceNumber, String hotelName, String mealName,
                               LocalDate date, Instant redeemedAt, int remainingTokens, boolean replay) {}
