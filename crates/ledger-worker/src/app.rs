//! The Rivium application: one long-running service that owns the Apalis worker.
//!
//! The same [`App`] runs under Rivium's process host on a laptop
//! (`ledger-worker run --root target/dev`) and, with `rivium-jni`, inside the
//! Android app. Rivium owns start-up, readiness and shutdown; this module only
//! says what to do in each phase.

use std::path::Path;

use rivium::{service, Error, Kind, Result, Service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

use crate::store::{self, StoreError};
use crate::worker::WorkerSettings;

/// Configuration visible to Rivium's config file and `--set key=value`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct LedgerConfig {
    /// Artificial processing delay per job in milliseconds (0 in normal use).
    ///
    /// Kept configurable so the persistence scenario can keep a job in flight
    /// while the process is killed: `--set delay_ms=3000`.
    pub delay_ms: u64,
}

/// The pocket-ledger application.
pub struct LedgerApp;

impl App for LedgerApp {
    const NAME: &'static str = "pocket-ledger";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = LedgerConfig;

    fn services(
        config: &LedgerConfig,
        context: &Context<LedgerConfig>,
    ) -> Result<Vec<Box<dyn Service>>> {
        let delay_ms = config.delay_ms;
        let db_path = context.paths().root().join("ledger.db");

        Ok(vec![service(
            "ledger-worker",
            Kind::Frontline,
            move |ctx| async move {
                // Readiness is declared only once the database is migrated and
                // jobs from a previous (possibly killed) run are re-queued.
                let pool = store::open(&db_path).await.map_err(|error| {
                    service_error("opening the ledger database", &db_path, error)
                })?;
                let recovered = store::recover_orphans(&pool)
                    .await
                    .map_err(|error| service_error("recovering orphaned jobs", &db_path, error))?;
                eprintln!(
                    "ledger service ready: db={} recovered_jobs={recovered}",
                    db_path.display()
                );
                ctx.ready();

                let stop = ctx.clone();
                crate::worker::run_worker(pool, WorkerSettings { delay_ms }, async move {
                    stop.stopped().await;
                })
                .await
                .map_err(|error| Error::internal(format!("apalis worker failed: {error}")))?;

                eprintln!("ledger service stopped");
                Ok(())
            },
        )])
    }
}

fn service_error(what: &str, db_path: &Path, error: StoreError) -> Error {
    Error::internal(format!("{what} at {}: {error}", db_path.display()))
}
