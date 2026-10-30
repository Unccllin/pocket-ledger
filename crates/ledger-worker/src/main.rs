//! `ledger-worker` CLI: the laptop half of pocket-ledger.
//!
//! ```text
//! ledger-worker [--root DIR] ingest "<message>"
//! ledger-worker [--root DIR] ingest-file <path>
//! ledger-worker [--root DIR] stats
//! ledger-worker [--root DIR] run [--delay-ms N]
//! ```
//!
//! The database always lives at `<root>/ledger.db`.

use std::error::Error;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::{SystemTime, UNIX_EPOCH};

use ledger_worker::{EnqueueOutcome, WorkerSettings};
use serde_json::json;

const USAGE: &str = "\
ledger-worker - offline M-Pesa ledger

USAGE:
    ledger-worker [--root DIR] ingest \"<message>\"
    ledger-worker [--root DIR] ingest-file <path>
    ledger-worker [--root DIR] stats
    ledger-worker [--root DIR] run [--delay-ms N]

OPTIONS:
    --root DIR      Working directory for ledger.db (default: target/dev)
    --delay-ms N    Artificial per-job delay for the persistence scenario

The database is <root>/ledger.db. No network calls are ever made.";

enum Command {
    Ingest(String),
    IngestFile(PathBuf),
    Stats,
    Run { delay_ms: u64 },
    Help,
}

struct Cli {
    root: PathBuf,
    command: Command,
}

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), Box<dyn Error + Send + Sync>> {
    let cli = Cli::parse(std::env::args().skip(1))?;
    if matches!(cli.command, Command::Help) {
        println!("{USAGE}");
        return Ok(());
    }

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
        Command::Run { delay_ms } => {
            let recovered = ledger_worker::recover_orphans(&pool).await?;
            eprintln!(
                "worker ready: db={} recovered_jobs={recovered} delay_ms={delay_ms}",
                db_path.display()
            );
            ledger_worker::run_worker(pool, WorkerSettings { delay_ms }, async {
                let _ = tokio::signal::ctrl_c().await;
            })
            .await?;
            eprintln!("worker stopped");
        }
        Command::Help => unreachable!("help returns before opening the database"),
    }
    Ok(())
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
                "run" => {
                    let mut delay_ms = 0u64;
                    while let Some(flag) = args.next() {
                        match flag.as_str() {
                            "--delay-ms" => {
                                delay_ms = args
                                    .next()
                                    .ok_or("--delay-ms needs a value")?
                                    .parse()
                                    .map_err(|error| format!("bad --delay-ms: {error}"))?;
                            }
                            other => return Err(format!("unexpected argument for run: {other}")),
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
}
