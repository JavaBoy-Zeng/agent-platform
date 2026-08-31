package com.github.agentos.server.automation;

import com.github.agentos.server.automation.AutomationModels.IntervalUnit;
import com.github.agentos.server.automation.AutomationModels.PeriodMode;
import com.github.agentos.server.automation.AutomationModels.PeriodUnit;
import com.github.agentos.server.automation.AutomationModels.SchedulePreview;
import com.github.agentos.server.automation.AutomationModels.TriggerDefinition;
import com.github.agentos.server.automation.AutomationModels.TriggerType;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 计算基础周期、Unix 五段 Cron 与固定间隔的未来触发时间。 */
public final class AutomationScheduleCalculator {

    private static final Duration MIN_INTERVAL = Duration.ofMinutes(5);
    private static final Duration MAX_INTERVAL = Duration.ofDays(365);

    public TriggerDefinition validate(TriggerDefinition value) {
        if (value == null || value.type() == null) {
            throw new IllegalArgumentException("trigger.type must be provided");
        }
        ZoneId zone = zone(value.timeZone());
        if (value.type() == TriggerType.INTERVAL) {
            if (value.every() == null || value.every() < 1 || value.intervalUnit() == null) {
                throw new IllegalArgumentException("间隔触发必须提供正整数 every 和 intervalUnit");
            }
            Duration duration = interval(value.every(), value.intervalUnit());
            if (duration.compareTo(MIN_INTERVAL) < 0 || duration.compareTo(MAX_INTERVAL) > 0) {
                throw new IllegalArgumentException("间隔必须在 5 分钟到 365 天之间");
            }
            return new TriggerDefinition(TriggerType.INTERVAL, null, null, null, null, null,
                    null, value.every(), value.intervalUnit(), zone.getId());
        }
        if (value.periodMode() == null) {
            throw new IllegalArgumentException("周期触发必须提供 periodMode");
        }
        if (value.periodMode() == PeriodMode.CRON) {
            UnixCron.parse(requireText(value.cron(), "cron"));
            return new TriggerDefinition(TriggerType.PERIOD, PeriodMode.CRON, null, null,
                    null, null, value.cron().trim(), null, null, zone.getId());
        }
        if (value.periodUnit() == null) {
            throw new IllegalArgumentException("基础周期必须提供 periodUnit");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(requireText(value.time(), "time"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("time 必须使用 HH:mm 格式", exception);
        }
        Integer weekday = value.weekday();
        Integer monthDay = value.monthDay();
        if (value.periodUnit() == PeriodUnit.WEEKLY && (weekday == null || weekday < 1 || weekday > 7)) {
            throw new IllegalArgumentException("weekly weekday 必须在 1 到 7 之间");
        }
        if (value.periodUnit() == PeriodUnit.MONTHLY && (monthDay == null || monthDay < 1 || monthDay > 31)) {
            throw new IllegalArgumentException("monthly monthDay 必须在 1 到 31 之间");
        }
        return new TriggerDefinition(TriggerType.PERIOD, PeriodMode.BASIC, value.periodUnit(),
                time.truncatedTo(ChronoUnit.MINUTES).toString(), weekday, monthDay,
                null, null, null, zone.getId());
    }

    public Instant next(TriggerDefinition trigger, Instant after) {
        TriggerDefinition value = validate(trigger);
        if (value.type() == TriggerType.INTERVAL) {
            return after.plus(interval(value.every(), value.intervalUnit()));
        }
        ZoneId zone = zone(value.timeZone());
        if (value.periodMode() == PeriodMode.CRON) {
            return UnixCron.parse(value.cron()).next(after, zone);
        }
        return nextBasic(value, after, zone);
    }

    /** 跳过停机期间的旧触发，返回严格晚于 now 的下一次时间。 */
    public Instant nextFuture(TriggerDefinition trigger, Instant anchor, Instant now) {
        Instant next = next(trigger, anchor);
        int guard = 0;
        while (!next.isAfter(now)) {
            next = next(trigger, next);
            if (++guard > 100_000) throw new IllegalStateException("无法推进自动化触发时间");
        }
        return next;
    }

    public SchedulePreview preview(TriggerDefinition trigger, Instant now) {
        TriggerDefinition value = validate(trigger);
        List<Instant> times = new ArrayList<>();
        Instant cursor = now;
        for (int index = 0; index < 3; index++) {
            cursor = next(value, cursor);
            times.add(cursor);
        }
        return new SchedulePreview(summary(value), List.copyOf(times));
    }

    public String summary(TriggerDefinition value) {
        TriggerDefinition trigger = validate(value);
        if (trigger.type() == TriggerType.INTERVAL) {
            String unit = switch (trigger.intervalUnit()) {
                case MINUTES -> "分钟";
                case HOURS -> "小时";
                case DAYS -> "天";
            };
            return "每 " + trigger.every() + " " + unit;
        }
        if (trigger.periodMode() == PeriodMode.CRON) return "Cron · " + trigger.cron();
        return switch (trigger.periodUnit()) {
            case DAILY -> "每天 " + trigger.time();
            case WEEKLY -> "每周" + weekdayLabel(trigger.weekday()) + " " + trigger.time();
            case MONTHLY -> "每月 " + trigger.monthDay() + " 日 " + trigger.time();
        };
    }

    private Instant nextBasic(TriggerDefinition value, Instant after, ZoneId zone) {
        ZonedDateTime localAfter = after.atZone(zone);
        LocalTime time = LocalTime.parse(value.time());
        LocalDate candidate = localAfter.toLocalDate();
        for (int count = 0; count < 800; count++) {
            LocalDate date = switch (value.periodUnit()) {
                case DAILY -> candidate;
                case WEEKLY -> candidate.with(TemporalAdjusters.nextOrSame(
                        java.time.DayOfWeek.of(value.weekday())));
                case MONTHLY -> {
                    int day = Math.min(value.monthDay(), candidate.lengthOfMonth());
                    LocalDate current = candidate.withDayOfMonth(day);
                    if (current.isBefore(candidate)) {
                        LocalDate nextMonth = candidate.plusMonths(1).withDayOfMonth(1);
                        current = nextMonth.withDayOfMonth(Math.min(value.monthDay(), nextMonth.lengthOfMonth()));
                    }
                    yield current;
                }
            };
            Instant instant = resolve(date.atTime(time), zone);
            if (instant.isAfter(after)) return instant;
            candidate = switch (value.periodUnit()) {
                case DAILY -> date.plusDays(1);
                case WEEKLY -> date.plusWeeks(1);
                case MONTHLY -> date.plusMonths(1).withDayOfMonth(1);
            };
        }
        throw new IllegalStateException("无法计算下一次基础周期触发时间");
    }

    private static Instant resolve(LocalDateTime value, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(value);
        if (!offsets.isEmpty()) return value.atOffset(offsets.get(0)).toInstant();
        return value.atZone(zone).toInstant();
    }

    private static Duration interval(int every, IntervalUnit unit) {
        try {
            return switch (unit) {
                case MINUTES -> Duration.ofMinutes(every);
                case HOURS -> Duration.ofHours(every);
                case DAYS -> Duration.ofDays(every);
            };
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("间隔数值过大", exception);
        }
    }

    private static ZoneId zone(String value) {
        try {
            return ZoneId.of(requireText(value, "timeZone"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timeZone 必须是有效的 IANA 时区", exception);
        }
    }

    private static String weekdayLabel(int day) {
        return List.of("", "一", "二", "三", "四", "五", "六", "日").get(day);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    /** 支持星号、列表、范围和步长的 Unix 五段 Cron。 */
    static final class UnixCron {
        private final Field minute;
        private final Field hour;
        private final Field dayOfMonth;
        private final Field month;
        private final Field dayOfWeek;

        private UnixCron(Field minute, Field hour, Field dayOfMonth, Field month, Field dayOfWeek) {
            this.minute = minute;
            this.hour = hour;
            this.dayOfMonth = dayOfMonth;
            this.month = month;
            this.dayOfWeek = dayOfWeek;
        }

        static UnixCron parse(String expression) {
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5) throw new IllegalArgumentException("Cron 必须包含 5 段：分 时 日 月 周");
            return new UnixCron(
                    Field.parse(fields[0], 0, 59, false),
                    Field.parse(fields[1], 0, 23, false),
                    Field.parse(fields[2], 1, 31, false),
                    Field.parse(fields[3], 1, 12, false),
                    Field.parse(fields[4], 0, 7, true));
        }

        Instant next(Instant after, ZoneId zone) {
            ZonedDateTime cursor = after.atZone(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            ZonedDateTime limit = cursor.plusYears(5);
            while (cursor.isBefore(limit)) {
                if (matches(cursor) && firstOffset(cursor)) return cursor.toInstant();
                cursor = cursor.plusMinutes(1);
            }
            throw new IllegalArgumentException("Cron 在未来五年内没有可执行时间");
        }

        private boolean matches(ZonedDateTime value) {
            int dow = value.getDayOfWeek().getValue() % 7;
            boolean domMatch = dayOfMonth.matches(value.getDayOfMonth());
            boolean dowMatch = dayOfWeek.matches(dow);
            boolean dayMatch = dayOfMonth.wildcard && dayOfWeek.wildcard
                    || dayOfMonth.wildcard && dowMatch
                    || dayOfWeek.wildcard && domMatch
                    || domMatch || dowMatch;
            return minute.matches(value.getMinute()) && hour.matches(value.getHour())
                    && month.matches(value.getMonthValue()) && dayMatch;
        }

        private static boolean firstOffset(ZonedDateTime value) {
            List<ZoneOffset> offsets = value.getZone().getRules().getValidOffsets(value.toLocalDateTime());
            return offsets.size() < 2 || value.getOffset().equals(offsets.get(0));
        }

        private record Field(Set<Integer> values, boolean wildcard) {
            boolean matches(int value) { return values.contains(value); }

            static Field parse(String raw, int min, int max, boolean normalizeSunday) {
                boolean wildcard = raw.equals("*");
                Set<Integer> values = new HashSet<>();
                for (String token : raw.toUpperCase(Locale.ROOT).split(",")) {
                    String[] stepParts = token.split("/", -1);
                    if (stepParts.length > 2) throw invalid(raw);
                    int step = stepParts.length == 2 ? parseNumber(stepParts[1], 1, max - min + 1, raw) : 1;
                    String base = stepParts[0];
                    int start;
                    int end;
                    if (base.equals("*")) {
                        start = min; end = max;
                    } else if (base.contains("-")) {
                        String[] range = base.split("-", -1);
                        if (range.length != 2) throw invalid(raw);
                        start = parseNumber(range[0], min, max, raw);
                        end = parseNumber(range[1], min, max, raw);
                        if (start > end) throw invalid(raw);
                    } else {
                        start = parseNumber(base, min, max, raw);
                        end = stepParts.length == 2 ? max : start;
                    }
                    for (int value = start; value <= end; value += step) {
                        values.add(normalizeSunday && value == 7 ? 0 : value);
                    }
                }
                if (values.isEmpty()) throw invalid(raw);
                return new Field(Set.copyOf(values), wildcard);
            }

            private static int parseNumber(String value, int min, int max, String raw) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed < min || parsed > max) throw invalid(raw);
                    return parsed;
                } catch (NumberFormatException exception) {
                    throw invalid(raw);
                }
            }

            private static IllegalArgumentException invalid(String value) {
                return new IllegalArgumentException("无效的 Cron 字段: " + value);
            }
        }
    }
}
