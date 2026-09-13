/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.domain.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.LocalDateInterval;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.SavingsCompoundingInterestPeriodType;
import org.apache.fineract.portfolio.savings.SavingsInterestCalculationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class GeroCalendarContractTest {

    private static final String TENANT = "calendar-invariant-audit";
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, null);
    private FineractPlatformTenant originalTenant;
    private HashMap<BusinessDateType, LocalDate> originalBusinessDates;

    @BeforeEach
    void setUp() {
        originalTenant = ThreadLocalContextUtil.getTenant();
        try {
            originalBusinessDates = ThreadLocalContextUtil.getBusinessDates();
        } catch (IllegalArgumentException ignored) {
            originalBusinessDates = new HashMap<>();
        }
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(99L, TENANT, "Calendar audit", "UTC", null));
        MoneyHelper.initializeTenantRoundingMode(TENANT, 4);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.setTenant(originalTenant);
        ThreadLocalContextUtil.setBusinessDates(originalBusinessDates);
        MoneyHelper.clearCacheForTenant(TENANT);
    }

    @ParameterizedTest(name = "nonLeapFebruary [{index}] {arguments}")
    @CsvSource({ "QUATERLY, 2024-06-15, 2025-02-28", "BI_ANNUAL, 2024-06-15, 2025-02-28", "QUATERLY, 2024-06-15, 2023-02-28",
            "BI_ANNUAL, 2024-06-15, 2023-02-28", "QUATERLY, 2025-06-15, 2025-02-28", "BI_ANNUAL, 2025-06-15, 2025-02-28" })
    void validNonLeapFebruaryMustNotDependOnBusinessYear(SavingsCompoundingInterestPeriodType type, LocalDate businessDate,
            LocalDate date) {
        setBusinessDate(businessDate);
        PostingPeriod period = posting(date, type);
        CompoundInterestValues values = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
        period.calculateInterest(values);
        assertEquals(0, new BigDecimal("1.00").compareTo(period.getInterestEarned().getAmount()));
        assertEquals(0, BigDecimal.ONE.compareTo(values.getcompoundedInterest()));
        assertEquals(0, BigDecimal.ZERO.compareTo(values.getuncompoundedInterest()));
    }

    @ParameterizedTest(name = "leapFebruaryBoundary [{index}] {arguments}")
    @EnumSource(value = SavingsCompoundingInterestPeriodType.class, names = { "QUATERLY", "BI_ANNUAL" })
    void leapFebruaryMustNotCompoundOneDayEarly(SavingsCompoundingInterestPeriodType type) {
        LocalDate date = LocalDate.of(2024, 2, 28);
        for (LocalDate businessDate : List.of(LocalDate.of(2024, 6, 15), LocalDate.of(2026, 6, 15))) {
            setBusinessDate(businessDate);
            PostingPeriod period = posting(date, type);
            CompoundInterestValues values = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
            period.calculateInterest(values);
            assertEquals(0, BigDecimal.ZERO.compareTo(values.getcompoundedInterest()),
                    "February 28 is not the last day of February 2024; businessDate=" + businessDate);
            assertEquals(0, BigDecimal.ONE.compareTo(values.getuncompoundedInterest()));
        }
    }

    @ParameterizedTest(name = "annualTargetYear [{index}] {arguments}")
    @CsvSource({ "2023-03-01, 2024-02-29, 366.00", "2024-03-01, 2025-02-28, 365.00", "2025-03-01, 2026-02-28, 365.00" })
    void annualCompoundingMustUseMonthEndInTargetYear(LocalDate start, LocalDate end, BigDecimal expectedInterest) {
        setBusinessDate(LocalDate.of(2026, 9, 7));
        PostingPeriod period = PostingPeriod.createFrom(LocalDateInterval.create(start, end), Money.of(CURRENCY, new BigDecimal("1000")),
                List.of(), CURRENCY, SavingsCompoundingInterestPeriodType.ANNUAL, SavingsInterestCalculationType.DAILY_BALANCE,
                new BigDecimal("0.365"), 365L, end, List.of(), false, Money.zero(CURRENCY), false, false, 3);
        period.calculateInterest(new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(0, expectedInterest.compareTo(period.getInterestEarned().getAmount()),
                "Interest before the first annual compounding boundary must be principal * rate * days / 365; actual="
                        + period.getInterestEarned().getAmount());
        assertEquals(1, period.getCompoundingPeriods().size(), "One fiscal year must form one compounding period");
    }

    @ParameterizedTest(name = "crossYearBoundary [{index}] {arguments}")
    @EnumSource(value = SavingsCompoundingInterestPeriodType.class, names = { "QUATERLY", "BI_ANNUAL" })
    void nextYearFebruaryBoundaryMustRemainIndependentOfBusinessYear(SavingsCompoundingInterestPeriodType type) {
        LocalDate start = LocalDate.of(2023, 12, 1);
        LocalDate end = LocalDate.of(2024, 2, 28);
        for (LocalDate businessDate : List.of(LocalDate.of(2024, 6, 15), LocalDate.of(2026, 6, 15))) {
            setBusinessDate(businessDate);
            PostingPeriod period = PostingPeriod.createFrom(LocalDateInterval.create(start, end),
                    Money.of(CURRENCY, new BigDecimal("1000")), List.of(), CURRENCY, type, SavingsInterestCalculationType.DAILY_BALANCE,
                    new BigDecimal("0.365"), 365L, end, List.of(), false, Money.zero(CURRENCY), false, false, 3);
            CompoundInterestValues values = new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO);
            period.calculateInterest(values);
            assertEquals(0, BigDecimal.ZERO.compareTo(values.getcompoundedInterest()),
                    "The first fiscal boundary is February 29, 2024; businessDate=" + businessDate);
            assertEquals(0, new BigDecimal("90").compareTo(values.getuncompoundedInterest()));
        }
    }

    private static PostingPeriod posting(LocalDate date, SavingsCompoundingInterestPeriodType type) {
        return PostingPeriod.createFrom(LocalDateInterval.create(date, date), Money.of(CURRENCY, new BigDecimal("1000")), List.of(),
                CURRENCY, type, SavingsInterestCalculationType.DAILY_BALANCE, new BigDecimal("0.365"), 365L, date, List.of(), false,
                Money.zero(CURRENCY), false, false, 3);
    }

    private static void setBusinessDate(LocalDate date) {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    }
}
