//! Android JNI bridge for pocket-ledger.
//!
//! Rivium's [`export!`] macro installs `JNI_OnLoad` and registers the fixed
//! five-method ABI (`nativeStart`, `nativeStop`, `nativeStatus`,
//! `nativeLastError`, `nativeVersion`) on `com/pocketledger/Bridge`.
//!
//! This crate adds two more entry points on the same class, resolved by symbol
//! name in the usual JNI way:
//!
//! * `nativeEnqueue(String rawText, long receivedAtUnix)` -> JSON
//!   `{"outcome":"enqueued","code":"SJA1B2C3D4"}` or `{"outcome":"filtered"}`
//!   or `{"error":"..."}`.
//! * `nativeStats()` -> the same JSON document the `stats` CLI command prints.
//!
//! Both share the database the running service opened: the pool is built once,
//! on first use, from the root Rivium recorded at start-up.

use std::sync::{Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::{errors::ThrowRuntimeExAndDefault, EnvUnowned};
use serde::Serialize;

use ledger_worker::SqlitePool;

rivium_jni::export!(
    class = "com/pocketledger/Bridge",
    app = ledger_worker::LedgerApp
);

struct Bridge {
    runtime: tokio::runtime::Runtime,
    pool: SqlitePool,
}

static BRIDGE: OnceLock<Mutex<Option<Bridge>>> = OnceLock::new();

/// Runs `call` against the (lazily opened) shared database handle.
fn with_bridge<T>(call: impl FnOnce(&Bridge) -> Result<T, String>) -> Result<T, String> {
    let slot = BRIDGE.get_or_init(|| Mutex::new(None));
    let mut guard = slot.lock().map_err(|_| "bridge lock poisoned".to_owned())?;

    if guard.is_none() {
        let root = ledger_worker::started_root()
            .ok_or_else(|| "ledger service is not running yet".to_owned())?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|error| format!("cannot create runtime: {error}"))?;
        let pool = runtime
            .block_on(ledger_worker::open(&ledger_worker::db_path(root)))
            .map_err(|error| format!("cannot open ledger: {error}"))?;
        *guard = Some(Bridge { runtime, pool });
    }

    guard
        .as_ref()
        .ok_or_else(|| "bridge not initialised".to_owned())
        .and_then(call)
}

fn to_json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|error| {
        serde_json::json!({ "error": format!("serialize: {error}") }).to_string()
    })
}

fn error_json(error: String) -> String {
    serde_json::json!({ "error": error }).to_string()
}

/// Queues one raw notification. Called from the Android notification listener.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pocketledger_Bridge_nativeEnqueue<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    raw_text: JString<'caller>,
    received_at: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> Result<jstring, jni::errors::Error> {
            let text = raw_text.to_string();
            let payload = with_bridge(|bridge| {
                let outcome = bridge
                    .runtime
                    .block_on(ledger_worker::enqueue(&bridge.pool, &text, received_at))
                    .map_err(|error| error.to_string())?;
                Ok(to_json(&outcome))
            })
            .unwrap_or_else(error_json);

            Ok(env.new_string(payload)?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Current ledger and queue state, as the `stats` CLI command prints it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_pocketledger_Bridge_nativeStats<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jstring {
    unowned_env
        .with_env(|env| -> Result<jstring, jni::errors::Error> {
            let payload = with_bridge(|bridge| {
                bridge
                    .runtime
                    .block_on(ledger_worker::stats(&bridge.pool))
                    .map_err(|error| error.to_string())
                    .map(|stats| to_json(&stats))
            })
            .unwrap_or_else(error_json);

            Ok(env.new_string(payload)?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
