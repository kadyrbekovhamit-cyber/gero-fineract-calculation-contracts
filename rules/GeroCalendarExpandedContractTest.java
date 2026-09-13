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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeroCalendarExpandedContractTest {

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

    @ParameterizedTest(name = "{0}, fiscal month={1}, start={2}, business date={3}, months={4}")
    @MethodSource("fiscalCycles")
    void firstFiscalCycleMustEqualSimpleInterestBeforeItsOnlyCompoundingBoundary(SavingsCompoundingInterestPeriodType type, int fiscalMonth,
            LocalDate start, LocalDate businessDate, int months) {
        setBusinessDate(businessDate);
        LocalDate end = start.plusMonths(months).minusDays(1);
        PostingPeriod period = PostingPeriod.createFrom(LocalDateInterval.create(start, end), Money.of(CURRENCY, new BigDecimal("1000")),
                List.of(), CURRENCY, type, SavingsInterestCalculationType.DAILY_BALANCE, new BigDecimal("0.365"), 365L, end, List.of(),
                false, Money.zero(CURRENCY), false, false, fiscalMonth);
        period.calculateInterest(new CompoundInterestValues(BigDecimal.ZERO, BigDecimal.ZERO));
        // With principal 1000, annual rate .365 and denominator 365, each day earns exactly 1.
        BigDecimal expectedInterest = BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end.plusDays(1)));
        assertEquals(0, expectedInterest.compareTo(period.getInterestEarned().getAmount()),
                "No interest may compound before the end of the first complete fiscal cycle");
        assertEquals(1, period.getCompoundingPeriods().size());
    }

    private static List<Arguments> fiscalCycles() {
        List<Arguments> cases = new ArrayList<>();
        for (int year : List.of(2023, 2024)) {
            for (int fiscalMonth = 1; fiscalMonth <= 12; fiscalMonth++) {
                for (LocalDate businessDate : List.of(LocalDate.of(2024, 6, 15), LocalDate.of(2026, 6, 15))) {
                    LocalDate start = LocalDate.of(year, fiscalMonth, 1);
                    cases.add(Arguments.of(SavingsCompoundingInterestPeriodType.QUATERLY, fiscalMonth, start, businessDate, 3));
                    cases.add(Arguments.of(SavingsCompoundingInterestPeriodType.BI_ANNUAL, fiscalMonth, start, businessDate, 6));
                    cases.add(Arguments.of(SavingsCompoundingInterestPeriodType.ANNUAL, fiscalMonth, start, businessDate, 12));
                }
            }
        }
        return cases;
    }

    private static void setBusinessDate(LocalDate date) {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, date)));
    }
}
