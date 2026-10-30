//! Privacy: what parts of a confirmation message may be kept.
//!
//! The ledger stores the parsed fields, and keeps a copy of the original
//! message only so the details sheet can show it. Phone numbers (a 10-digit
//! Kenyan msisdn, or the same number with a 254 prefix) must never be stored,
//! even inside that copy, so they are masked here before the insert.
//!
//! Short digit runs (amounts, balances, dates, times, six-digit agent numbers)
//! are left alone: they are already stored as typed fields and are what makes
//! the original readable.

/// Masks digit runs long enough to be a phone number.
///
/// A run is masked when it has at least nine digits, which covers `0700000001`,
/// `254712345678` and `+254 712 345 678` written without spaces. Every masked
/// position becomes `*`, keeping the message's shape.
#[must_use]
pub fn redact_phone_numbers(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();

    let flush = |out: &mut String, run: &mut String| {
        if run.len() >= 9 {
            out.extend(std::iter::repeat_n('*', run.len()));
        } else {
            out.push_str(run);
        }
        run.clear();
    };

    for ch in text.chars() {
        if ch.is_ascii_digit() {
            run.push(ch);
        } else {
            flush(&mut out, &mut run);
            out.push(ch);
        }
    }
    flush(&mut out, &mut run);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn masks_phone_numbers_but_keeps_the_receipt_readable() {
        let raw = "SJA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0700000001 \
                   on 5/10/26 at 2:15 PM. New M-PESA balance is Ksh1,234.56. \
                   Transaction cost, Ksh7.00.";
        let redacted = redact_phone_numbers(raw);
        assert!(!redacted.contains("0700000001"));
        assert!(redacted.contains("**********"));
        // Everything a reader needs is untouched.
        assert!(redacted.contains("SJA1B2C3D4"));
        assert!(redacted.contains("Ksh500.00"));
        assert!(redacted.contains("5/10/26"));
        assert!(redacted.contains("2:15 PM"));
        assert!(redacted.contains("Ksh1,234.56"));
    }

    #[test]
    fn masks_a_254_prefixed_sender() {
        let raw = "Confirmed. You have received Ksh1,000.00 from JANE DOE 254712345678.";
        let redacted = redact_phone_numbers(raw);
        assert!(!redacted.contains("254712345678"));
        assert!(redacted.contains("************"));
    }

    #[test]
    fn leaves_agent_numbers_and_short_runs_alone() {
        let raw = "Withdraw Ksh2,000.00 from 123456 - AGENT NAME on 30/10/26 at 4:00 PM.";
        assert_eq!(redact_phone_numbers(raw), raw);
    }
}
