//! End-to-end persistence tests: a real SQLite file, a real Apalis worker, and
//! the guarantees the README claims.
//!
//! Each test owns a temporary directory, so the queue name (which is shared by
//! every process) never causes cross-test interference.

use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ledger_worker::{
    db_path, enqueue, open, recover_orphans, run_worker, stats, EnqueueOutcome, SqlitePool,
    WorkerSettings,
};
use sqlx::Row;

const FIXTURES: &str = include_str!("../../ledger-core/tests/fixtures/mpesa_messages.txt");

const SENT: &str = "SJA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0700000001 on 5/10/26 at 2:15 PM. New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh7.00.";
const PAYBILL: &str = "SJA3B4C5D6 Confirmed. Ksh1,200.00 sent to KPLC PREPAID for account 00000000 on 5/10/26 at 8:00 PM New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.";
const MALFORMED: &str =
    "SJA9Z9Z9Z9 Confirmed. Something entirely new happened on 5/10/26 at 2:15 PM.";
const PERSONAL: &str = "Your M-PESA statement for this month is ready. Dial *334# to view.";

struct TempDir {
    path: PathBuf,
}

impl TempDir {
    fn new(tag: &str) -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock after epoch")
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "pocket-ledger-{tag}-{}-{nanos}",
            std::process::id()
        ));
        std::fs::create_dir_all(&path).expect("temp dir");
        Self { path }
    }

    fn db(&self) -> PathBuf {
        db_path(&self.path)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

/// Runs the worker until `done` holds for three consecutive checks.
///
/// The predicate is the test's own terminal condition (rows stored, queue
/// empty, nothing failed), so the helper can never declare success before any
/// work has started, and it still stops promptly once the work is finished.
async fn drain_until(
    pool: &SqlitePool,
    done: impl Fn(&ledger_worker::Stats) -> bool + Send + 'static,
) {
    let worker_pool = pool.clone();
    let probe_pool = pool.clone();
    let shutdown = async move {
        let mut confirmations = 0;
        loop {
            tokio::time::sleep(Duration::from_millis(100)).await;
            let current = stats(&probe_pool).await.expect("stats while draining");
            if done(&current) {
                confirmations += 1;
                if confirmations >= 3 {
                    break;
                }
            } else {
                confirmations = 0;
            }
        }
    };

    let worker = tokio::spawn(run_worker(
        worker_pool,
        WorkerSettings { delay_ms: 0 },
        shutdown,
    ));
    tokio::time::timeout(Duration::from_secs(30), worker)
        .await
        .expect("worker drained within 30s")
        .expect("worker task joined")
        .expect("worker stopped without error");
}

/// Terminal condition for a run that must store exactly `rows` ledger rows.
fn settled(rows: i64) -> impl Fn(&ledger_worker::Stats) -> bool + Send + 'static {
    move |stats: &ledger_worker::Stats| {
        stats.ledger_rows == rows && stats.queued_jobs == 0 && stats.running_jobs == 0
    }
}

fn fixture_messages() -> Vec<String> {
    let mut messages = Vec::new();
    let mut current: Vec<&str> = Vec::new();
    for line in FIXTURES.lines() {
        if line.trim() == "---" {
            let block = current.join("\n").trim().to_owned();
            if !block.is_empty() {
                messages.push(block);
            }
            current.clear();
        } else if !line.trim_start().starts_with('#') {
            current.push(line);
        }
    }
    let block = current.join("\n").trim().to_owned();
    if !block.is_empty() {
        messages.push(block);
    }
    messages
}

async fn open_temp(tag: &str) -> (TempDir, SqlitePool) {
    let dir = TempDir::new(tag);
    let pool = open(&dir.db()).await.expect("database opens");
    (dir, pool)
}

#[tokio::test]
async fn personal_notifications_never_reach_the_queue() {
    let (_dir, pool) = open_temp("filter").await;
    assert_eq!(
        enqueue(&pool, PERSONAL, 0).await.expect("enqueue"),
        EnqueueOutcome::Filtered
    );
    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.queued_jobs, 0);
    assert_eq!(current.ledger_rows, 0);
}

#[tokio::test]
async fn the_same_confirmation_twice_produces_one_job_and_one_row() {
    let (_dir, pool) = open_temp("dedup").await;
    for _ in 0..2 {
        let outcome = enqueue(&pool, SENT, 0).await.expect("enqueue");
        assert_eq!(
            outcome,
            EnqueueOutcome::Enqueued {
                code: "SJA1B2C3D4".to_owned()
            }
        );
    }
    // The Apalis idempotency index kept exactly one pending job.
    assert_eq!(stats(&pool).await.expect("stats").queued_jobs, 1);

    drain_until(&pool, settled(1)).await;
    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.ledger_rows, 1);
    assert_eq!(current.queued_jobs, 0);
    assert_eq!(current.failed_jobs, 0);
    assert_eq!(current.unparsed, 0);
    assert_eq!(current.latest[0].code, "SJA1B2C3D4");
    assert_eq!(current.latest[0].kind, "sent");
    assert_eq!(current.latest[0].category, "transfer");
}

#[tokio::test]
async fn every_known_fixture_is_stored_exactly_once() {
    let (_dir, pool) = open_temp("fixtures").await;
    let mut enqueued = 0;
    for message in fixture_messages() {
        if let EnqueueOutcome::Enqueued { .. } = enqueue(&pool, &message, 0).await.expect("enqueue")
        {
            enqueued += 1;
        }
    }
    assert_eq!(enqueued, 6, "six M-Pesa fixtures, one non-M-Pesa block");

    drain_until(&pool, settled(6)).await;
    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.ledger_rows, 6);
    assert_eq!(current.queued_jobs, 0);
    assert_eq!(current.failed_jobs, 0);
    assert_eq!(current.unparsed, 0);

    // Successful jobs are acknowledged, not left reserved forever.
    let statuses: Vec<String> =
        sqlx::query_scalar("SELECT DISTINCT status FROM Jobs WHERE job_type = ?1 ORDER BY status")
            .bind(ledger_worker::queue_name())
            .fetch_all(&pool)
            .await
            .expect("job statuses");
    assert_eq!(statuses, vec!["Done".to_owned()]);

    // Re-ingesting everything must not add rows.
    for message in fixture_messages() {
        let _ = enqueue(&pool, &message, 0).await.expect("re-enqueue");
    }
    drain_until(&pool, settled(6)).await;
    assert_eq!(stats(&pool).await.expect("stats").ledger_rows, 6);
}

#[tokio::test]
async fn malformed_mpesa_is_parked_after_bounded_retries() {
    let (_dir, pool) = open_temp("unparsed").await;
    assert!(matches!(
        enqueue(&pool, MALFORMED, 0).await.expect("enqueue"),
        EnqueueOutcome::Enqueued { .. }
    ));

    drain_until(&pool, |stats: &ledger_worker::Stats| {
        stats.unparsed == 1 && stats.failed_jobs == 1 && stats.queued_jobs == 0
    })
    .await;
    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.ledger_rows, 0, "nothing invented");
    assert_eq!(current.unparsed, 1, "parked for review exactly once");
    assert_eq!(current.failed_jobs, 1, "retries exhausted, job visible");
    assert_eq!(current.queued_jobs, 0, "not retried forever");

    let attempts: i64 = sqlx::query("SELECT attempts FROM unparsed LIMIT 1")
        .fetch_one(&pool)
        .await
        .expect("unparsed row")
        .get(0);
    assert!(
        (1..=3).contains(&attempts),
        "attempts must be bounded, got {attempts}"
    );
}

#[tokio::test]
async fn jobs_orphaned_by_a_killed_process_are_recovered() {
    let (_dir, pool) = open_temp("orphans").await;
    enqueue(&pool, PAYBILL, 0).await.expect("enqueue");

    // Simulate `kill -9` after Apalis locked the job: it stays Running with a
    // lock owner that will never come back. Foreign keys are enforced, so the
    // dead worker has to exist in the Workers table first.
    sqlx::query(
        "INSERT OR IGNORE INTO Workers (id, worker_type, storage_name, last_seen)
         VALUES ('dead-worker', ?1, 'test', 0)",
    )
    .bind(ledger_worker::queue_name())
    .execute(&pool)
    .await
    .expect("register dead worker");
    sqlx::query(
        "UPDATE Jobs SET status = 'Running', lock_by = 'dead-worker',
         lock_at = strftime('%s', 'now') WHERE job_type = ?1",
    )
    .bind(ledger_worker::queue_name())
    .execute(&pool)
    .await
    .expect("simulate orphan");

    assert_eq!(recover_orphans(&pool).await.expect("recover"), 1);
    let after_recovery = stats(&pool).await.expect("stats");
    assert_eq!(after_recovery.queued_jobs, 1);
    assert_eq!(after_recovery.running_jobs, 0);

    drain_until(&pool, settled(1)).await;
    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.ledger_rows, 1);
    assert_eq!(current.latest[0].code, "SJA3B4C5D6");
    assert_eq!(current.latest[0].category, "utilities");
}

#[tokio::test]
async fn a_redelivery_after_completion_adds_no_rows() {
    let temp = TempDir::new("redelivery");
    let first = open(&temp.db()).await.expect("first open");
    enqueue(&first, SENT, 0).await.expect("enqueue");
    drain_until(&first, settled(1)).await;
    assert_eq!(stats(&first).await.expect("stats").ledger_rows, 1);

    // Reopen the same file, as a restarted app would, and deliver it again.
    let second = open(&temp.db()).await.expect("second open");
    enqueue(&second, SENT, 0).await.expect("re-enqueue");
    drain_until(&second, settled(1)).await;
    assert_eq!(stats(&second).await.expect("stats").ledger_rows, 1);
}

#[tokio::test]
async fn database_file_is_created_under_the_root() {
    let temp = TempDir::new("paths");
    assert!(!temp.db().exists());
    let _pool = open(&temp.db()).await.expect("open");
    assert!(Path::new(&temp.db()).exists());
}

// ---------------------------------------------------------------------------
// What the 0.3 UI reads: aggregates, row details, and the review flow.
// ---------------------------------------------------------------------------

fn now_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after epoch")
        .as_secs() as i64
}

/// One ledger row for aggregation tests that do not want to depend on the
/// calendar.
struct FixtureRow<'a> {
    code: &'a str,
    category: &'a str,
    kind: &'a str,
    amount_cents: i64,
    cost_cents: i64,
    occurred_at: i64,
    balance_cents: Option<i64>,
}

/// Inserts a row straight into the ledger with a chosen timestamp.
async fn insert_fixture_row(pool: &SqlitePool, row: FixtureRow<'_>) {
    let FixtureRow {
        code,
        category,
        kind,
        amount_cents,
        cost_cents,
        occurred_at,
        balance_cents,
    } = row;
    sqlx::query(
        "INSERT INTO ledger
            (code, kind, category, amount_cents, counterparty, reference,
             balance_cents, cost_cents, occurred_at, raw_hash, created_at, raw_text)
         VALUES (?1, ?2, ?3, ?4, ?5, NULL, ?6, ?7, ?8, ?9, ?8, NULL)",
    )
    .bind(code)
    .bind(kind)
    .bind(category)
    .bind(amount_cents)
    .bind("TEST SHOP")
    .bind(balance_cents)
    .bind(cost_cents)
    .bind(occurred_at)
    .bind(format!("hash-{code}"))
    .execute(pool)
    .await
    .expect("insert ledger row");
}

#[tokio::test]
async fn ledger_keeps_a_masked_original_message_and_the_balance() {
    let (_dir, pool) = open_temp("row-details").await;
    let tx = ledger_core::parse(SENT).expect("fixture parses");
    ledger_worker::store::insert_transaction(&pool, &tx, SENT)
        .await
        .expect("insert");

    let row = stats(&pool).await.expect("stats").latest.remove(0);
    assert_eq!(row.balance_cents, Some(123_456));
    let raw = row.raw_text.expect("original message kept");
    assert!(
        !raw.contains("0700000001"),
        "phone number must not be stored"
    );
    assert!(raw.contains("**********"));
    assert!(raw.contains("SJA1B2C3D4"), "the reference stays readable");
}

#[tokio::test]
async fn stats_aggregate_today_categories_and_the_week() {
    let (_dir, pool) = open_temp("aggregates").await;
    let now = now_seconds();
    let (today_start, _) = ledger_worker::eat_day_bounds(now);

    // Today: two food payments, one bill, one yesterday, one last week, and
    // one receipt so the balance line is exercised.
    for row in [
        FixtureRow {
            code: "AA1",
            category: "food",
            kind: "buygoods",
            amount_cents: 30_000,
            cost_cents: 0,
            occurred_at: today_start + 60,
            balance_cents: Some(50_000),
        },
        FixtureRow {
            code: "AA2",
            category: "food",
            kind: "buygoods",
            amount_cents: 20_000,
            cost_cents: 0,
            occurred_at: today_start + 120,
            balance_cents: None,
        },
        FixtureRow {
            code: "AA3",
            category: "utilities",
            kind: "paybill",
            amount_cents: 40_000,
            cost_cents: 100,
            occurred_at: today_start + 500,
            balance_cents: None,
        },
        FixtureRow {
            code: "AA4",
            category: "transport",
            kind: "buygoods",
            amount_cents: 10_000,
            cost_cents: 0,
            occurred_at: today_start - 86_400,
            balance_cents: None,
        },
        FixtureRow {
            code: "AA5",
            category: "shopping",
            kind: "buygoods",
            amount_cents: 70_000,
            cost_cents: 0,
            occurred_at: today_start - 10 * 86_400,
            balance_cents: None,
        },
        FixtureRow {
            code: "AA6",
            category: "transfer",
            kind: "received",
            amount_cents: 15_000,
            cost_cents: 0,
            occurred_at: today_start + 900,
            balance_cents: Some(123_400),
        },
    ] {
        insert_fixture_row(&pool, row).await;
    }

    let current = stats(&pool).await.expect("stats");
    assert_eq!(
        current.today_payment_count, 3,
        "received money is not a payment"
    );
    assert_eq!(current.today_spend_cents, 30_000 + 20_000 + 40_100);
    assert_eq!(current.today_income_cents, 15_000);
    assert_eq!(
        current.balance_cents,
        Some(123_400),
        "latest stated balance wins"
    );

    let top: Vec<(&str, i64)> = current
        .top_categories
        .iter()
        .map(|total| (total.category.as_str(), total.amount_cents))
        .collect();
    assert_eq!(top, vec![("food", 50_000), ("utilities", 40_100)]);

    // Seven-day window includes yesterday but not ten days ago.
    assert_eq!(current.week_spend_cents, 30_000 + 20_000 + 40_100 + 10_000);
    assert_eq!(current.prev_week_spend_cents, 70_000);
}

#[tokio::test]
async fn review_items_can_be_retried_and_ignored() {
    let (_dir, pool) = open_temp("review").await;
    ledger_worker::store::record_unparsed(&pool, MALFORMED, "no amount")
        .await
        .expect("park");
    ledger_worker::store::record_unparsed(&pool, MALFORMED, "no amount")
        .await
        .expect("park again");

    let current = stats(&pool).await.expect("stats");
    assert_eq!(current.unparsed, 1, "one message, two attempts");
    assert_eq!(current.review.len(), 1);
    assert_eq!(current.review[0].attempts, 2);
    let id = current.review[0].id;

    // Ignore hides it from the review list without deleting the message.
    assert!(ledger_worker::dismiss_unparsed(&pool, id)
        .await
        .expect("ignore"));
    let after = stats(&pool).await.expect("stats");
    assert_eq!(after.unparsed, 0);
    assert!(after.review.is_empty());
    let still_there: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM unparsed")
        .fetch_one(&pool)
        .await
        .expect("count");
    assert_eq!(still_there, 1, "ignored, not deleted");

    // Retry a second parked message: it becomes a job again and leaves the list.
    // (A different message: ignoring is permanent per message content.)
    let second = "SJ8Z8Z8Z8Z Confirmed. Another unreadable confirmation on 5/10/26.";
    ledger_worker::store::record_unparsed(&pool, second, "no amount")
        .await
        .expect("park");
    let id = stats(&pool).await.expect("stats").review[0].id;
    let outcome = ledger_worker::retry_unparsed(&pool, id)
        .await
        .expect("retry");
    assert!(matches!(outcome, EnqueueOutcome::Enqueued { .. }));
    let after = stats(&pool).await.expect("stats");
    assert!(after.review.is_empty());
    assert_eq!(after.queued_jobs, 1);
}
