//! Keyword-based categorisation.
//!
//! This is intentionally a small table, not a model: it is easy to audit, easy
//! to extend, and never guesses when it does not recognise the counterparty.

use crate::model::{Category, Kind, Transaction};

/// Ordered keyword table. First match wins, so put specific names before
/// generic words. Keywords are matched case-insensitively as substrings.
const KEYWORDS: &[(&str, Category)] = &[
    // Utilities and bills.
    ("KPLC", Category::Utilities),
    ("KENYA POWER", Category::Utilities),
    ("NAIROBI WATER", Category::Utilities),
    ("WATER", Category::Utilities),
    ("DSTV", Category::Utilities),
    ("STARTIMES", Category::Utilities),
    ("ZUKU", Category::Utilities),
    ("TELKOM", Category::Utilities),
    // Food.
    ("JAVA", Category::Food),
    ("RESTAURANT", Category::Food),
    ("CAFE", Category::Food),
    ("COFFEE", Category::Food),
    ("HOTEL", Category::Food),
    ("PIZZA", Category::Food),
    ("CHICKEN", Category::Food),
    ("KFC", Category::Food),
    ("BURGER", Category::Food),
    ("BAKERY", Category::Food),
    ("CHIPPER", Category::Food),
    // Transport and fuel.
    ("UBER", Category::Transport),
    ("BOLT", Category::Transport),
    ("LITTLE CAB", Category::Transport),
    ("MATATU", Category::Transport),
    ("FUEL", Category::Transport),
    ("PETROL", Category::Transport),
    ("TOTAL", Category::Transport),
    ("SHELL", Category::Transport),
    // Retail.
    ("NAIVAS", Category::Shopping),
    ("CARREFOUR", Category::Shopping),
    ("QUICKMART", Category::Shopping),
    ("CHANDARANA", Category::Shopping),
    ("SUPERMARKET", Category::Shopping),
    ("SHOPRITE", Category::Shopping),
    ("TUSKYS", Category::Shopping),
    ("CLEANSHELF", Category::Shopping),
];

/// Assigns a spending category to a parsed transaction.
///
/// Kind decides the unambiguous cases (airtime, cash withdrawal); everything
/// else falls back to the keyword table and then to `Transfer` for
/// person-to-person movements or `Other` for businesses we do not know.
#[must_use]
pub fn categorise(tx: &Transaction) -> Category {
    match tx.kind {
        Kind::Airtime => Category::Airtime,
        Kind::Withdraw => Category::Cash,
        Kind::Received | Kind::Sent => keyword_category(tx).unwrap_or(Category::Transfer),
        Kind::BuyGoods | Kind::Paybill => keyword_category(tx).unwrap_or(Category::Other),
    }
}

fn keyword_category(tx: &Transaction) -> Option<Category> {
    let name = tx.counterparty.to_uppercase();
    KEYWORDS
        .iter()
        .find(|(keyword, _)| name.contains(keyword))
        .map(|(_, category)| *category)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::money;
    use crate::parse;
    use crate::time::CivilTime;

    fn tx(kind: Kind, counterparty: &str) -> Transaction {
        Transaction {
            code: "SJA1B2C3D4".to_owned(),
            kind,
            amount_cents: money::parse_cents("350.00").unwrap(),
            counterparty: counterparty.to_owned(),
            reference: None,
            balance_cents: Some(0),
            cost_cents: 0,
            occurred_at: CivilTime::new(2026, 10, 5, 13, 0).unwrap(),
            raw_hash: "0".repeat(16),
        }
    }

    #[test]
    fn kind_decides_airtime_and_cash() {
        assert_eq!(
            categorise(&tx(Kind::Airtime, "Safaricom")),
            Category::Airtime
        );
        assert_eq!(
            categorise(&tx(Kind::Withdraw, "AGENT NAME")),
            Category::Cash
        );
    }

    #[test]
    fn keywords_match_case_insensitively() {
        assert_eq!(
            categorise(&tx(Kind::BuyGoods, "Java House")),
            Category::Food
        );
        assert_eq!(
            categorise(&tx(Kind::Paybill, "kplc prepaid")),
            Category::Utilities
        );
        assert_eq!(
            categorise(&tx(Kind::Paybill, "Naivas Supermarket")),
            Category::Shopping
        );
        assert_eq!(categorise(&tx(Kind::BuyGoods, "Bolt")), Category::Transport);
    }

    #[test]
    fn person_to_person_is_transfer_and_unknown_business_is_other() {
        assert_eq!(categorise(&tx(Kind::Sent, "JOHN DOE")), Category::Transfer);
        assert_eq!(
            categorise(&tx(Kind::Received, "JANE DOE")),
            Category::Transfer
        );
        assert_eq!(
            categorise(&tx(Kind::BuyGoods, "MYSTERY SHOP")),
            Category::Other
        );
    }

    #[test]
    fn categorises_the_real_fixture_shape() {
        let raw = "SJA3B4C5D6 Confirmed. Ksh1,200.00 sent to KPLC PREPAID for account 00000000 on 5/10/26 at 8:00 PM New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.";
        let parsed = parse::parse(raw).unwrap();
        assert_eq!(categorise(&parsed), Category::Utilities);
    }
}
