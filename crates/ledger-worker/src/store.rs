//! SQLite storage: the ledger, the unparsed review queue, queue statistics and
//! the enqueue path shared by the CLI and (later) the Android JNI bridge.

use std::fmt;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use apalis::prelude::{TaskBuilder, TaskSink};
use apalis_sqlite::{SqlitePool, SqlitePoolOptions, SqliteStorage};
use ledger_core::{categorise, looks_like_mpesa, CivilTime, Transaction};
use serde::Serialize;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode};
use sqlx::Row;

use crate::job::ParseTransaction;

/// Number of times a job may run before Apalis stops retrying it.
///
/// Failures are also written to the `unparsed` table on every attempt, so a
/// message that exhausts its retries is parked for review, never dropped.
pub const MAX_ATTEMPTS: usize = 3;

/// The Apalis queue name. It defaults to the job type's full name, so every
/// process that uses [`queue_name`] talks to the same queue.
#[must_use]
pub fn queue_name() -> &'static str {
    std::any::type_name::<ParseTransaction>()
}

/// Anything that can go wrong while reading or writing the ledger.
#[derive(Debug)]
pub enum StoreError {
    /// SQLite rejected a statement.
    Sqlx(sqlx::Error),
    /// A database file could not be created.
    Io(std::io::Error),
    /// The Apalis migrations could not be applied.
    Migrations(String),
    /// Apalis rejected a pushed job.
    Push(String),
}

impl fmt::Display for StoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Sqlx(error) => write!(f, "sqlite: {error}"),
            Self::Io(error) => write!(f, "io: {error}"),
            Self::Migrations(error) => write!(f, "apalis migrations: {error}"),
            Self::Push(error) => write!(f, "cannot enqueue job: {error}"),
        }
    }
}

impl std::error::Error for StoreError {}

impl From<sqlx::Error> for StoreError {
    fn from(error: sqlx::Error) -> Self {
        Self::Sqlx(error)
    }
}

/// What happened when a message was offered to the queue.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "outcome", rename_all = "snake_case")]
pub enum EnqueueOutcome {
    /// A job was written to the SQLite queue.
    Enqueued {
        /// The transaction code used as the idempotency key.
        code: String,
    },
    /// Not an M-Pesa confirmation: personal traffic, never stored.
    Filtered,
}

/// One stored ledger row, as shown in `stats` and on the widget.
#[derive(Debug, Clone, Serialize)]
pub struct LedgerRow {
    /// M-Pesa transaction code.
    pub code: String,
    /// `sent`, `received`, `paybill`, `buygoods`, `airtime` or `withdraw`.
    pub kind: String,
    /// Spending category tag.
    pub category: String,
    /// Amount in integer cents.
    pub amount_cents: i64,
    /// Counterparty name (never a phone number).
    pub counterparty: String,
    /// Transaction time as Unix seconds.
    pub occurred_at: i64,
}

/// Everything the widget, the result notification and the CLI need in one call.
#[derive(Debug, Clone, Serialize)]
pub struct Stats {
    /// Total ledger rows.
    pub ledger_rows: i64,
    /// Money spent today (EAT), amount plus cost, excluding money received.
    pub today_spend_cents: i64,
    /// Money received today (EAT).
    pub today_income_cents: i64,
    /// Jobs waiting to be processed.
    ///
    /// Apalis reserves work as `Queued` (locked by a worker) and leaves
    /// untouched work as `Pending`; from the widget's point of view both are
    /// still waiting, so both are counted here.
    pub queued_jobs: i64,
    /// Jobs currently being processed.
    pub running_jobs: i64,
    /// Jobs that exhausted their retries.
    pub failed_jobs: i64,
    /// Messages parked for review in the `unparsed` table.
    pub unparsed: i64,
    /// Most recent ledger rows.
    pub latest: Vec<LedgerRow>,
}

/// Opens (creating if needed) the ledger database and applies all migrations.
pub async fn open(db_path: &Path) -> Result<SqlitePool, StoreError> {
    if let Some(parent) = db_path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(StoreError::Io)?;
        }
    }
    let options = SqliteConnectOptions::new()
        .filename(db_path)
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(5));
    let pool = SqlitePoolOptions::new()
        .max_connections(4)
        .connect_with(options)
        .await?;
    // `setup` is defined on `SqliteStorage<()>` in apalis-sqlite 1.0.0: it only
    // runs the queue migrations, so the job type parameter is irrelevant here.
    SqliteStorage::<()>::setup(&pool)
        .await
        .map_err(|error| StoreError::Migrations(error.to_string()))?;
    init_schema(&pool).await?;
    Ok(pool)
}

/// Convenience wrapper: `root/ledger.db`.
#[must_use]
pub fn db_path(root: &Path) -> PathBuf {
    root.join("ledger.db")
}

/// Creates this project's tables. Safe to run on every start.
pub async fn init_schema(pool: &SqlitePool) -> Result<(), StoreError> {
    for statement in [
        "CREATE TABLE IF NOT EXISTS ledger (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL UNIQUE,
            kind TEXT NOT NULL,
            category TEXT NOT NULL,
            amount_cents INTEGER NOT NULL,
            counterparty TEXT NOT NULL,
            reference TEXT,
            balance_cents INTEGER,
            cost_cents INTEGER NOT NULL,
            occurred_at INTEGER NOT NULL,
            raw_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )",
        "CREATE INDEX IF NOT EXISTS idx_ledger_occurred_at ON ledger(occurred_at)",
        "CREATE TABLE IF NOT EXISTS unparsed (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            raw_text TEXT NOT NULL,
            raw_hash TEXT NOT NULL UNIQUE,
            attempts INTEGER NOT NULL,
            last_error TEXT NOT NULL,
            first_seen INTEGER NOT NULL,
            last_seen INTEGER NOT NULL
        )",
    ] {
        sqlx::query(statement).execute(pool).await?;
    }
    Ok(())
}

/// Offers one raw notification to the queue.
///
/// Non-M-Pesa notifications are filtered here, before anything is written.
/// The transaction code is the Apalis idempotency key, so re-enqueueing the
/// same confirmation (listener re-fire, retry, restart) never doubles the work.
pub async fn enqueue(
    pool: &SqlitePool,
    raw_text: &str,
    received_at: i64,
) -> Result<EnqueueOutcome, StoreError> {
    if !looks_like_mpesa(raw_text) {
        return Ok(EnqueueOutcome::Filtered);
    }
    let Some(code) = ledger_core::transaction_code(raw_text) else {
        return Ok(EnqueueOutcome::Filtered);
    };

    let task = TaskBuilder::new(ParseTransaction::new(raw_text, received_at))
        .idempotency_key(&code)
        .max_attempts(MAX_ATTEMPTS)
        .build();
    let mut storage = SqliteStorage::<ParseTransaction>::new(pool);
    storage
        .push_task(task)
        .await
        .map_err(|error| StoreError::Push(error.to_string()))?;
    Ok(EnqueueOutcome::Enqueued { code })
}

/// Inserts a parsed transaction.
///
/// Returns `true` when a new row was written and `false` when the code was
/// already known, which is how idempotency is enforced at the ledger level.
pub async fn insert_transaction(pool: &SqlitePool, tx: &Transaction) -> Result<bool, StoreError> {
    let category = categorise(tx);
    let result = sqlx::query(
        "INSERT INTO ledger
            (code, kind, category, amount_cents, counterparty, reference,
             balance_cents, cost_cents, occurred_at, raw_hash, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
         ON CONFLICT(code) DO NOTHING",
    )
    .bind(&tx.code)
    .bind(tx.kind.as_str())
    .bind(category.as_str())
    .bind(tx.amount_cents)
    .bind(&tx.counterparty)
    .bind(tx.reference.as_deref())
    .bind(tx.balance_cents)
    .bind(tx.cost_cents)
    .bind(tx.occurred_at.unix_seconds())
    .bind(&tx.raw_hash)
    .bind(now_unix())
    .execute(pool)
    .await?;
    Ok(result.rows_affected() == 1)
}

/// Records a message that looks like M-Pesa but could not be parsed.
///
/// Upserts on the content hash, so retries increment `attempts` on one row
/// instead of creating duplicates.
pub async fn record_unparsed(
    pool: &SqlitePool,
    raw_text: &str,
    last_error: &str,
) -> Result<(), StoreError> {
    let now = now_unix();
    sqlx::query(
        "INSERT INTO unparsed (raw_text, raw_hash, attempts, last_error, first_seen, last_seen)
         VALUES (?1, ?2, 1, ?3, ?4, ?4)
         ON CONFLICT(raw_hash) DO UPDATE SET
            attempts = attempts + 1,
            last_error = excluded.last_error,
            last_seen = excluded.last_seen",
    )
    .bind(raw_text)
    .bind(ledger_core::content_hash(raw_text))
    .bind(last_error)
    .bind(now)
    .execute(pool)
    .await?;
    Ok(())
}

/// Re-queues jobs that a previous process took but never finished.
///
/// The Apalis SQLite backend only re-queues orphaned work through its `Workers`
/// heartbeat table, which this backend never populates, so a `kill -9` would
/// otherwise leave the job `Running` forever. This project assumes one worker
/// process per device at a time, so at startup every unfinished job for our
/// queue belongs to a dead process and is safe to re-queue.
pub async fn recover_orphans(pool: &SqlitePool) -> Result<u64, StoreError> {
    let result = sqlx::query(
        "UPDATE Jobs
         SET status = 'Pending',
             done_at = NULL,
             lock_by = NULL,
             lock_at = NULL,
             attempts = attempts + 1,
             last_result = ?1
         WHERE job_type = ?2 AND status IN ('Running', 'Queued')",
    )
    .bind(r#"{"Err":"re-enqueued at startup: previous worker stopped without finishing"}"#)
    .bind(queue_name())
    .execute(pool)
    .await?;
    Ok(result.rows_affected())
}

/// Current ledger and queue state.
pub async fn stats(pool: &SqlitePool) -> Result<Stats, StoreError> {
    let ledger_rows: i64 = sqlx::query("SELECT COUNT(*) FROM ledger")
        .fetch_one(pool)
        .await?
        .get(0);

    let (today_start, today_end) = eat_day_bounds(now_unix());
    let today_spend_cents: i64 = sqlx::query(
        "SELECT COALESCE(SUM(amount_cents + cost_cents), 0) FROM ledger
         WHERE occurred_at >= ?1 AND occurred_at < ?2 AND kind != 'received'",
    )
    .bind(today_start)
    .bind(today_end)
    .fetch_one(pool)
    .await?
    .get(0);
    let today_income_cents: i64 = sqlx::query(
        "SELECT COALESCE(SUM(amount_cents), 0) FROM ledger
         WHERE occurred_at >= ?1 AND occurred_at < ?2 AND kind = 'received'",
    )
    .bind(today_start)
    .bind(today_end)
    .fetch_one(pool)
    .await?
    .get(0);

    let queued_jobs = count_jobs(
        pool,
        "SELECT COUNT(*) FROM Jobs WHERE job_type = ?1 AND status IN ('Pending', 'Queued')",
    )
    .await?;
    let running_jobs = count_jobs(
        pool,
        "SELECT COUNT(*) FROM Jobs WHERE job_type = ?1 AND status = 'Running'",
    )
    .await?;
    let failed_jobs = count_jobs(
        pool,
        "SELECT COUNT(*) FROM Jobs WHERE job_type = ?1 AND status IN ('Failed', 'Killed')",
    )
    .await?;
    let unparsed: i64 = sqlx::query("SELECT COUNT(*) FROM unparsed")
        .fetch_one(pool)
        .await?
        .get(0);

    let latest = sqlx::query(
        "SELECT code, kind, category, amount_cents, counterparty, occurred_at
         FROM ledger ORDER BY occurred_at DESC, id DESC LIMIT 5",
    )
    .fetch_all(pool)
    .await?
    .into_iter()
    .map(|row| LedgerRow {
        code: row.get::<String, _>("code"),
        kind: row.get::<String, _>("kind"),
        category: row.get::<String, _>("category"),
        amount_cents: row.get::<i64, _>("amount_cents"),
        counterparty: row.get::<String, _>("counterparty"),
        occurred_at: row.get::<i64, _>("occurred_at"),
    })
    .collect();

    Ok(Stats {
        ledger_rows,
        today_spend_cents,
        today_income_cents,
        queued_jobs,
        running_jobs,
        failed_jobs,
        unparsed,
        latest,
    })
}

/// The `[start, end)` Unix-second window covering "today" in East Africa Time.
#[must_use]
pub fn eat_day_bounds(now_unix: i64) -> (i64, i64) {
    let offset = ledger_core::time::EAT_OFFSET_SECONDS;
    let start = (now_unix + offset).div_euclid(86_400) * 86_400 - offset;
    (start, start + 86_400)
}

/// Formats a stored Unix timestamp in EAT for logs and the CLI.
#[must_use]
pub fn format_eat(unix_seconds: i64) -> String {
    CivilTime::from_unix_seconds(unix_seconds)
        .map_or_else(|| unix_seconds.to_string(), |civil| civil.to_string())
}

async fn count_jobs(pool: &SqlitePool, sql: &'static str) -> Result<i64, StoreError> {
    Ok(sqlx::query(sql)
        .bind(queue_name())
        .fetch_one(pool)
        .await?
        .get(0))
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| {
            i64::try_from(elapsed.as_secs()).unwrap_or(i64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn today_is_bounded_by_eat_midnight() {
        // 2026-10-05 14:15 EAT
        let now = 1_791_198_900;
        let (start, end) = eat_day_bounds(now);
        assert_eq!(end - start, 86_400);
        assert!(start <= now && now < end);
        // EAT midnight is 21:00 UTC the previous day.
        assert_eq!(start % 86_400, 86_400 - 10_800);
        assert_eq!(now - start, 14 * 3_600 + 15 * 60);
    }

    #[test]
    fn eat_day_bounds_are_stable_at_midnight() {
        let (start, _) = eat_day_bounds(1_791_198_900);
        assert_eq!(eat_day_bounds(start).0, start);
        assert_eq!(eat_day_bounds(start - 1).0, start - 86_400);
    }

    #[test]
    fn queue_name_is_the_job_type_name() {
        assert!(queue_name().ends_with("ParseTransaction"));
    }

    #[test]
    fn format_eat_is_iso_with_offset_note() {
        let text = format_eat(1_791_198_900);
        assert_eq!(text, "2026-10-05 14:15 EAT");
    }
}
