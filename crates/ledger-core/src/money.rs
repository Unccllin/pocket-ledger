//! Integer-cent money handling.
//!
//! M-Pesa amounts are always written with two decimals (`Ksh1,234.56`), so the
//! ledger never stores floats: everything is `i64` cents.

use std::fmt;

/// Why a money string could not be parsed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoneyError {
    /// The string held no digits at all.
    Empty,
    /// A character that cannot appear in an amount was found.
    InvalidCharacter(char),
    /// A thousands separator was not surrounded by digits (e.g. `1,,000`).
    InvalidSeparator,
    /// More than two decimal places were supplied.
    TooManyDecimals,
    /// More than one decimal point was supplied.
    MultipleDecimals,
    /// The value does not fit in `i64` cents.
    Overflow,
}

impl fmt::Display for MoneyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => f.write_str("no digits found"),
            Self::InvalidCharacter(c) => write!(f, "invalid character {c:?}"),
            Self::InvalidSeparator => f.write_str("misplaced thousands separator"),
            Self::TooManyDecimals => f.write_str("more than two decimal places"),
            Self::MultipleDecimals => f.write_str("more than one decimal point"),
            Self::Overflow => f.write_str("amount does not fit in i64 cents"),
        }
    }
}

impl std::error::Error for MoneyError {}

/// Parses `Ksh1,234.56`, `KES 500`, `1,234` or `1234.5` into integer cents.
///
/// An optional `Ksh`/`KES` prefix is accepted (case-insensitive), commas are
/// allowed only between digits, and 0, 1 or 2 decimal places are accepted.
pub fn parse_cents(input: &str) -> Result<i64, MoneyError> {
    let mut s = input.trim();
    for prefix in ["ksh", "kes"] {
        let head = s.as_bytes().get(..prefix.len());
        if head.is_some_and(|h| h.eq_ignore_ascii_case(prefix.as_bytes())) {
            s = s[prefix.len()..].trim_start();
            break;
        }
    }
    if s.is_empty() {
        return Err(MoneyError::Empty);
    }

    let bytes = s.as_bytes();
    let mut whole: i64 = 0;
    let mut frac: i64 = 0;
    let mut decimals: u32 = 0;
    let mut seen_dot = false;
    let mut digits = 0usize;

    for (i, &byte) in bytes.iter().enumerate() {
        match byte {
            b'0'..=b'9' => {
                let digit = i64::from(byte - b'0');
                if seen_dot {
                    if decimals == 2 {
                        return Err(MoneyError::TooManyDecimals);
                    }
                    frac = frac * 10 + digit;
                    decimals += 1;
                } else {
                    whole = whole
                        .checked_mul(10)
                        .and_then(|v| v.checked_add(digit))
                        .ok_or(MoneyError::Overflow)?;
                }
                digits += 1;
            }
            b',' => {
                let prev_is_digit = i > 0 && bytes[i - 1].is_ascii_digit();
                let next_is_digit = bytes.get(i + 1).is_some_and(u8::is_ascii_digit);
                if !prev_is_digit || !next_is_digit {
                    return Err(MoneyError::InvalidSeparator);
                }
            }
            b'.' => {
                if seen_dot {
                    return Err(MoneyError::MultipleDecimals);
                }
                seen_dot = true;
            }
            other => return Err(MoneyError::InvalidCharacter(char::from(other))),
        }
    }

    if digits == 0 {
        return Err(MoneyError::Empty);
    }

    let cents = match decimals {
        0 => 0,
        1 => frac * 10,
        _ => frac,
    };
    whole
        .checked_mul(100)
        .and_then(|v| v.checked_add(cents))
        .ok_or(MoneyError::Overflow)
}

/// Formats integer cents as a plain decimal string, e.g. `123456` -> `1234.56`.
#[must_use]
pub fn format_cents(cents: i64) -> String {
    let sign = if cents < 0 { "-" } else { "" };
    let abs = cents.unsigned_abs();
    format!("{sign}{}.{:02}", abs / 100, abs % 100)
}

/// Scans an amount out of the text that follows a `Ksh` marker.
///
/// Stops at the first character that cannot be part of an amount, so
/// `"1,234.56."` yields `123456` and `"350.00 paid"` yields `35000`.
pub(crate) fn scan_amount_after_marker(text: &str) -> Result<i64, MoneyError> {
    let bytes = text.trim_start().as_bytes();
    let mut end = 0;
    let mut seen_dot = false;
    while end < bytes.len() {
        match bytes[end] {
            b'0'..=b'9' | b',' => end += 1,
            b'.' if !seen_dot => {
                seen_dot = true;
                end += 1;
            }
            _ => break,
        }
    }
    if end == 0 {
        return Err(MoneyError::Empty);
    }
    parse_cents(&text.trim_start()[..end])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_common_mpesa_amounts() {
        assert_eq!(parse_cents("Ksh500.00"), Ok(50_000));
        assert_eq!(parse_cents("Ksh1,234.56"), Ok(123_456));
        assert_eq!(parse_cents("Ksh0.00"), Ok(0));
        assert_eq!(parse_cents("499,500.00"), Ok(49_950_000));
        assert_eq!(parse_cents("kes 1,000"), Ok(100_000));
        assert_eq!(parse_cents("  12.5  "), Ok(1_250));
        assert_eq!(parse_cents("500."), Ok(50_000));
    }

    #[test]
    fn rejects_bad_money() {
        assert_eq!(parse_cents(""), Err(MoneyError::Empty));
        assert_eq!(parse_cents("Ksh"), Err(MoneyError::Empty));
        assert_eq!(parse_cents("12.345"), Err(MoneyError::TooManyDecimals));
        assert_eq!(parse_cents("1.2.3"), Err(MoneyError::MultipleDecimals));
        assert_eq!(parse_cents("1,,000"), Err(MoneyError::InvalidSeparator));
        assert_eq!(parse_cents(",100"), Err(MoneyError::InvalidSeparator));
        assert_eq!(parse_cents("-5"), Err(MoneyError::InvalidCharacter('-')));
        assert_eq!(parse_cents("12abc"), Err(MoneyError::InvalidCharacter('a')));
    }

    #[test]
    fn overflow_is_an_error_not_a_wrap() {
        assert_eq!(
            parse_cents("92233720368547758.08"),
            Err(MoneyError::Overflow)
        );
    }

    #[test]
    fn formats_cents() {
        assert_eq!(format_cents(50_000), "500.00");
        assert_eq!(format_cents(123_456), "1234.56");
        assert_eq!(format_cents(5), "0.05");
        assert_eq!(format_cents(-700), "-7.00");
    }

    /// Property-style check without a proptest dependency: a deterministic
    /// pseudo-random sweep must round-trip format -> parse for every value.
    #[test]
    fn format_then_parse_round_trips() {
        let mut state: u64 = 0x2545_F491_4F6C_DD1D;
        for _ in 0..20_000 {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            let cents = i64::from((state >> 33) as u32);
            let text = format_cents(cents);
            assert_eq!(parse_cents(&text), Ok(cents), "failed for {text}");
            let with_prefix = format!("Ksh{text}");
            assert_eq!(
                parse_cents(&with_prefix),
                Ok(cents),
                "failed for {with_prefix}"
            );
        }
    }

    /// Commas anywhere between digit groups must not change the value.
    #[test]
    fn thousands_separators_are_transparent() {
        for (plain, grouped) in [
            ("123456", "123,456"),
            ("1234567", "1,234,567"),
            ("123456789", "123,456,789"),
        ] {
            assert_eq!(
                parse_cents(plain),
                parse_cents(grouped),
                "{plain} vs {grouped}"
            );
            assert_eq!(
                parse_cents(grouped),
                Ok(plain.parse::<i64>().unwrap_or(0) * 100)
            );
        }
    }

    #[test]
    fn scan_stops_at_the_end_of_the_number() {
        assert_eq!(scan_amount_after_marker("1,234.56."), Ok(123_456));
        assert_eq!(scan_amount_after_marker("350.00 paid"), Ok(35_000));
        assert_eq!(scan_amount_after_marker(""), Err(MoneyError::Empty));
    }
}
