//! The single Apalis job type this project uses.

use serde::{Deserialize, Serialize};

/// One M-Pesa confirmation waiting to be parsed and stored.
///
/// `raw_text` is stored verbatim so a future parser version can re-process it,
/// and `received_at` records when the notification arrived on the phone (Unix
/// seconds), independent of the timestamp printed inside the message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ParseTransaction {
    /// Raw notification text exactly as the Android listener saw it.
    pub raw_text: String,
    /// Unix seconds at which the notification arrived on the device.
    pub received_at: i64,
}

impl ParseTransaction {
    /// Builds a job from a raw message and its arrival time.
    #[must_use]
    pub fn new(raw_text: impl Into<String>, received_at: i64) -> Self {
        Self {
            raw_text: raw_text.into(),
            received_at,
        }
    }

    /// The M-Pesa transaction code, the natural idempotency key.
    #[must_use]
    pub fn code(&self) -> Option<String> {
        ledger_core::transaction_code(&self.raw_text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_is_the_idempotency_key() {
        let job = ParseTransaction::new(
            "SJA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0700000001 on 5/10/26 at 2:15 PM.",
            1_791_200_000,
        );
        assert_eq!(job.code().as_deref(), Some("SJA1B2C3D4"));
        assert_eq!(ParseTransaction::new("hello", 0).code(), None);
    }
}
