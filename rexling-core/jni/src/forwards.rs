use anyhow::{Context, Result};
use rex_core::forward::{self, Request, Shared};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
    time::Duration,
};
use tokio::sync::{mpsc, watch};

struct Job {
    state: Shared,
    cancel: watch::Sender<bool>,
    select: mpsc::UnboundedSender<u16>,
}
static JOBS: OnceLock<Mutex<HashMap<u64, Job>>> = OnceLock::new();
static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
fn jobs() -> &'static Mutex<HashMap<u64, Job>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn unavailable() -> Value {
    json!({
        "revision":0,
        "phase":"failed",
        "outcome":"failed",
        "failure":"internal",
        "error":"The port forward is no longer available",
        "choices":[]
    })
}

pub fn create(value: Value) -> Result<u64> {
    let request = Request::parse(&value, Duration::from_secs(10))?;
    let state = forward::shared();
    let shared = state.clone();
    let (cancel_tx, cancel_rx) = watch::channel(false);
    let (select_tx, select_rx) = mpsc::unbounded_channel();
    let _worker = std::thread::Builder::new()
        .name("rexling-forward".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()?;
                runtime.block_on(forward::run(request, shared.clone(), cancel_rx, select_rx));
                Ok(())
            }));
            if result.is_err()
                && let Ok(mut state) = shared.lock()
            {
                let revision = state["revision"].as_u64().unwrap_or(0) + 1;
                *state = json!({
                    "revision": revision,
                    "phase":"failed",
                    "outcome":"failed",
                    "failure":"internal",
                    "error":"The port forward worker failed",
                    "choices":[]
                });
            }
        })
        .context("Could not start the port forward worker")?;
    let id = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    jobs()
        .lock()
        .map_err(|_| anyhow::anyhow!("Forward registry unavailable"))?
        .insert(
            id,
            Job {
                state,
                cancel: cancel_tx,
                select: select_tx,
            },
        );
    Ok(id)
}

pub fn poll(id: u64) -> Value {
    jobs()
        .lock()
        .ok()
        .and_then(|jobs| jobs.get(&id).map(|job| forward::snapshot(&job.state)))
        .unwrap_or_else(unavailable)
}

pub fn select(id: u64, port: u16) {
    if let Ok(jobs) = jobs().lock()
        && let Some(job) = jobs.get(&id)
    {
        let _ = job.select.send(port);
    }
}

pub fn close(id: u64) {
    if let Ok(jobs) = jobs().lock()
        && let Some(job) = jobs.get(&id)
    {
        let _ = job.cancel.send(true);
    }
}

pub fn destroy(id: u64) {
    if let Ok(mut jobs) = jobs().lock()
        && let Some(job) = jobs.remove(&id)
    {
        let _ = job.cancel.send(true);
    }
}

pub fn close_all() {
    if let Ok(mut jobs) = jobs().lock() {
        for job in jobs.values() {
            let _ = job.cancel.send(true);
        }
        jobs.clear();
    }
}
