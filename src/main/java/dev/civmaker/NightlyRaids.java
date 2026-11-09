package dev.civmaker;

import dev.civmaker.CivStore.Window;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

record NightlyRaids(String start, String end, String zone) {
    Window window(LocalDate date) {
        LocalTime begins = LocalTime.parse(start);
        LocalTime ends = LocalTime.parse(end);
        ZoneId timezone = ZoneId.of(zone);
        LocalDate endDate = ends.isAfter(begins) ? date : date.plusDays(1);
        return new Window(date.atTime(begins).atZone(timezone).toInstant().toEpochMilli(),
                endDate.atTime(ends).atZone(timezone).toInstant().toEpochMilli());
    }

    LocalDate dateAt(long time) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.of(zone)).toLocalDate();
    }
}
