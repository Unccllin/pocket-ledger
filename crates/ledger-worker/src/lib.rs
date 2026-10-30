//! Apalis worker, SQLite ledger and CLI for **pocket-ledger**.
//!
//! The queue is SQLite-backed, so a job written here survives the process being
//! closed, killed or the phone rebooting. Both the CLI and (later) the Android
//! JNI bridge push through [`enqueue`], which uses the M-Pesa transaction code
//! as the Apalis idempotency key.
//!
//! ```no_run
//! # async fn demo() -> Result<(), Box<dyn std::error::Error>> {
//! use ledger_worker::{db_path, enqueue, open, stats};
//!
//! let pool = open(&db_path(std::path::Path::new("target/dev"))).await?;
//! enqueue(&pool, "SJA1B2C3D4 Confirmed. Ksh1.00 sent to A B on 5/10/26 at 2:15 PM. \
//!                 New M-PESA balance is Ksh10.00. Transaction cost, Ksh0.00.", 0).await?;
//! let stats = stats(&pool).await?;
//! println!("{} queued", stats.queued_jobs);
//! # Ok(())
//! # }
//! ```

#![forbid(unsafe_code)]

pub mod app;
pub mod job;
pub mod store;
pub mod worker;

pub use apalis_sqlite::SqlitePool;
pub use app::{started_root, LedgerApp, LedgerConfig};
pub use job::ParseTransaction;
pub use store::{
    db_path, eat_day_bounds, enqueue, format_eat, open, queue_name, recover_orphans, stats,
    EnqueueOutcome, LedgerRow, Stats, StoreError, MAX_ATTEMPTS,
};
pub use worker::{run_worker, WorkerSettings};
