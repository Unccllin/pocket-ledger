//! Fixture-driven tests: every synthetic M-Pesa message in
//! `tests/fixtures/mpesa_messages.txt` carries its expected parse in a header
//! line, and this harness asserts the parser agrees with all of it.
//!
//! Fixture format (see the file's own preamble):
//!
//! ```text
//! # kind=<tag> amount=<decimal> counterparty="<name>" balance=<decimal> cost=<decimal> [reference="<ref>"]
//! <raw message text, possibly several lines>
//! ---
//! ```
//!
//! `# kind=unknown` marks a notification that must be filtered out as
//! [`ParseError::NotMpesa`] rather than enqueued.

use std::collections::BTreeMap;

use ledger_core::money::parse_cents;
use ledger_core::{categorise, looks_like_mpesa, parse, Category, Kind, ParseError, Transaction};

const FIXTURES: &str = include_str!("fixtures/mpesa_messages.txt");

/// Header/body disagreements in the synthetic fixtures.
///
/// The withdraw block's header says `counterparty="AGENT 123456"`, but its own
/// message body says `from 123456 - AGENT NAME`, which is the real M-Pesa
/// withdraw shape (agent number, then agent name). The fixture preamble says
/// "real formats win", so the parser follows the body and this table records
/// the deviation instead of silently editing the fixture.
const COUNTERPARTY_OVERRIDES: &[(&str, &str)] = &[("SJA6B7C8D9", "AGENT NAME")];

/// References (paybill account, agent number) are not part of the fixture header
/// format; they are read from each message body, which the parser must follow.
const EXPECTED_REFERENCES: &[(&str, Option<&str>)] = &[
    ("SJA3B4C5D6", Some("00000000")),
    ("SJA6B7C8D9", Some("123456")),
];

/// One fixture block: the expectation header plus the raw message.
struct Block {
    header: BTreeMap<String, String>,
    raw: String,
}

fn blocks() -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut header: BTreeMap<String, String> = BTreeMap::new();
    let mut body: Vec<&str> = Vec::new();

    let flush =
        |header: &mut BTreeMap<String, String>, body: &mut Vec<&str>, out: &mut Vec<Block>| {
            if header.contains_key("kind") {
                out.push(Block {
                    header: std::mem::take(header),
                    raw: body.join("\n").trim().to_owned(),
                });
            } else {
                header.clear();
            }
            body.clear();
        };

    for line in FIXTURES.lines() {
        if line.trim() == "---" {
            flush(&mut header, &mut body, &mut blocks);
            continue;
        }
        if line.trim_start().starts_with('#') {
            header.extend(parse_header(line));
        } else {
            body.push(line);
        }
    }
    flush(&mut header, &mut body, &mut blocks);
    blocks
}

/// Parses `key=value` pairs, where values may be double quoted.
fn parse_header(line: &str) -> Vec<(String, String)> {
    let mut pairs = Vec::new();
    let text = line.trim_start_matches('#').trim();
    let bytes = text.as_bytes();
    let mut at = 0usize;

    while at < bytes.len() {
        while at < bytes.len() && bytes[at].is_ascii_whitespace() {
            at += 1;
        }
        let key_start = at;
        while at < bytes.len() && bytes[at] != b'=' && !bytes[at].is_ascii_whitespace() {
            at += 1;
        }
        if at >= bytes.len() || bytes[at] != b'=' {
            while at < bytes.len() && !bytes[at].is_ascii_whitespace() {
                at += 1;
            }
            continue;
        }
        let key = text[key_start..at].to_owned();
        at += 1;
        let value = if bytes.get(at) == Some(&b'"') {
            at += 1;
            let value_start = at;
            while at < bytes.len() && bytes[at] != b'"' {
                at += 1;
            }
            let value = text[value_start..at].to_owned();
            at += usize::from(at < bytes.len());
            value
        } else {
            let value_start = at;
            while at < bytes.len() && !bytes[at].is_ascii_whitespace() {
                at += 1;
            }
            text[value_start..at].to_owned()
        };
        pairs.push((key, value));
    }
    pairs
}

fn expect_cents(header: &BTreeMap<String, String>, key: &str) -> i64 {
    let raw = header
        .get(key)
        .unwrap_or_else(|| panic!("fixture header is missing {key}: {header:?}"));
    parse_cents(raw).unwrap_or_else(|error| panic!("fixture {key}={raw} is not money: {error}"))
}

fn assert_block(block: &Block) -> Option<Transaction> {
    let tag = block.header.get("kind").expect("kind is present").clone();

    if tag == "unknown" {
        assert!(
            !looks_like_mpesa(&block.raw),
            "unknown block must be filtered out before enqueue: {}",
            block.raw
        );
        assert_eq!(parse(&block.raw), Err(ParseError::NotMpesa));
        return None;
    }

    let expected_kind = Kind::from_tag(&tag).unwrap_or_else(|| panic!("unknown kind tag {tag}"));
    let tx = parse(&block.raw)
        .unwrap_or_else(|error| panic!("fixture failed to parse ({error}):\n{}\n", block.raw));

    assert_eq!(tx.kind, expected_kind, "kind for {}", block.raw);
    assert_eq!(
        tx.amount_cents,
        expect_cents(&block.header, "amount"),
        "amount for {}",
        block.raw
    );
    assert_eq!(
        tx.balance_cents,
        Some(expect_cents(&block.header, "balance")),
        "balance for {}",
        block.raw
    );
    assert_eq!(
        tx.cost_cents,
        expect_cents(&block.header, "cost"),
        "cost for {}",
        block.raw
    );

    let expected_counterparty = COUNTERPARTY_OVERRIDES
        .iter()
        .find(|(code, _)| *code == tx.code)
        .map_or_else(
            || {
                block.header.get("counterparty").map_or_else(
                    || panic!("missing counterparty for {}", block.raw),
                    Clone::clone,
                )
            },
            |(_, counterparty)| (*counterparty).to_owned(),
        );
    assert_eq!(
        tx.counterparty, expected_counterparty,
        "counterparty for {}",
        block.raw
    );

    let expected_reference = EXPECTED_REFERENCES
        .iter()
        .find(|(code, _)| *code == tx.code)
        .and_then(|(_, reference)| *reference);
    assert_eq!(
        tx.reference.as_deref(),
        expected_reference,
        "reference for {}",
        block.raw
    );

    assert!(
        !tx.counterparty.chars().any(|c| c.is_ascii_digit()),
        "phone numbers must never be stored, got {:?}",
        tx.counterparty
    );
    assert_eq!(tx.occurred_at.year, 2026, "timestamp for {}", block.raw);
    assert_eq!(tx.raw_hash.len(), 16);

    Some(tx)
}

#[test]
fn every_fixture_matches_its_header() {
    let blocks = blocks();
    assert_eq!(blocks.len(), 7, "the fixture file should hold 7 blocks");

    let mut seen = std::collections::BTreeSet::new();
    for block in &blocks {
        let Some(tx) = assert_block(block) else {
            continue;
        };
        assert!(seen.insert(tx.code.clone()), "duplicate code {}", tx.code);
    }
    assert_eq!(seen.len(), 6);
}

#[test]
fn parsing_is_deterministic() {
    for block in blocks() {
        if block.header.get("kind").map(String::as_str) == Some("unknown") {
            continue;
        }
        let first = parse(&block.raw).expect("parses");
        let second = parse(&block.raw).expect("parses");
        assert_eq!(first, second, "parse must be a pure function");
    }
}

#[test]
fn every_known_fixture_gets_a_category() {
    let mut categories = Vec::new();
    for block in blocks() {
        if block.header.get("kind").map(String::as_str) == Some("unknown") {
            continue;
        }
        let tx = parse(&block.raw).expect("parses");
        categories.push((tx.counterparty.clone(), categorise(&tx)));
    }

    assert!(categories.contains(&("JAVA HOUSE".to_owned(), Category::Food)));
    assert!(categories.contains(&("KPLC PREPAID".to_owned(), Category::Utilities)));
    assert!(categories.contains(&("Safaricom".to_owned(), Category::Airtime)));
    assert!(categories.contains(&("AGENT NAME".to_owned(), Category::Cash)));
    assert!(categories.contains(&("JOHN DOE".to_owned(), Category::Transfer)));
}

#[test]
fn whitespace_quirks_do_not_change_the_result() {
    for block in blocks() {
        if block.header.get("kind").map(String::as_str) == Some("unknown") {
            continue;
        }
        let distorted = block.raw.replace(' ', "  ").replace("  .", ".");
        let baseline = parse(&block.raw).expect("baseline parses");
        let distorted = parse(&distorted).expect("double-spaced variant parses");
        assert_eq!(baseline, distorted, "whitespace changed the parse");
    }
}
