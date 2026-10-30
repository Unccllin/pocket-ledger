//! M-Pesa confirmations to [`Transaction`]s.
//!
//! The parser is deliberately forgiving about whitespace (M-Pesa messages
//! arrive with missing spaces, double spaces and mixed casing) but strict about
//! money and timestamps: anything ambiguous is an error, never a guess.
//!
//! Two failure modes matter to the queue:
//!
//! * [`ParseError::NotMpesa`] — the notification is unrelated personal traffic
//!   and must be filtered out **before** it is enqueued.
//! * every other variant — the text looks like an M-Pesa confirmation but does
//!   not match a known shape. It is retried a bounded number of times and then
//!   parked as `unparsed` for review, never silently dropped.

use std::fmt;

use crate::model::{Kind, Transaction};
use crate::money::{self, MoneyError};
use crate::time::CivilTime;

/// Why a message is not a usable transaction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ParseError {
    /// Not an M-Pesa confirmation at all: no transaction code, or no
    /// "Confirmed" marker. These are filtered out **before** enqueueing.
    NotMpesa,
    /// The confirmation does not match any known M-Pesa shape.
    UnknownKind,
    /// The transaction amount could not be read.
    MissingAmount(MoneyError),
    /// The counterparty could not be extracted.
    MissingCounterparty,
    /// The transaction timestamp could not be read.
    MissingTimestamp,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotMpesa => f.write_str("not an M-Pesa confirmation"),
            Self::UnknownKind => f.write_str("unrecognised M-Pesa confirmation shape"),
            Self::MissingAmount(e) => write!(f, "unreadable amount: {e}"),
            Self::MissingCounterparty => f.write_str("no counterparty"),
            Self::MissingTimestamp => f.write_str("no readable transaction timestamp"),
        }
    }
}

impl std::error::Error for ParseError {}

/// True when the text has the M-Pesa confirmation *shape*: a transaction code
/// plus "Confirmed".
///
/// Use this to decide whether to enqueue; use [`parse`] to decide whether the
/// message is actually understood. A message that passes this check but fails
/// [`parse`] is retried and then parked as `unparsed`.
#[must_use]
pub fn looks_like_mpesa(raw: &str) -> bool {
    let normalised = normalise(raw);
    extract_code(&normalised).is_some() && find_ci(&normalised, "confirmed").is_some()
}

/// Parses one M-Pesa confirmation message.
///
/// # Errors
///
/// Returns [`ParseError::NotMpesa`] for unrelated notifications, and another
/// [`ParseError`] when the message looks like M-Pesa but is not understood.
pub fn parse(raw: &str) -> Result<Transaction, ParseError> {
    let normalised = normalise(raw);
    // Shape check first: anything without a code *and* a confirmation is
    // personal traffic and must never reach the queue.
    let code = extract_code(&normalised).ok_or(ParseError::NotMpesa)?;
    let confirmed_at = find_ci(&normalised, "confirmed").ok_or(ParseError::NotMpesa)?;
    let after_confirmed = &normalised[confirmed_at + "confirmed".len()..];
    let kind = classify(after_confirmed).ok_or(ParseError::UnknownKind)?;

    let amount_cents = amount_after(&normalised, confirmed_at)?;
    // Structural: a confirmation without a timestamp is not usable, so this is
    // checked before field extraction so the error is the useful one.
    let occurred_at = parse_timestamp(&normalised).ok_or(ParseError::MissingTimestamp)?;
    let (counterparty, reference) = counterparty(kind, after_confirmed)?;
    let balance_cents = balance(&normalised);
    let cost_cents = cost(&normalised);

    Ok(Transaction {
        code: code.to_owned(),
        kind,
        amount_cents,
        counterparty,
        reference,
        balance_cents,
        cost_cents,
        occurred_at,
        raw_hash: content_hash(&normalised),
    })
}

/// Collapses every run of whitespace to a single space and trims the ends.
///
/// This is what makes `Confirmed.You` and `Confirmed.  You` equivalent without
/// splitting `Confirmed.Jane` into the wrong field.
#[must_use]
pub fn normalise(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut pending_space = false;
    for ch in raw.chars() {
        if ch.is_whitespace() {
            pending_space = !out.is_empty();
        } else {
            if pending_space {
                out.push(' ');
                pending_space = false;
            }
            out.push(ch);
        }
    }
    out
}

/// FNV-1a 64-bit hash, hex encoded. Stable across runs and platforms.
#[must_use]
pub fn content_hash(text: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}")
}

fn find_ci(haystack: &str, needle: &str) -> Option<usize> {
    let hay = haystack.as_bytes();
    let needle = needle.as_bytes();
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    (0..=hay.len() - needle.len())
        .find(|&start| hay[start..start + needle.len()].eq_ignore_ascii_case(needle))
}

fn extract_code(normalised: &str) -> Option<&str> {
    normalised
        .split(|c: char| !c.is_ascii_alphanumeric())
        .find(|token| is_code(token))
}

fn is_code(token: &str) -> bool {
    let bytes = token.as_bytes();
    bytes.len() == 10
        && bytes[..3].iter().all(u8::is_ascii_uppercase)
        && bytes[3..]
            .iter()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit())
}

fn classify(after_confirmed: &str) -> Option<Kind> {
    if find_ci(after_confirmed, "you have received").is_some() {
        Some(Kind::Received)
    } else if find_ci(after_confirmed, "you bought").is_some()
        && find_ci(after_confirmed, "airtime").is_some()
    {
        Some(Kind::Airtime)
    } else if find_ci(after_confirmed, "withdraw").is_some() {
        Some(Kind::Withdraw)
    } else if find_ci(after_confirmed, "paid to").is_some() {
        Some(Kind::BuyGoods)
    } else if find_ci(after_confirmed, "sent to").is_some() {
        if find_ci(after_confirmed, "for account").is_some() {
            Some(Kind::Paybill)
        } else {
            Some(Kind::Sent)
        }
    } else {
        None
    }
}

/// The first `Ksh` amount after the confirmation marker is always the
/// transaction amount; later amounts are the balance and the cost.
fn amount_after(normalised: &str, from: usize) -> Result<i64, ParseError> {
    let tail = &normalised[from..];
    let ksh = find_ci(tail, "ksh").ok_or(ParseError::MissingAmount(MoneyError::Empty))?;
    scan_marked_amount(&tail[ksh..])
}

fn balance(normalised: &str) -> Option<i64> {
    let tail = after_any(normalised, &["new m-pesa balance is", "m-pesa balance is"])?;
    let ksh = find_ci(tail, "ksh")?;
    scan_marked_amount(&tail[ksh..]).ok()
}

fn cost(normalised: &str) -> i64 {
    let Some(tail) = after_any(normalised, &["transaction cost"]) else {
        return 0;
    };
    let Some(ksh) = find_ci(tail, "ksh") else {
        return 0;
    };
    scan_marked_amount(&tail[ksh..]).unwrap_or(0)
}

fn after_any<'a>(haystack: &'a str, needles: &[&str]) -> Option<&'a str> {
    needles
        .iter()
        .filter_map(|needle| find_ci(haystack, needle).map(|at| (at, needle.len())))
        .min_by_key(|(at, _)| *at)
        .map(|(at, len)| &haystack[at + len..])
}

fn scan_marked_amount(marked: &str) -> Result<i64, ParseError> {
    let after_marker = marked
        .get("ksh".len()..)
        .ok_or(ParseError::MissingAmount(MoneyError::Empty))?;
    money::scan_amount_after_marker(after_marker).map_err(ParseError::MissingAmount)
}

fn counterparty(kind: Kind, after_confirmed: &str) -> Result<(String, Option<String>), ParseError> {
    match kind {
        Kind::Airtime => Ok(("Safaricom".to_owned(), None)),
        Kind::Received => {
            let rest =
                after_verb(after_confirmed, &[" from "]).ok_or(ParseError::MissingCounterparty)?;
            let end = find_ci(rest, " on ").ok_or(ParseError::MissingCounterparty)?;
            let name = sanitise_name(&rest[..end]);
            // Never keep a phone number: M-Pesa appends the msisdn to the name.
            let (name, _msisdn) = split_trailing_phone(&name);
            Ok((name, None))
        }
        Kind::Sent | Kind::BuyGoods | Kind::Paybill => {
            let rest = after_verb(after_confirmed, &[" paid to ", " sent to "])
                .ok_or(ParseError::MissingCounterparty)?;
            let mut reference = None;
            let raw_name = if kind == Kind::Paybill {
                let end = find_ci(rest, " for account ").ok_or(ParseError::MissingCounterparty)?;
                let account_tail = &rest[end + " for account ".len()..];
                let account_end = find_ci(account_tail, " on ").unwrap_or(account_tail.len());
                reference = Some(sanitise_name(&account_tail[..account_end]));
                &rest[..end]
            } else {
                let end = find_ci(rest, " on ").ok_or(ParseError::MissingCounterparty)?;
                &rest[..end]
            };
            let name = sanitise_name(raw_name);
            // Never keep a phone number: M-Pesa appends the msisdn to the name.
            let (name, _msisdn) = split_trailing_phone(&name);
            Ok((name, reference))
        }
        Kind::Withdraw => {
            let rest =
                after_verb(after_confirmed, &[" from "]).ok_or(ParseError::MissingCounterparty)?;
            let end =
                find_ci(rest, " new m-pesa balance").ok_or(ParseError::MissingCounterparty)?;
            let agent = sanitise_name(&rest[..end]);
            match agent.split_once(" - ") {
                // Real format: "from <agent number> - <agent name>".
                Some((number, name)) => Ok((sanitise_name(name), Some(sanitise_name(number)))),
                None => Ok((agent, None)),
            }
        }
    }
}

fn after_verb<'a>(haystack: &'a str, verbs: &[&str]) -> Option<&'a str> {
    verbs
        .iter()
        .filter_map(|verb| find_ci(haystack, verb).map(|at| (at, verb.len())))
        .min_by_key(|(at, _)| *at)
        .map(|(at, len)| &haystack[at + len..])
}

fn sanitise_name(raw: &str) -> String {
    raw.trim()
        .trim_end_matches(['.', ',', ';'])
        .trim_end()
        .to_owned()
}

/// Drops a trailing phone number from a counterparty, returning it separately.
fn split_trailing_phone(name: &str) -> (String, Option<String>) {
    let mut words: Vec<&str> = name.split_whitespace().collect();
    let Some(last) = words.last().copied() else {
        return (name.to_owned(), None);
    };
    let digits = last.strip_prefix('+').unwrap_or(last);
    let looks_like_msisdn =
        (9..=13).contains(&digits.len()) && digits.bytes().all(|b| b.is_ascii_digit());
    if looks_like_msisdn && words.len() > 1 {
        words.pop();
        (words.join(" "), Some(last.to_owned()))
    } else {
        (name.to_owned(), None)
    }
}

/// Scans for the first `d/m/yy(yy) at h:mm AM/PM` group in the text.
fn parse_timestamp(text: &str) -> Option<CivilTime> {
    let bytes = text.as_bytes();
    for start in 0..bytes.len() {
        if !bytes[start].is_ascii_digit() {
            continue;
        }
        if let Some(parsed) = try_parse_timestamp(&text[start..]) {
            return Some(parsed);
        }
    }
    None
}

fn try_parse_timestamp(text: &str) -> Option<CivilTime> {
    let bytes = text.as_bytes();
    let mut at = 0usize;
    let day = read_digits(bytes, &mut at, 2)?;
    if bytes.get(at) != Some(&b'/') {
        return None;
    }
    at += 1;
    let month = read_digits(bytes, &mut at, 2)?;
    if bytes.get(at) != Some(&b'/') {
        return None;
    }
    at += 1;
    let year = read_digits(bytes, &mut at, 4)?;
    let year = if year < 100 {
        2000 + year as i32
    } else {
        year as i32
    };

    let rest = text.get(at..)?;
    let rest = rest.trim_start();
    if rest.len() == text.len().saturating_sub(at) {
        // The date must be followed by whitespace before "at".
        return None;
    }
    let rest_bytes = rest.as_bytes();
    if !rest_bytes
        .get(..3)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case(b"at "))
    {
        return None;
    }

    let clock = rest.get(3..)?;
    let clock_bytes = clock.as_bytes();
    let mut at = 0usize;
    let hour12 = read_digits(clock_bytes, &mut at, 2)?;
    if clock_bytes.get(at) != Some(&b':') {
        return None;
    }
    at += 1;
    let minute = read_digits(clock_bytes, &mut at, 2)?;
    let meridiem = clock.get(at..)?.trim_start();
    let is_pm = if meridiem
        .as_bytes()
        .get(..2)
        .is_some_and(|p| p.eq_ignore_ascii_case(b"pm"))
    {
        true
    } else if meridiem
        .as_bytes()
        .get(..2)
        .is_some_and(|p| p.eq_ignore_ascii_case(b"am"))
    {
        false
    } else {
        return None;
    };

    CivilTime::from_12_hour(
        year,
        u8::try_from(month).ok()?,
        u8::try_from(day).ok()?,
        u8::try_from(hour12).ok()?,
        u8::try_from(minute).ok()?,
        is_pm,
    )
    .ok()
}

fn read_digits(bytes: &[u8], at: &mut usize, max: usize) -> Option<u32> {
    let start = *at;
    let mut value: u32 = 0;
    while *at < bytes.len() && bytes[*at].is_ascii_digit() && *at - start < max {
        value = value * 10 + u32::from(bytes[*at] - b'0');
        *at += 1;
    }
    (*at > start).then_some(value)
}

/// The M-Pesa transaction code in the text, if there is one.
///
/// Used as the idempotency key for the job queue, so it is available without a
/// full [`parse`].
#[must_use]
pub fn transaction_code(raw: &str) -> Option<String> {
    extract_code(&normalise(raw)).map(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SENT: &str = "SJA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0700000001 on 5/10/26 at 2:15 PM. New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh7.00. Amount you can transact within the day is 499,500.00.";

    #[test]
    fn normalise_collapses_whitespace() {
        assert_eq!(normalise("  a\n\n b\tc  "), "a b c");
        assert_eq!(normalise("Confirmed.You"), "Confirmed.You");
    }

    #[test]
    fn filters_unrelated_notifications() {
        assert!(!looks_like_mpesa(
            "Your M-PESA statement is ready. Dial *334#."
        ));
        assert!(!looks_like_mpesa("JOHN: see you at 5"));
        assert_eq!(
            parse("Your M-PESA statement for this month is ready. Dial *334# to view."),
            Err(ParseError::NotMpesa)
        );
        assert!(looks_like_mpesa(SENT));
    }

    #[test]
    fn parses_a_sent_message() {
        let tx = parse(SENT).expect("sent message parses");
        assert_eq!(tx.code, "SJA1B2C3D4");
        assert_eq!(tx.kind, Kind::Sent);
        assert_eq!(tx.amount_cents, 50_000);
        assert_eq!(tx.counterparty, "JOHN DOE");
        assert_eq!(tx.reference, None);
        assert_eq!(tx.balance_cents, Some(123_456));
        assert_eq!(tx.cost_cents, 700);
        assert_eq!(tx.occurred_at.to_string(), "2026-10-05 14:15 EAT");
    }

    #[test]
    fn missing_space_after_confirmed_is_tolerated() {
        let raw = "SJA2B3C4D5 Confirmed.You have received Ksh1,000.00 from JANE DOE 0700000002 on 5/10/26 at 9:00 AM  New M-PESA balance is Ksh2,234.56.";
        let tx = parse(raw).expect("received message parses");
        assert_eq!(tx.kind, Kind::Received);
        assert_eq!(tx.counterparty, "JANE DOE");
        assert_eq!(tx.amount_cents, 100_000);
        assert_eq!(tx.balance_cents, Some(223_456));
        assert_eq!(tx.cost_cents, 0);
    }

    #[test]
    fn withdraw_keeps_the_agent_number_as_reference() {
        let raw = "SJA6B7C8D9 Confirmed.on 5/10/26 at 4:00 PM Withdraw Ksh2,000.00 from 123456 - AGENT NAME New M-PESA balance is Ksh3,000.00. Transaction cost, Ksh28.00.";
        let tx = parse(raw).expect("withdraw message parses");
        assert_eq!(tx.kind, Kind::Withdraw);
        assert_eq!(tx.counterparty, "AGENT NAME");
        assert_eq!(tx.reference.as_deref(), Some("123456"));
        assert_eq!(tx.amount_cents, 200_000);
        assert_eq!(tx.cost_cents, 2_800);
        assert_eq!(tx.occurred_at.hour, 16);
    }

    #[test]
    fn paybill_keeps_the_account_as_reference() {
        let raw = "SJA3B4C5D6 Confirmed. Ksh1,200.00 sent to KPLC PREPAID for account 00000000 on 5/10/26 at 8:00 PM New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.";
        let tx = parse(raw).expect("paybill message parses");
        assert_eq!(tx.kind, Kind::Paybill);
        assert_eq!(tx.counterparty, "KPLC PREPAID");
        assert_eq!(tx.reference.as_deref(), Some("00000000"));
    }

    #[test]
    fn buygoods_drops_the_trailing_period() {
        let raw = "SJA4B5C6D7 Confirmed. Ksh350.00 paid to JAVA HOUSE. on 5/10/26 at 1:00 PM.New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00.";
        let tx = parse(raw).expect("buygoods message parses");
        assert_eq!(tx.kind, Kind::BuyGoods);
        assert_eq!(tx.counterparty, "JAVA HOUSE");
    }

    #[test]
    fn lowercase_confirmation_is_tolerated() {
        let raw = "SJA5B6C7D8 confirmed. You bought Ksh100.00 of airtime on 5/10/26 at 7:00 PM.New M-PESA balance is Ksh584.56. Transaction cost, Ksh0.00.";
        let tx = parse(raw).expect("airtime message parses");
        assert_eq!(tx.kind, Kind::Airtime);
        assert_eq!(tx.counterparty, "Safaricom");
    }

    #[test]
    fn malformed_mpesa_is_retryable_not_filtered() {
        // Right shape, unknown body: the queue must retry and then park it.
        let raw = "SJA9Z9Z9Z9 Confirmed. Something entirely new happened on 5/10/26 at 2:15 PM.";
        assert!(looks_like_mpesa(raw));
        assert_eq!(parse(raw), Err(ParseError::UnknownKind));
    }

    #[test]
    fn code_and_timestamp_are_required() {
        assert_eq!(
            parse("Confirmed. Ksh500.00 sent to X on 5/10/26 at 2:15 PM."),
            Err(ParseError::NotMpesa)
        );
        assert_eq!(
            parse("SJA1B2C3D4 Confirmed. Ksh500.00 sent to X."),
            Err(ParseError::MissingTimestamp)
        );
        assert_eq!(
            parse("SJA1B2C3D4 Ksh500.00 sent to X on 5/10/26 at 2:15 PM."),
            Err(ParseError::NotMpesa)
        );
    }

    #[test]
    fn phone_numbers_are_never_kept() {
        let tx = parse(SENT).expect("sent message parses");
        assert!(!tx.counterparty.chars().any(|c| c.is_ascii_digit()));
        assert_eq!(tx.reference, None);
    }

    #[test]
    fn transaction_code_is_available_without_a_full_parse() {
        assert_eq!(transaction_code(SENT), Some("SJA1B2C3D4".to_owned()));
        assert_eq!(transaction_code("Confirmed. Ksh1.00 sent."), None);
        assert_eq!(transaction_code("JOHN: see you at 5"), None);
    }

    #[test]
    fn hash_is_stable_and_input_sensitive() {
        assert_eq!(content_hash("abc"), content_hash("abc"));
        assert_eq!(content_hash("abc"), "e71fa2190541574b");
        assert_ne!(content_hash("abc"), content_hash("abd"));
    }

    #[test]
    fn timestamps_accept_four_digit_years_and_am() {
        let raw = "SJA1B2C3D4 Confirmed. Ksh1.00 sent to A B on 05/10/2026 at 9:05 AM New M-PESA balance is Ksh1.00.";
        let tx = parse(raw).expect("four digit year parses");
        assert_eq!(tx.occurred_at.year, 2026);
        assert_eq!(tx.occurred_at.hour, 9);
        assert_eq!(tx.occurred_at.minute, 5);
    }
}
