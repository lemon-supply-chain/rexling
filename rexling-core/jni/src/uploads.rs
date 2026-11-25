use anyhow::{Context, Result};
use rex_core::{
    transport::Endpoint,
    upload::{self, Phase, Request},
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;
struct Job {
    state: Arc<Mutex<Value>>,
    cancel: Option<oneshot::Sender<()>>,
}
static JOBS: OnceLock<Mutex<HashMap<u64, Job>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn jobs() -> &'static Mutex<HashMap<u64, Job>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}
pub fn create(value: Value) -> Result<u64> {
    let endpoint = Endpoint::new(
        value["endpoint"]
            .as_str()
            .context("Upload server missing")?,
        Duration::from_secs(10),
    )?;
    let field = |key: &str| {
        value[key]
            .as_str()
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
            .with_context(|| format!("Upload {key} missing"))
    };
    let session = field("session")?;
    let block = field("block")?;
    let path = field("path")?;
    let name = field("name")?;
    let reference = field("ref")?;
    let state = Arc::new(Mutex::new(upload::status(Phase::Preparing, 0, 0, "")));
    let shared = state.clone();
    let (tx, rx) = oneshot::channel();
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let mut registry = jobs()
        .lock()
        .map_err(|_| anyhow::anyhow!("Upload registry unavailable"))?;
    anyhow::ensure!(registry.is_empty(), "An upload is already running");
    let worker = std::thread::Builder::new()
        .name("rexling-upload".into())
        .spawn(move || {
            let result =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                    let runtime = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()?;
                    let (phase, error) = runtime.block_on(upload::upload(
                        Request {
                            endpoint,
                            session: &session,
                            block: &block,
                            path: Path::new(&path),
                            name: &name,
                            reference: &reference,
                        },
                        rx,
                        |phase, sent, total| {
                            if let Ok(mut state) = shared.lock() {
                                *state = upload::status(phase, sent, total, "");
                            }
                        },
                    ));
                    if let Ok(mut state) = shared.lock() {
                        let sent = state["sent"].as_u64().unwrap_or(0);
                        let total = state["total"].as_u64().unwrap_or(0);
                        *state = upload::status(phase, sent, total, &error);
                    }
                    Ok(())
                }));
            let error = match result {
                Ok(Ok(())) => None,
                Ok(Err(error)) => Some(error.to_string()),
                Err(_) => Some("Upload worker failed".into()),
            };
            if let Some(error) = error
                && let Ok(mut state) = shared.lock()
            {
                *state = upload::status(Phase::Uncertain, 0, 0, &error);
            }
        });
    worker.context("Could not start upload worker")?;
    registry.insert(
        id,
        Job {
            state,
            cancel: Some(tx),
        },
    );
    Ok(id)
}
pub fn poll(id: u64) -> Value {
    jobs()
        .lock()
        .ok()
        .and_then(|jobs| {
            jobs.get(&id)
                .and_then(|job| job.state.lock().ok().map(|state| state.clone()))
        })
        .unwrap_or_else(|| json!({"phase":"failed","error":"Upload is no longer available"}))
}
pub fn destroy(id: u64) {
    if let Ok(mut jobs) = jobs().lock()
        && let Some(mut job) = jobs.remove(&id)
        && let Some(cancel) = job.cancel.take()
    {
        let _ = cancel.send(());
    }
}
