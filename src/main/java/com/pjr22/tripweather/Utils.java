package com.pjr22.tripweather;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Utils {

   public static final DateTimeFormatter date_time_formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
   public static final String default_timezone_name = "America/Los_Angeles";

   public static ZonedDateTime getZonedDateTime(String date, String time, ZoneId zone) {
      LocalDateTime localDateTime = LocalDateTime.parse(String.format("%s %s", date, time), date_time_formatter);
      return localDateTime.atZone(zone);
   }

   /**
    * Convert datetime string from one timezone to another using LocationService
    * timezone data
    */
   public static String convertDateTime(String dateTimeStr, String fromTimezone, String toTimezone) {
      try {
         LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, date_time_formatter);

         ZoneId fromZoneId = ZoneId.of(fromTimezone);
         ZoneId toZoneId = ZoneId.of(toTimezone);

         ZonedDateTime fromZonedDateTime = localDateTime.atZone(fromZoneId);
         ZonedDateTime toZonedDateTime = fromZonedDateTime.withZoneSameInstant(toZoneId);

         return toZonedDateTime.format(date_time_formatter);
      } catch (Exception e) {
         System.err
               .println("Error converting datetime from " + fromTimezone + " to " + toTimezone + ": " + e.getMessage());
         return dateTimeStr; // Return original if conversion fails
      }
   }

}
