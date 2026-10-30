//! Civil (wall-clock) timestamps, without pulling in a date-time dependency.
//!
//! M-Pesa confirms transactions in East Africa Time. The parser keeps the
//! wall-clock fields it saw in the message (`5/10/26 at 2:15 PM`) and converts
//! to a Unix timestamp with a fixed [`EAT_OFFSET_SECONDS`] offset. Storing the
//! civil fields keeps the ledger readable and makes tests independent of the
//! host time zone.

use std::fmt;

/// East Africa Time is UTC+3 all year; Kenya has no daylight saving.
pub const EAT_OFFSET_SECONDS: i64 = 3 * 60 * 60;

/// A validated wall-clock timestamp as printed in an M-Pesa message.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CivilTime {
    /// Full year, e.g. `2026`.
    pub year: i32,
    /// Month, `1..=12`.
    pub month: u8,
    /// Day of month, `1..=31` (validated against the month).
    pub day: u8,
    /// Hour in 24-hour form, `0..=23`.
    pub hour: u8,
    /// Minute, `0..=59`.
    pub minute: u8,
}

/// Why a timestamp was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimeError {
    /// Year outside the supported range (`1..=9999`).
    Year,
    /// Month outside `1..=12`.
    Month,
    /// Day outside the month's length.
    Day,
    /// Hour outside `0..=23`.
    Hour,
    /// Minute outside `0..=59`.
    Minute,
    /// 12-hour clock hour outside `1..=12`.
    Hour12,
}

impl fmt::Display for TimeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Year => "year out of range",
            Self::Month => "month out of range",
            Self::Day => "day out of range for the month",
            Self::Hour => "hour out of range",
            Self::Minute => "minute out of range",
            Self::Hour12 => "12-hour clock hour out of range",
        })
    }
}

impl std::error::Error for TimeError {}

impl CivilTime {
    /// Builds a timestamp, rejecting impossible calendar values.
    pub fn new(year: i32, month: u8, day: u8, hour: u8, minute: u8) -> Result<Self, TimeError> {
        if !(1..=9999).contains(&year) {
            return Err(TimeError::Year);
        }
        if !(1..=12).contains(&month) {
            return Err(TimeError::Month);
        }
        if day == 0 || day > days_in_month(year, month) {
            return Err(TimeError::Day);
        }
        if hour > 23 {
            return Err(TimeError::Hour);
        }
        if minute > 59 {
            return Err(TimeError::Minute);
        }
        Ok(Self {
            year,
            month,
            day,
            hour,
            minute,
        })
    }

    /// Builds a timestamp from the 12-hour clock M-Pesa prints (`2:15 PM`).
    pub fn from_12_hour(
        year: i32,
        month: u8,
        day: u8,
        hour12: u8,
        minute: u8,
        is_pm: bool,
    ) -> Result<Self, TimeError> {
        if !(1..=12).contains(&hour12) {
            return Err(TimeError::Hour12);
        }
        let hour = match (hour12, is_pm) {
            (12, false) => 0,
            (12, true) => 12,
            (h, false) => h,
            (h, true) => h + 12,
        };
        Self::new(year, month, day, hour, minute)
    }

    /// Seconds since the Unix epoch, interpreting the fields as East Africa Time.
    #[must_use]
    pub fn unix_seconds(&self) -> i64 {
        let days = days_from_civil(self.year, self.month, self.day);
        days * 86_400 + i64::from(self.hour) * 3_600 + i64::from(self.minute) * 60
            - EAT_OFFSET_SECONDS
    }
}

impl fmt::Display for CivilTime {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:04}-{:02}-{:02} {:02}:{:02} EAT",
            self.year, self.month, self.day, self.hour, self.minute
        )
    }
}

/// Number of days in `month`, honouring leap years.
#[must_use]
pub fn days_in_month(year: i32, month: u8) -> u8 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(year) => 29,
        2 => 28,
        _ => 0,
    }
}

/// Gregorian leap-year rule.
#[must_use]
pub fn is_leap_year(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

/// Days since `1970-01-01` for a civil date (Howard Hinnant's `days_from_civil`).
#[must_use]
pub fn days_from_civil(year: i32, month: u8, day: u8) -> i64 {
    let y = i64::from(year) - i64::from(month <= 2);
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let year_of_era = y - era * 400;
    let month_index = i64::from((month + 9) % 12);
    let day_of_year = (153 * month_index + 2) / 5 + i64::from(day) - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_anchor_matches_the_algorithm() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(days_from_civil(1970, 1, 2), 1);
        assert_eq!(days_from_civil(1969, 12, 31), -1);
        assert_eq!(days_from_civil(2000, 1, 1), 10_957);
        assert_eq!(days_from_civil(2024, 2, 29), 19_782);
    }

    #[test]
    fn unix_seconds_uses_the_eat_offset() {
        // 2000-01-01 00:00 EAT == 1999-12-31T21:00Z
        let midnight = CivilTime::new(2000, 1, 1, 0, 0).unwrap();
        assert_eq!(midnight.unix_seconds(), 946_674_000);
        // 2024-02-29 12:00 EAT
        let leap = CivilTime::new(2024, 2, 29, 12, 0).unwrap();
        assert_eq!(leap.unix_seconds(), 1_709_197_200);
        // 2026-10-05 14:15 EAT (a fixture timestamp)
        let fixture = CivilTime::from_12_hour(2026, 10, 5, 2, 15, true).unwrap();
        assert_eq!(fixture.unix_seconds(), 1_791_198_900);
    }

    #[test]
    fn twelve_hour_clock_edges() {
        assert_eq!(
            CivilTime::from_12_hour(2026, 1, 1, 12, 0, false)
                .unwrap()
                .hour,
            0
        );
        assert_eq!(
            CivilTime::from_12_hour(2026, 1, 1, 12, 0, true)
                .unwrap()
                .hour,
            12
        );
        assert_eq!(
            CivilTime::from_12_hour(2026, 1, 1, 1, 0, false)
                .unwrap()
                .hour,
            1
        );
        assert_eq!(
            CivilTime::from_12_hour(2026, 1, 1, 1, 0, true)
                .unwrap()
                .hour,
            13
        );
        assert_eq!(
            CivilTime::from_12_hour(2026, 1, 1, 13, 0, true),
            Err(TimeError::Hour12)
        );
    }

    #[test]
    fn rejects_impossible_dates() {
        assert_eq!(CivilTime::new(2026, 13, 1, 0, 0), Err(TimeError::Month));
        assert_eq!(CivilTime::new(2026, 2, 29, 0, 0), Err(TimeError::Day));
        assert_eq!(CivilTime::new(2024, 2, 29, 0, 0).map(|t| t.day), Ok(29));
        assert_eq!(CivilTime::new(2026, 4, 31, 0, 0), Err(TimeError::Day));
        assert_eq!(CivilTime::new(2026, 1, 1, 24, 0), Err(TimeError::Hour));
        assert_eq!(CivilTime::new(2026, 1, 1, 0, 60), Err(TimeError::Minute));
        assert_eq!(CivilTime::new(0, 1, 1, 0, 0), Err(TimeError::Year));
    }

    #[test]
    fn leap_years() {
        assert!(is_leap_year(2024));
        assert!(is_leap_year(2000));
        assert!(!is_leap_year(1900));
        assert!(!is_leap_year(2026));
    }

    #[test]
    fn display_is_iso_like() {
        let t = CivilTime::new(2026, 10, 5, 14, 15).unwrap();
        assert_eq!(t.to_string(), "2026-10-05 14:15 EAT");
    }
}
