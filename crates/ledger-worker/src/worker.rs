//! The Apalis worker: one job type, bounded retries, never a silent drop.

use std::future::Future;
use std::time::Duration;

use apalis::prelude::*;
use apalis_sqlite::{SqlitePool, SqliteStorage};
use ledger_core::{parse, ParseError};

use crate::job::ParseTransaction;
use crate::store::{self, MAX_ATTEMPTS};

/// Runtime knobs for the worker.
#[derive(Debug, Clone, Copy, Default)]
pub struct WorkerSettings {
    /// Artificial processing delay per job, in milliseconds.
    ///
    /// Used by the persistence scenario to keep a job in flight while the
    /// process is killed. `0` in normal use.
    pub delay_ms: u64,
}

/// Handles one queued confirmation.
///
/// * A parsed message is inserted with `ON CONFLICT(code) DO NOTHING`, so a
///   duplicate delivery writes nothing.
/// * A message that is not M-Pesa is acknowledged and stored nowhere.
/// * A message that looks like M-Pesa but does not parse is recorded in the
///   `unparsed` table and returned as an error, which Apalis retries up to
///   [`MAX_ATTEMPTS`] times before giving up. Since the row is already parked,
///   the message is never lost and never retried forever.
pub async fn handle(
    job: ParseTransaction,
    pool: Data<SqlitePool>,
    settings: Data<WorkerSettings>,
) -> Result<(), BoxDynError> {
    if settings.delay_ms > 0 {
        tokio::time::sleep(Duration::from_millis(settings.delay_ms)).await;
    }

    match parse(&job.raw_text) {
        Ok(tx) => {
            store::insert_transaction(&pool, &tx, &job.raw_text).await?;
            Ok(())
        }
        Err(ParseError::NotMpesa) => Ok(()),
        Err(error) => {
            store::record_unparsed(&pool, &job.raw_text, &error.to_string()).await?;
            Err(Box::new(error))
        }
    }
}

/// Runs the worker until `shutdown` completes, then drains in-flight jobs.
pub async fn run_worker<F>(
    pool: SqlitePool,
    settings: WorkerSettings,
    shutdown: F,
) -> Result<(), BoxDynError>
where
    F: Future<Output = ()> + Send + 'static,
{
    // Poll promptly: a confirmation should reach the widget in well under a
    // second, not on the backend's slow default cadence.
    let backend = SqliteStorage::<ParseTransaction>::new(&pool)
        .poll_with_interval(Duration::from_millis(250));
    let worker = WorkerBuilder::new(worker_instance_name())
        .backend(backend)
        .data(pool.clone())
        .data(settings)
        // Apalis asks for concurrency before retries so a retry does not take
        // an extra concurrency slot.
        .concurrency(2)
        .retry(RetryPolicy::retries(MAX_ATTEMPTS - 1))
        .build(handle);

    worker
        .run_until(async move {
            shutdown.await;
            Ok::<(), WorkerError>(())
        })
        .await?;
    Ok(())
}

/// A worker name unique to this process start.
///
/// Apalis registers every worker in the `Workers` table by name, and refuses to
/// start a second worker with a name that is already registered. Restarting the
/// app must not collide with the previous run's registration, so the name
/// carries the process id and a monotonic timestamp.
fn worker_instance_name() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static SEQUENCE: AtomicU64 = AtomicU64::new(0);

    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_nanos());
    let sequence = SEQUENCE.fetch_add(1, Ordering::Relaxed);
    format!("ledger-worker-{}-{nanos}-{sequence}", std::process::id())
}
