//! Pure Rust domain, parser and categoriser for **pocket-ledger**.
//!
//! This crate knows nothing about Android, Rivium, Apalis or SQLite. It turns
//! one M-Pesa confirmation message into one [`Transaction`] (or a typed
//! [`ParseError`]) and assigns a spending [`Category`].
//!
//! ```
//! use ledger_core::{categorise, parse, Category, Kind};
//!
//! let raw = "SJA4B5C6D7 Confirmed. Ksh350.00 paid to JAVA HOUSE. on 5/10/26 at 1:00 PM. \
//!            New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00.";
//! let tx = parse(raw)?;
//! assert_eq!(tx.kind, Kind::BuyGoods);
//! assert_eq!(tx.counterparty, "JAVA HOUSE");
//! assert_eq!(tx.amount_cents, 35_000);
//! assert_eq!(categorise(&tx), Category::Food);
//! # Ok::<(), ledger_core::ParseError>(())
//! ```

#![forbid(unsafe_code)]

pub mod categorise;
pub mod model;
pub mod money;
pub mod parse;
pub mod redact;
pub mod time;

pub use categorise::categorise;
pub use model::{Category, Kind, Transaction};
pub use parse::{content_hash, looks_like_mpesa, normalise, parse, transaction_code, ParseError};
pub use redact::redact_phone_numbers;
pub use time::CivilTime;
