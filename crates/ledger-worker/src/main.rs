//! `ledger-worker` CLI: the laptop half of pocket-ledger.
//!
//! ```text
//! ledger-worker [--root DIR] ingest "<message>"
//! ledger-worker [--root DIR] ingest-file <path>
//! ledger-worker [--root DIR] stats
//! ledger-worker [--root DIR] run [--delay-ms N]
//! ```
//!
//! `run` hands the process to Rivium's process host, which owns start-up,
//! readiness, signal handling and graceful shutdown. Everything else is a
//! one-shot command against the same SQLite file.

use std::error::Error;
use std::ffi::OsString;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use ledger_worker::{EnqueueOutcome, LedgerApp};
use serde_json::json;

const USAGE: &str = "\
ledger-worker - offline M-Pesa ledger

USAGE:
    ledger-worker [--root DIR] ingest \"<message>\"
    ledger-worker [--root DIR] ingest-file <path>
    ledger-worker [--root DIR] stats
    ledger-worker [--root DIR] retry-review <id>
    ledger-worker [--root DIR] ignore-review <id>
    ledger-worker [--root DIR] run [--delay-ms N]

OPTIONS:
    --root DIR      Working directory for ledger.db and logs (default: target/dev)
    --delay-ms N    Artificial per-job delay for the persistence scenario

`run` is a Rivium service, so it also accepts Rivium's flags:
    --set KEY=VALUE, --config FILE, default-config, check-config, --help, --version

The database is <root>/ledger.db. No network calls are ever made.";

enum Command {
    Ingest(String),
    IngestFile(PathBuf),
    Stats,
    RetryReview(i64),
    IgnoreReview(i64),
    Run { delay_ms: u64 },
    Help,
}

struct Cli {
    root: PathBuf,
    command: Command,
}

fn main() -> ExitCode {
    let raw_args: Vec<OsString> = std::env::args_os().skip(1).collect();
    match dispatch(raw_args) {
        Ok(code) => ExitCode::from(code),
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

/// Entry point shared by the CLI commands.
///
/// `run` is handed to Rivium before any Tokio runtime exists: Rivium's process
/// host owns its own runtime, and building one inside another panics.
fn dispatch(raw_args: Vec<OsString>) -> Result<u8, Box<dyn Error + Send + Sync>> {
    let cli = Cli::parse(
        raw_args
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned()),
    )?;

    if matches!(cli.command, Command::Help) {
        println!("{USAGE}");
        return Ok(0);
    }
    if let Command::Run { delay_ms } = cli.command {
        // Rivium parses its own arguments and owns the process from here.
        let args = rivium_args(&raw_args, delay_ms);
        return Ok(rivium_host::process::run_args::<LedgerApp>(args).exit());
    }

    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?
        .block_on(run_command(cli))
}

async fn run_command(cli: Cli) -> Result<u8, Box<dyn Error + Send + Sync>> {
    let db_path = ledger_worker::db_path(&cli.root);
    let pool = ledger_worker::open(&db_path).await?;

    match cli.command {
        Command::Ingest(message) => {
            let outcome = ledger_worker::enqueue(&pool, &message, now_unix()).await?;
            println!("{}", serde_json::to_string_pretty(&outcome)?);
            report_filtered(&outcome);
        }
        Command::IngestFile(path) => {
            let text = std::fs::read_to_string(&path)
                .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
            let messages = split_messages(&text);
            let mut enqueued = Vec::new();
            let mut filtered = 0usize;
            for message in &messages {
                match ledger_worker::enqueue(&pool, message, now_unix()).await? {
                    EnqueueOutcome::Enqueued { code } => enqueued.push(code),
                    EnqueueOutcome::Filtered => filtered += 1,
                }
            }
            println!(
                "{}",
                serde_json::to_string_pretty(&json!({
                    "file": path.display().to_string(),
                    "blocks": messages.len(),
                    "enqueued": enqueued,
                    "filtered": filtered,
                }))?
            );
        }
        Command::Stats => {
            let stats = ledger_worker::stats(&pool).await?;
            println!("{}", serde_json::to_string_pretty(&stats)?);
        }
        Command::RetryReview(id) => {
            let outcome = ledger_worker::retry_unparsed(&pool, id).await?;
            println!("{}", serde_json::to_string_pretty(&outcome)?);
            report_filtered(&outcome);
        }
        Command::IgnoreReview(id) => {
            let ignored = ledger_worker::dismiss_unparsed(&pool, id).await?;
            println!(
                "{}",
                serde_json::to_string_pretty(&json!({ "ignored": ignored }))?
            );
        }
        Command::Run { .. } | Command::Help => {
            unreachable!("handled before the database is opened")
        }
    }
    Ok(0)
}

/// Rewrites our CLI arguments into arguments Rivium understands.
///
/// The `run` subcommand is ours, not Rivium's, and `--delay-ms` is translated
/// into Rivium's own `--set delay_ms=...` override so the value lands in
/// [`LedgerConfig`](ledger_worker::LedgerConfig) like any other setting.
fn rivium_args(raw_args: &[OsString], delay_ms: u64) -> Vec<OsString> {
    let mut args = Vec::with_capacity(raw_args.len() + 2);
    let mut skipping_delay_value = false;
    for arg in raw_args {
        if skipping_delay_value {
            skipping_delay_value = false;
            continue;
        }
        match arg.to_str() {
            Some("run") => {}
            Some("--delay-ms") => skipping_delay_value = true,
            _ => args.push(arg.clone()),
        }
    }
    if delay_ms > 0 {
        args.push(OsString::from("--set"));
        args.push(OsString::from(format!("delay_ms={delay_ms}")));
    }
    args
}

fn report_filtered(outcome: &EnqueueOutcome) {
    if *outcome == EnqueueOutcome::Filtered {
        eprintln!("filtered: not an M-Pesa confirmation, nothing stored");
    }
}

/// Splits an ingest file into messages.
///
/// Blocks are separated by a line containing only `---`; lines starting with
/// `#` are comments (fixture headers), matching `tests/fixtures/mpesa_messages.txt`.
fn split_messages(text: &str) -> Vec<String> {
    let mut messages = Vec::new();
    let mut current: Vec<&str> = Vec::new();
    for line in text.lines() {
        if line.trim() == "---" {
            push_block(&mut messages, &mut current);
            continue;
        }
        if line.trim_start().starts_with('#') {
            continue;
        }
        current.push(line);
    }
    push_block(&mut messages, &mut current);
    messages
}

fn push_block(messages: &mut Vec<String>, lines: &mut Vec<&str>) {
    let block = lines.join("\n").trim().to_owned();
    if !block.is_empty() {
        messages.push(block);
    }
    lines.clear();
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| {
            i64::try_from(elapsed.as_secs()).unwrap_or(i64::MAX)
        })
}

impl Cli {
    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut root = PathBuf::from("target/dev");
        let mut command: Option<Command> = None;
        let mut args = args.peekable();

        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--help" | "-h" => command = Some(Command::Help),
                "--root" => {
                    root = PathBuf::from(args.next().ok_or("--root needs a value")?);
                }
                "ingest" => {
                    let message = args.next().ok_or("ingest needs a message")?;
                    command = Some(Command::Ingest(message));
                }
                "ingest-file" => {
                    let path = args.next().ok_or("ingest-file needs a path")?;
                    command = Some(Command::IngestFile(PathBuf::from(path)));
                }
                "stats" => command = Some(Command::Stats),
                "retry-review" => {
                    let id = args
                        .next()
                        .ok_or("retry-review needs a row id")?
                        .parse()
                        .map_err(|error| format!("bad review id: {error}"))?;
                    command = Some(Command::RetryReview(id));
                }
                "ignore-review" => {
                    let id = args
                        .next()
                        .ok_or("ignore-review needs a row id")?
                        .parse()
                        .map_err(|error| format!("bad review id: {error}"))?;
                    command = Some(Command::IgnoreReview(id));
                }
                "run" => {
                    // Everything after `run` belongs to Rivium (--root, --set,
                    // --config, check-config, --help, ...). We only lift out our
                    // own --delay-ms so it can be translated into --set.
                    let mut delay_ms = 0u64;
                    let mut rest = args.by_ref().peekable();
                    while let Some(flag) = rest.next() {
                        if flag == "--delay-ms" {
                            delay_ms = rest
                                .next()
                                .ok_or("--delay-ms needs a value")?
                                .parse()
                                .map_err(|error| format!("bad --delay-ms: {error}"))?;
                        }
                    }
                    command = Some(Command::Run { delay_ms });
                }
                other => return Err(format!("unknown argument: {other}\n\n{USAGE}")),
            }
        }

        let command = command.ok_or_else(|| format!("no command given\n\n{USAGE}"))?;
        Ok(Self { root, command })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_fixture_style_blocks() {
        let text = "\
# kind=sent amount=1.00
SJA1B2C3D4 Confirmed. Ksh1.00 sent to A B on 5/10/26 at 2:15 PM.
---
# kind=unknown
Not a transaction.

---
";
        let messages = split_messages(text);
        assert_eq!(messages.len(), 2);
        assert!(messages[0].starts_with("SJA1B2C3D4"));
        assert_eq!(messages[1], "Not a transaction.");
    }

    #[test]
    fn parses_the_known_commands() {
        let cli = Cli::parse(["ingest".to_owned(), "hello".to_owned()].into_iter()).unwrap();
        assert!(matches!(cli.command, Command::Ingest(_)));
        assert_eq!(cli.root, PathBuf::from("target/dev"));

        let cli = Cli::parse(
            ["--root", "target/other", "run", "--delay-ms", "250"]
                .iter()
                .map(|s| (*s).to_owned()),
        )
        .unwrap();
        assert_eq!(cli.root, PathBuf::from("target/other"));
        assert!(matches!(cli.command, Command::Run { delay_ms: 250 }));
    }

    #[test]
    fn rejects_unknown_arguments() {
        assert!(Cli::parse(["nonsense".to_owned()].into_iter()).is_err());
        assert!(Cli::parse(std::iter::empty()).is_err());
    }

    #[test]
    fn rewrites_arguments_for_rivium() {
        let raw: Vec<OsString> = ["--root", "target/dev3", "run", "--delay-ms", "3000"]
            .iter()
            .map(|s| OsString::from(*s))
            .collect();
        let rewritten: Vec<String> = rivium_args(&raw, 3000)
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            rewritten,
            vec!["--root", "target/dev3", "--set", "delay_ms=3000"]
        );
    }

    #[test]
    fn no_delay_means_no_set_override() {
        let raw: Vec<OsString> = ["run", "--root", "target/dev"]
            .iter()
            .map(|s| OsString::from(*s))
            .collect();
        let rewritten: Vec<String> = rivium_args(&raw, 0)
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert_eq!(rewritten, vec!["--root", "target/dev"]);
    }
}
