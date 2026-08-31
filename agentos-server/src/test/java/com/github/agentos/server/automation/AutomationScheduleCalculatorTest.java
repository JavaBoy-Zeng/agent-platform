package com.github.agentos.server.automation;

import com.github.agentos.server.automation.AutomationModels.IntervalUnit;
import com.github.agentos.server.automation.AutomationModels.PeriodMode;
import com.github.agentos.server.automation.AutomationModels.PeriodUnit;
import com.github.agentos.server.automation.AutomationModels.TriggerDefinition;
import com.github.agentos.server.automation.AutomationModels.TriggerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationScheduleCalculatorTest {

    private final AutomationScheduleCalculator calculator = new AutomationScheduleCalculator();

    @Test
    void calculatesDailyAndWeeklyInTaskTimeZone() {
        TriggerDefinition daily = basic(PeriodUnit.DAILY, "08:30", null, null, "Asia/Shanghai");
        assertThat(calculator.next(daily, Instant.parse("2026-08-30T00:20:00Z")))
                .isEqualTo(Instant.parse("2026-08-30T00:30:00Z"));

        TriggerDefinition weekly = basic(PeriodUnit.WEEKLY, "09:00", 1, null, "Asia/Shanghai");
        assertThat(calculator.next(weekly, Instant.parse("2026-08-30T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-31T01:00:00Z"));
    }

    @Test
    void monthlyDayFallsBackToLastDay() {
        TriggerDefinition monthly = basic(PeriodUnit.MONTHLY, "10:00", null, 31, "Asia/Shanghai");
        assertThat(calculator.next(monthly, Instant.parse("2027-02-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-02-28T02:00:00Z"));
    }

    @Test
    void unixCronSupportsRangesListsStepsAndUnixDayOrSemantics() {
        TriggerDefinition cron = new TriggerDefinition(TriggerType.PERIOD, PeriodMode.CRON,
                null, null, null, null, "*/15 9-10 * * 1-5", null, null, "Asia/Shanghai");
        assertThat(calculator.next(cron, Instant.parse("2026-08-31T01:01:00Z")))
                .isEqualTo(Instant.parse("2026-08-31T01:15:00Z"));

        TriggerDefinition dayOr = new TriggerDefinition(TriggerType.PERIOD, PeriodMode.CRON,
                null, null, null, null, "0 8 1 * 1", null, null, "UTC");
        assertThat(calculator.next(dayOr, Instant.parse("2026-09-01T08:01:00Z")))
                .isEqualTo(Instant.parse("2026-09-07T08:00:00Z"));
    }

    @Test
    void intervalBoundsAndNoBackfillAdvancementAreEnforced() {
        TriggerDefinition interval = new TriggerDefinition(TriggerType.INTERVAL, null, null,
                null, null, null, null, 30, IntervalUnit.MINUTES, "UTC");
        assertThat(calculator.nextFuture(interval, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T02:01:00Z")))
                .isEqualTo(Instant.parse("2026-01-01T02:30:00Z"));

        TriggerDefinition tooShort = new TriggerDefinition(TriggerType.INTERVAL, null, null,
                null, null, null, null, 4, IntervalUnit.MINUTES, "UTC");
        assertThatThrownBy(() -> calculator.validate(tooShort))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 分钟");
    }

    @Test
    void springGapMovesToFirstValidLocalTimeAndOverlapUsesFirstOffset() {
        TriggerDefinition gap = basic(PeriodUnit.DAILY, "02:30", null, null, "America/New_York");
        assertThat(calculator.next(gap, Instant.parse("2026-03-08T05:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));

        TriggerDefinition overlapCron = new TriggerDefinition(TriggerType.PERIOD, PeriodMode.CRON,
                null, null, null, null, "30 1 * * *", null, null, "America/New_York");
        assertThat(calculator.next(overlapCron, Instant.parse("2026-11-01T04:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    private static TriggerDefinition basic(
            PeriodUnit unit, String time, Integer weekday, Integer monthDay, String zone) {
        return new TriggerDefinition(TriggerType.PERIOD, PeriodMode.BASIC, unit, time,
                weekday, monthDay, null, null, null, zone);
    }
}
