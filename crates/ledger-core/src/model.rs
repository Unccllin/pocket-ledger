//! Domain types for the pocket ledger.

use crate::time::CivilTime;

/// The kind of M-Pesa movement a confirmation describes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    /// Money sent to a person.
    Sent,
    /// Money received from a person.
    Received,
    /// Payment to a paybill account (for example a utility).
    Paybill,
    /// Payment to a till or business (buy goods and services).
    BuyGoods,
    /// Airtime purchase.
    Airtime,
    /// Cash withdrawal at an agent.
    Withdraw,
}

impl Kind {
    /// Stable lowercase tag, also used by the fixture headers and the database.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Sent => "sent",
            Self::Received => "received",
            Self::Paybill => "paybill",
            Self::BuyGoods => "buygoods",
            Self::Airtime => "airtime",
            Self::Withdraw => "withdraw",
        }
    }

    /// Parses a tag produced by [`Kind::as_str`].
    #[must_use]
    pub fn from_tag(tag: &str) -> Option<Self> {
        match tag {
            "sent" => Some(Self::Sent),
            "received" => Some(Self::Received),
            "paybill" => Some(Self::Paybill),
            "buygoods" => Some(Self::BuyGoods),
            "airtime" => Some(Self::Airtime),
            "withdraw" => Some(Self::Withdraw),
            _ => None,
        }
    }
}

/// A spending category derived from the counterparty by a small keyword table.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Category {
    /// Restaurants, cafes, food delivery.
    Food,
    /// Power, water, other household utilities.
    Utilities,
    /// Airtime and data.
    Airtime,
    /// Fuel, taxis, buses.
    Transport,
    /// Supermarkets and retail.
    Shopping,
    /// Cash withdrawn at an agent.
    Cash,
    /// Person-to-person movement of money.
    Transfer,
    /// Not recognised yet; kept rather than guessed.
    Other,
}

impl Category {
    /// Stable lowercase tag used in the database and the widget.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Food => "food",
            Self::Utilities => "utilities",
            Self::Airtime => "airtime",
            Self::Transport => "transport",
            Self::Shopping => "shopping",
            Self::Cash => "cash",
            Self::Transfer => "transfer",
            Self::Other => "other",
        }
    }

    /// Parses a tag produced by [`Category::as_str`].
    #[must_use]
    pub fn from_tag(tag: &str) -> Option<Self> {
        match tag {
            "food" => Some(Self::Food),
            "utilities" => Some(Self::Utilities),
            "airtime" => Some(Self::Airtime),
            "transport" => Some(Self::Transport),
            "shopping" => Some(Self::Shopping),
            "cash" => Some(Self::Cash),
            "transfer" => Some(Self::Transfer),
            "other" => Some(Self::Other),
            _ => None,
        }
    }
}

/// One confirmed M-Pesa transaction.
///
/// `code` is the idempotency key: the same confirmation arriving twice must
/// produce exactly one ledger row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Transaction {
    /// M-Pesa transaction code, e.g. `SJA1B2C3D4`. Unique per transaction.
    pub code: String,
    /// What kind of movement this was.
    pub kind: Kind,
    /// Transaction amount in integer cents.
    pub amount_cents: i64,
    /// Who the money went to or came from. Phone numbers are never stored.
    pub counterparty: String,
    /// Paybill account or agent number, when the message carries one.
    pub reference: Option<String>,
    /// Running M-PESA balance after the transaction, in integer cents.
    pub balance_cents: Option<i64>,
    /// Transaction cost in integer cents (`0` when the message omits it).
    pub cost_cents: i64,
    /// Wall-clock time printed in the message.
    pub occurred_at: CivilTime,
    /// FNV-1a hash of the normalised message text, for tracing and dedup audits.
    pub raw_hash: String,
}

impl Transaction {
    /// Amount formatted for display, e.g. `350.00`.
    #[must_use]
    pub fn amount_display(&self) -> String {
        crate::money::format_cents(self.amount_cents)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tags_round_trip() {
        for kind in [
            Kind::Sent,
            Kind::Received,
            Kind::Paybill,
            Kind::BuyGoods,
            Kind::Airtime,
            Kind::Withdraw,
        ] {
            assert_eq!(Kind::from_tag(kind.as_str()), Some(kind));
        }
        assert_eq!(Kind::from_tag("nonsense"), None);
    }

    #[test]
    fn category_tags_round_trip() {
        for category in [
            Category::Food,
            Category::Utilities,
            Category::Airtime,
            Category::Transport,
            Category::Shopping,
            Category::Cash,
            Category::Transfer,
            Category::Other,
        ] {
            assert_eq!(Category::from_tag(category.as_str()), Some(category));
        }
        assert_eq!(Category::from_tag("nonsense"), None);
    }
}
