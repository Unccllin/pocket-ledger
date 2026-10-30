# syntax=docker/dockerfile:1
# The laptop half of pocket-ledger as a container: the same CLI the README
# describes, so the worker can be tried without a Rust toolchain.
FROM rust:1.94-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY crates ./crates
RUN cargo build --release -p ledger-worker

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home ledger \
    && mkdir -p /data && chown ledger /data
COPY --from=build /src/target/release/ledger-worker /usr/local/bin/ledger-worker
USER ledger
VOLUME ["/data"]
ENTRYPOINT ["ledger-worker"]
CMD ["--root", "/data", "stats"]
