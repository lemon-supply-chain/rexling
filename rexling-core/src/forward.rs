//! On-demand loopback forwarding through a detached Rex dial tunnel.
//!
//! One worker owns one forward: a revision-16 control connection, a loopback
//! listener, one `com.superlogical.tunnel.dial` block, and every data stream.
//! Nothing else mutates that state. Kotlin observes a published snapshot and
//! sends `select` or `close` commands.
//!
//! The listener closes as soon as a complete port snapshot no longer contains
//! the chosen address and port. Partial, truncated, and unsupported snapshots
//! never close a working forward.

use crate::{
    control::{CallReply, Control},
    transport::{self, Endpoint},
};
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{mpsc, watch},
};
use tokio_tungstenite::tungstenite::Message;

pub const BYTES: &str = "rex.data.bytes.v1";
pub const TUNNEL_DIAL: &str = "com.superlogical.tunnel.dial";
const RPC_TIMEOUT: Duration = Duration::from_secs(10);
const POLL_INTERVAL: Duration = Duration::from_secs(15);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const CLEANUP_TIMEOUT: Duration = Duration::from_secs(2);
const MAX_CONNECTIONS: usize = 64;
const MAX_STREAM_CHUNK: usize = 65536;

pub type Shared = Arc<Mutex<Value>>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    New,
    Connecting,
    Resolving,
    AwaitingSelection,
    Binding,
    CreatingTunnel,
    Active,
    Stopping,
    Closed,
    Failed,
}
impl Phase {
    fn name(self) -> &'static str {
        match self {
            Self::New => "new",
            Self::Connecting => "connecting",
            Self::Resolving => "resolving",
            Self::AwaitingSelection => "awaiting_selection",
            Self::Binding => "binding",
            Self::CreatingTunnel => "creating_tunnel",
            Self::Active => "active",
            Self::Stopping => "stopping",
            Self::Closed => "closed",
            Self::Failed => "failed",
        }
    }
    fn terminal(self) -> bool {
        matches!(self, Self::Closed | Self::Failed)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Reason {
    User,
    PortGone,
    PortAddressChanged,
    SourceBlockClosed,
    SourceProgramExited,
    SessionClosed,
    TunnelClosed,
    ControlLost,
    Error,
}
impl Reason {
    fn name(self) -> &'static str {
        match self {
            Self::User => "user",
            Self::PortGone => "port_gone",
            Self::PortAddressChanged => "port_address_changed",
            Self::SourceBlockClosed => "source_block_closed",
            Self::SourceProgramExited => "source_program_exited",
            Self::SessionClosed => "session_closed",
            Self::TunnelClosed => "tunnel_closed",
            Self::ControlLost => "control_lost",
            Self::Error => "error",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Failure {
    UnsupportedServer,
    ConnectFailed,
    NotListening,
    NeedsPort,
    BindFailed,
    TunnelFailed,
    ControlFailed,
    Internal,
}
impl Failure {
    fn name(self) -> &'static str {
        match self {
            Self::UnsupportedServer => "unsupported_server",
            Self::ConnectFailed => "connect_failed",
            Self::NotListening => "not_listening",
            Self::NeedsPort => "needs_port",
            Self::BindFailed => "bind_failed",
            Self::TunnelFailed => "tunnel_failed",
            Self::ControlFailed => "control_failed",
            Self::Internal => "internal",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PortAuthority {
    Unknown,
    Complete,
    Partial,
    Truncated,
    Unsupported,
}
impl PortAuthority {
    fn name(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::Complete => "complete",
            Self::Partial => "partial",
            Self::Truncated => "truncated",
            Self::Unsupported => "unsupported",
        }
    }
    fn complete(self) -> bool {
        self == Self::Complete
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PortMode {
    Disabled,
    PollOnly,
    EventDriven,
}
impl PortMode {
    fn name(self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::PollOnly => "poll",
            Self::EventDriven => "event",
        }
    }
}

struct Stop {
    reason: Reason,
    failure: Option<Failure>,
    error: String,
}
impl Stop {
    fn closed(reason: Reason, error: impl Into<String>) -> Self {
        Self {
            reason,
            failure: None,
            error: error.into(),
        }
    }
    fn failed(failure: Failure, error: impl std::fmt::Display) -> Self {
        Self {
            reason: Reason::Error,
            failure: Some(failure),
            error: error.to_string(),
        }
    }
    fn user() -> Self {
        Self::closed(Reason::User, "The forward was closed")
    }
    /// A lost control connection or a closed tunnel ends in failure; the
    /// listener and streams cannot continue without their owner.
    fn lost(reason: Reason, error: impl Into<String>) -> Self {
        Self {
            reason,
            failure: Some(if reason == Reason::TunnelClosed {
                Failure::TunnelFailed
            } else {
                Failure::ControlFailed
            }),
            error: error.into(),
        }
    }
    fn cancelled(&self) -> bool {
        self.reason == Reason::User
    }
}

pub struct Request {
    pub endpoint: Endpoint,
    pub session: String,
    pub source_block: Option<String>,
    pub target_host: Option<String>,
    pub target_port: Option<u16>,
    pub default_port: Option<u16>,
    /// Stable per-install tag used to recognise and close orphaned tunnels.
    pub label_tag: Option<String>,
}
impl Request {
    pub fn parse(value: &Value, timeout: Duration) -> Result<Self> {
        let endpoint = Endpoint::new(
            value["endpoint"].as_str().context("Forward endpoint missing")?,
            timeout,
        )?;
        let text = |key: &str| {
            value[key]
                .as_str()
                .map(str::trim)
                .filter(|v| !v.is_empty())
                .map(str::to_owned)
        };
        let session = text("session").context("Forward session missing")?;
        let source_block = text("sourceBlock");
        let target_host = text("targetHost");
        let port = |key: &str| -> Result<Option<u16>> {
            match value.get(key) {
                None | Some(Value::Null) => Ok(None),
                Some(v) => v
                    .as_u64()
                    .filter(|n| (1..=65535).contains(n))
                    .map(|n| Some(n as u16))
                    .context("Forward port must be between 1 and 65535"),
            }
        };
        let target_port = port("targetPort")?;
        let default_port = port("defaultPort")?;
        let label_tag = text("labelTag").map(|tag| {
            tag.chars()
                .filter(|c| c.is_ascii_alphanumeric() || *c=='-')
                .take(16)
                .collect::<String>()
        });
        ensure!(
            source_block.is_some() || target_port.is_some(),
            "A forward needs a source block or an explicit port"
        );
        Ok(Self {
            endpoint,
            session,
            source_block,
            target_host,
            target_port,
            default_port,
            label_tag,
        })
    }
}

#[derive(Clone, Debug)]
pub struct PortEntry {
    port: u16,
    address: String,
    pid: Option<u64>,
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    status: String,
    truncated: bool,
    blob_id: Option<u64>,
    ports: Vec<PortEntry>,
    processes: BTreeMap<u64, String>,
    observed_seq: u64,
}
impl Snapshot {
    fn from_value(value: &Value, seq: u64) -> Self {
        let ports = value["ports"]
            .as_array()
            .map(|items| {
                items
                    .iter()
                    .filter_map(|item| {
                        let protocol = item["protocol"].as_str().unwrap_or("tcp");
                        if !protocol.eq_ignore_ascii_case("tcp") {
                            return None;
                        }
                        let port = item["port"].as_u64().filter(|p| *p <= 65535)? as u16;
                        let address = item["address"].as_str()?.to_owned();
                        Some(PortEntry {
                            port,
                            address,
                            pid: item["pid"].as_u64(),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        let processes = value["processes"]
            .as_array()
            .map(|items| {
                items
                    .iter()
                    .filter_map(|item| {
                        let pid = item["pid"].as_u64()?;
                        let label = item["script"]
                            .as_str()
                            .or_else(|| item["argv0"].as_str())
                            .or_else(|| item["name"].as_str())
                            .unwrap_or("")
                            .to_owned();
                        Some((pid, label))
                    })
                    .collect()
            })
            .unwrap_or_default();
        Self {
            status: value["status"].as_str().unwrap_or("").to_owned(),
            truncated: value["truncated"].as_bool().unwrap_or(false),
            blob_id: value["id"].as_u64(),
            ports,
            processes,
            observed_seq: seq,
        }
    }
    fn authority(&self) -> PortAuthority {
        if self.status == "unsupported" {
            PortAuthority::Unsupported
        } else if self.truncated {
            PortAuthority::Truncated
        } else if self.status == "ok" {
            PortAuthority::Complete
        } else {
            PortAuthority::Partial
        }
    }
    fn entries(&self, port: u16) -> Vec<&PortEntry> {
        self.ports.iter().filter(|entry| entry.port == port).collect()
    }
    fn distinct_ports(&self) -> Vec<u16> {
        self.ports
            .iter()
            .map(|entry| entry.port)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
    fn has(&self, port: u16, address: &str) -> bool {
        self.entries(port)
            .iter()
            .any(|entry| same_address(&entry.address, address))
    }
    fn label(&self, port: u16) -> String {
        self.entries(port)
            .iter()
            .find_map(|entry| entry.pid.and_then(|pid| self.processes.get(&pid)))
            .cloned()
            .unwrap_or_default()
    }
}

/// Wildcard listeners accept loopback traffic, so the tunnel can target it.
fn normalize_address(address: &str) -> String {
    let address = address.trim().trim_start_matches('[').trim_end_matches(']');
    match address.to_ascii_lowercase().as_str() {
        "0.0.0.0" | "localhost" => "127.0.0.1".into(),
        "::" => "::1".into(),
        _ => address.to_owned(),
    }
}
fn same_address(a: &str, b: &str) -> bool {
    normalize_address(a).eq_ignore_ascii_case(&normalize_address(b))
}
fn is_ip_literal(host: &str) -> bool {
    host.parse::<std::net::IpAddr>().is_ok()
}
fn preferred_address(entries: &[&PortEntry], host: Option<&str>) -> Option<String> {
    let wanted = host.map(normalize_address);
    if let Some(wanted) = wanted.as_deref()
        && is_ip_literal(wanted)
        && entries
            .iter()
            .any(|entry| same_address(&entry.address, wanted))
    {
        return Some(wanted.to_owned());
    }
    for candidate in ["127.0.0.1", "::1"] {
        if entries
            .iter()
            .any(|entry| same_address(&entry.address, candidate))
        {
            return Some(candidate.into());
        }
    }
    entries
        .first()
        .map(|entry| normalize_address(&entry.address))
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Choice {
    port: u16,
    address: String,
    label: String,
}
impl Choice {
    fn json(&self) -> Value {
        json!({"port":self.port,"address":self.address,"label":self.label})
    }
}

#[derive(Clone, Debug)]
struct Resolved {
    host: String,
    port: u16,
}

#[derive(Clone, Debug)]
struct PendingBlob {
    id: u64,
    observed_seq: u64,
}

pub fn shared() -> Shared {
    Arc::new(Mutex::new(json!({
        "revision":0,
        "phase":Phase::New.name(),
        "outcome":null,
        "reason":"",
        "failure":"",
        "error":"",
        "authority":PortAuthority::Unknown.name(),
        "portMode":PortMode::Disabled.name(),
        "targetHost":"",
        "targetPort":0,
        "localPort":0,
        "localUrl":"",
        "tunnelBlock":"",
        "connections":0,
        "choices":[]
    })))
}

fn publish(shared: &Shared, value: &Value) {
    if let Ok(mut state) = shared.lock() {
        *state = value.clone();
    }
}

pub fn snapshot(shared: &Shared) -> Value {
    shared
        .lock()
        .map(|state| state.clone())
        .unwrap_or_else(|_| json!({"phase":"failed","failure":Failure::Internal.name(),"error":"forward state unavailable"}))
}

struct Worker {
    request: Request,
    shared: Shared,
    cancel: watch::Receiver<bool>,
    select_rx: mpsc::UnboundedReceiver<u16>,
    control: Option<Control>,
    revision: u64,
    phase: Phase,
    reason: Option<Reason>,
    failure: Option<Failure>,
    error: String,
    port_mode: PortMode,
    snapshot: Option<Snapshot>,
    applied_seq: u64,
    blob: Option<PendingBlob>,
    resolved: Option<Resolved>,
    local_port: u16,
    tunnel: String,
    listener: Option<TcpListener>,
    choices: Vec<Choice>,
    streams: Vec<tokio::task::JoinHandle<()>>,
    stream_done: mpsc::UnboundedReceiver<()>,
    stream_done_tx: mpsc::UnboundedSender<()>,
    connections: usize,
    accepted: u64,
    dirty: bool,
}

pub async fn run(
    request: Request,
    shared: Shared,
    cancel: watch::Receiver<bool>,
    select_rx: mpsc::UnboundedReceiver<u16>,
) {
    let (done_tx, done_rx) = mpsc::unbounded_channel();
    let mut worker = Worker {
        request,
        shared,
        cancel,
        select_rx,
        control: None,
        revision: 0,
        phase: Phase::New,
        reason: None,
        failure: None,
        error: String::new(),
        port_mode: PortMode::Disabled,
        snapshot: None,
        applied_seq: 0,
        blob: None,
        resolved: None,
        local_port: 0,
        tunnel: String::new(),
        listener: None,
        choices: Vec::new(),
        streams: Vec::new(),
        stream_done: done_rx,
        stream_done_tx: done_tx,
        connections: 0,
        accepted: 0,
        dirty: false,
    };
    let stop = worker.execute().await;
    worker.finish(stop).await;
}

impl Worker {
    async fn execute(&mut self) -> Stop {
        self.publish();
        if let Err(stop) = self.connect().await {
            return stop;
        }
        if let Err(stop) = self.resolve().await {
            return stop;
        }
        if let Err(stop) = self.bind().await {
            return stop;
        }
        if let Err(stop) = self.create_tunnel().await {
            return stop;
        }
        match self.active().await {
            Ok(()) => Stop::closed(Reason::User, "The forward ended"),
            Err(stop) => stop,
        }
    }

    async fn connect(&mut self) -> Result<(), Stop> {
        self.set_phase(Phase::Connecting);
        let future = Control::connect_forward(self.request.endpoint.clone());
        tokio::pin!(future);
        let control = tokio::select! {
            biased;
            _=wait_cancel(&mut self.cancel)=>return Err(Stop::user()),
            result=&mut future=>result.map_err(|error|Stop::failed(Failure::ConnectFailed,error))?,
        };
        let revision = control.welcome()["api_revision"].as_u64().unwrap_or(0);
        if revision < 15 {
            return Err(Stop::failed(
                Failure::UnsupportedServer,
                format!(
                    "This Rex server speaks API revision {revision}; port forwarding needs 15"
                ),
            ));
        }
        self.port_mode = match (&self.request.source_block, revision) {
            (Some(_), r) if r >= 16 => PortMode::EventDriven,
            (Some(_), _) => PortMode::PollOnly,
            (None, _) => PortMode::Disabled,
        };
        self.control = Some(control);
        self.rpc("session.attach", json!({"session_id":self.request.session}))
            .await?;
        self.cleanup_stale_tunnels().await;
        if cancelled(&mut self.cancel) {
            return Err(Stop::user());
        }
        self.publish();
        Ok(())
    }

    /// Close detached tunnels this install left behind after a process death.
    /// Blocks still owned by a live connection are never touched.
    async fn cleanup_stale_tunnels(&mut self) {
        let Some(tag) = self.request.label_tag.clone() else {
            return;
        };
        let prefix = format!("rexling fwd {tag} ");
        let reply = match self
            .rpc(
                "session.list_blocks",
                json!({"session_id":self.request.session}),
            )
            .await
        {
            Ok(reply) => reply,
            Err(stop) if stop.cancelled() => return,
            Err(_) => return,
        };
        let stale: Vec<String> = reply.payload["blocks"]
            .as_array()
            .map(|blocks| {
                blocks
                    .iter()
                    .filter(|block| {
                        block["creator_name"] == "com.superlogical.tunnel"
                            && block["placement"] == "detached"
                            && block["owner"].as_str().is_none_or(str::is_empty)
                            && block["label"]
                                .as_str()
                                .is_some_and(|label| label.starts_with(&prefix))
                    })
                    .filter_map(|block| block["block_id"].as_str())
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_default();
        for block in stale {
            let _ = self
                .rpc(
                    "block.close",
                    json!({"session_id":self.request.session,"block_id":block}),
                )
                .await;
        }
        let _ = self.drain_events();
    }

    async fn resolve(&mut self) -> Result<(), Stop> {
        self.set_phase(Phase::Resolving);
        if self.request.source_block.is_some() && self.port_mode != PortMode::Disabled {
            let snapshot = self.read_ports().await?;
            self.apply(snapshot);
            if let Some(stop) = self.drain_events() {
                return Err(stop);
            }
            if let Some(stop) = self.complete_blob().await {
                return Err(stop);
            }
        }
        if let Some(port) = self.request.target_port {
            self.resolved = Some(self.explicit_resolution(port)?);
            return Ok(());
        }
        if let Some(default) = self.request.default_port
            && self
                .snapshot
                .as_ref()
                .is_some_and(|s| s.authority().complete() && !s.entries(default).is_empty())
        {
            self.resolved = self.resolution_for(default);
            return Ok(());
        }
        self.auto_select().await
    }

    fn explicit_resolution(&self, port: u16) -> Result<Resolved, Stop> {
        let fallback_host = || {
            self.request
                .target_host
                .as_deref()
                .map(normalize_address)
                .filter(|host| is_ip_literal(host))
                .unwrap_or_else(|| "127.0.0.1".into())
        };
        let Some(snapshot) = &self.snapshot else {
            return Ok(Resolved {
                host: fallback_host(),
                port,
            });
        };
        if let Some(resolved) = self.resolution_for(port) {
            return Ok(resolved);
        }
        if snapshot.authority().complete() {
            return Err(Stop::failed(
                Failure::NotListening,
                format!("Nothing in this terminal is listening on port {port}"),
            ));
        }
        Ok(Resolved {
            host: fallback_host(),
            port,
        })
    }

    fn resolution_for(&self, port: u16) -> Option<Resolved> {
        let snapshot = self.snapshot.as_ref()?;
        let entries = snapshot.entries(port);
        let host = preferred_address(&entries, self.request.target_host.as_deref())?;
        Some(Resolved { host, port })
    }

    async fn auto_select(&mut self) -> Result<(), Stop> {
        loop {
            if cancelled(&mut self.cancel) {
                return Err(Stop::user());
            }
            let Some(snapshot) = self.snapshot.clone() else {
                return Err(Stop::failed(
                    Failure::NeedsPort,
                    "Choose a port; this server sent no port list",
                ));
            };
            if !snapshot.authority().complete() {
                return Err(Stop::failed(
                    Failure::NeedsPort,
                    "The server could not list every port; open a link with an explicit port",
                ));
            }
            let ports = snapshot.distinct_ports();
            match ports.as_slice() {
                [] => {
                    return Err(Stop::failed(
                        Failure::NotListening,
                        "Nothing in this terminal is listening on a TCP port",
                    ));
                }
                [port] => {
                    self.resolved = self.resolution_for(*port);
                    return Ok(());
                }
                _ => {
                    self.choices = self.choices(&snapshot);
                    self.set_phase(Phase::AwaitingSelection);
                    if let Some(port) = self.wait_selection().await? {
                        self.choices.clear();
                        self.resolved = self.resolution_for(port);
                        return Ok(());
                    }
                }
            }
        }
    }

    fn choices(&self, snapshot: &Snapshot) -> Vec<Choice> {
        snapshot
            .distinct_ports()
            .into_iter()
            .filter_map(|port| {
                let entries = snapshot.entries(port);
                Some(Choice {
                    port,
                    address: preferred_address(&entries, self.request.target_host.as_deref())?,
                    label: snapshot.label(port),
                })
            })
            .collect()
    }

    /// `None` means the snapshot changed and the caller must evaluate again.
    async fn wait_selection(&mut self) -> Result<Option<u16>, Stop> {
        let mut poll = tokio::time::interval(POLL_INTERVAL);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        poll.tick().await;
        enum Signal {
            Cancel,
            Selected(u16),
            Poll,
            Message(Value),
        }
        loop {
            self.publish();
            let timer = poll.tick();
            tokio::pin!(timer);
            let signal = {
                let (control, cancel, selects) = (
                    self.control.as_mut().expect("selection needs a control connection"),
                    &mut self.cancel,
                    &mut self.select_rx,
                );
                tokio::select! {
                    biased;
                    _=wait_cancel(cancel)=>Signal::Cancel,
                    chosen=selects.recv()=>match chosen {
                        Some(port)=>Signal::Selected(port),
                        None=>Signal::Cancel,
                    },
                    _=&mut timer, if self.port_mode==PortMode::PollOnly=>Signal::Poll,
                    message=control.next_message()=>match message {
                        Ok(message)=>Signal::Message(message),
                        Err(error)=>{
                            return Err(if *cancel.borrow() {Stop::user()} else {Stop::failed(Failure::ControlFailed,error)});
                        }
                    },
                }
            };
            match signal {
                Signal::Cancel => return Err(Stop::user()),
                Signal::Selected(port) => {
                    if self
                        .snapshot
                        .as_ref()
                        .is_some_and(|snapshot| !snapshot.entries(port).is_empty())
                    {
                        return Ok(Some(port));
                    }
                }
                Signal::Poll => {
                    self.poll_ports().await?;
                    return Ok(None);
                }
                Signal::Message(message) => {
                    if let Some(stop)=self.handle_event(&message) {return Err(stop)}
                    if self.blob.is_some()
                        && let Some(stop)=self.complete_blob().await {return Err(stop)}
                    if self.snapshot_changed() {return Ok(None)}
                }
            }
        }
    }

    async fn bind(&mut self) -> Result<(), Stop> {
        self.set_phase(Phase::Binding);
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|error| Stop::failed(Failure::BindFailed, format!("Could not listen locally: {error}")))?;
        self.local_port = listener
            .local_addr()
            .map_err(|error| Stop::failed(Failure::BindFailed, error))?
            .port();
        self.listener = Some(listener);
        self.publish();
        Ok(())
    }

    async fn create_tunnel(&mut self) -> Result<(), Stop> {
        self.set_phase(Phase::CreatingTunnel);
        let resolved = self.resolved.clone().ok_or_else(|| {
            Stop::failed(Failure::Internal, "The forward has no resolved target")
        })?;
        let payload = json!({
            "session_id":self.request.session,
            "flavor":TUNNEL_DIAL,
            "options":{
                "host":resolved.host,
                "port":resolved.port,
                "client_address":format!("127.0.0.1:{}",self.local_port)
            },
            "label":self.label(&resolved)
        });
        let reply = self.rpc("session.new_block", payload).await?;
        let block = reply
            .payload["block_id"]
            .as_str()
            .filter(|id| !id.is_empty())
            .ok_or_else(|| {
                Stop::failed(Failure::TunnelFailed, "Rex created no tunnel block")
            })?
            .to_owned();
        self.tunnel = block;
        if let Some(stop) = self.drain_events() {
            return Err(stop);
        }
        if let Some(stop) = self.port_check() {
            return Err(stop);
        }
        Ok(())
    }

    fn label(&self, resolved: &Resolved) -> String {
        let tag = self
            .request
            .label_tag
            .as_deref()
            .map(|tag| format!(" {tag}"))
            .unwrap_or_default();
        format!("rexling fwd{tag} {}:{}", resolved.host, resolved.port)
            .chars()
            .take(128)
            .collect()
    }

    async fn active(&mut self) -> Result<(), Stop> {
        self.set_phase(Phase::Active);
        let mut poll = tokio::time::interval(POLL_INTERVAL);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        poll.tick().await;
        enum Signal {
            Cancel,
            Poll,
            Stream(Option<()>),
            Message(Value),
            Accepted(std::io::Result<(TcpStream, std::net::SocketAddr)>),
        }
        loop {
            let timer = poll.tick();
            tokio::pin!(timer);
            let signal = {
                let (control, cancel, done) = (
                    self.control.as_mut().expect("active forward needs a control connection"),
                    &mut self.cancel,
                    &mut self.stream_done,
                );
                let listener = self.listener.as_ref().expect("active forward is listening");
                tokio::select! {
                    biased;
                    _=wait_cancel(cancel)=>Signal::Cancel,
                    _=&mut timer, if self.port_mode==PortMode::PollOnly=>Signal::Poll,
                    stream=done.recv()=>Signal::Stream(stream),
                    message=control.next_message()=>match message {
                        Ok(message)=>Signal::Message(message),
                        Err(error)=>{
                            return Err(if *cancel.borrow() {Stop::user()} else {Stop::lost(Reason::ControlLost,error.to_string())});
                        }
                    },
                    accepted=listener.accept()=>Signal::Accepted(accepted),
                }
            };
            match signal {
                Signal::Cancel => return Err(Stop::user()),
                Signal::Poll => {
                    self.poll_ports().await?;
                    if let Some(stop)=self.port_check() {return Err(stop)}
                }
                Signal::Stream(stream) => {
                    if stream.is_none() { return Err(Stop::failed(Failure::Internal,"Forward stream registry closed")) }
                    self.connections=self.connections.saturating_sub(1);
                    self.publish();
                }
                Signal::Message(message) => {
                    if let Some(stop)=self.handle_event(&message) {return Err(stop)}
                    if self.blob.is_some()
                        && let Some(stop)=self.complete_blob().await {return Err(stop)}
                    if self.snapshot_changed()
                        && let Some(stop)=self.port_check() {return Err(stop)}
                }
                Signal::Accepted(accepted) => {
                    let (tcp,_)=accepted.map_err(|error|Stop::failed(Failure::Internal,format!("Local listener failed: {error}")))?;
                    if self.connections>=MAX_CONNECTIONS {drop(tcp);continue}
                    self.open_stream(tcp).await?;
                }
            }
        }
    }

    async fn open_stream(&mut self, tcp: TcpStream) -> Result<(), Stop> {
        self.accepted += 1;
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|value| value.as_nanos())
            .unwrap_or(0);
        let reference = format!("conn:{stamp:x}-{}", self.accepted);
        let reply = self
            .rpc(
                "data.connect",
                json!({
                    "session_id": &self.request.session,
                    "block_id": &self.tunnel,
                    "data_ref_id": reference,
                    "subprotocol": BYTES
                }),
            )
            .await?;
        if let Some(stop) = self.drain_events() {
            return Err(stop);
        }
        if let Some(stop) = self.port_check() {
            return Err(stop);
        }
        let ticket: [u8; 16] = URL_SAFE_NO_PAD
            .decode(
                reply.payload["ticket"]
                    .as_str()
                    .context("Tunnel ticket missing")
                    .map_err(|error| Stop::failed(Failure::TunnelFailed, error))?,
            )
            .map_err(|error| Stop::failed(Failure::TunnelFailed, error))?
            .try_into()
            .map_err(|_| Stop::failed(Failure::TunnelFailed, "Rex returned an invalid tunnel ticket"))?;
        let endpoint = self.request.endpoint.clone();
        let done = self.stream_done_tx.clone();
        self.streams
            .retain(|handle| !handle.is_finished());
        self.streams.push(tokio::spawn(async move {
            bridge(endpoint, ticket, tcp).await;
            let _ = done.send(());
        }));
        self.connections += 1;
        self.publish();
        Ok(())
    }

    async fn poll_ports(&mut self) -> Result<(), Stop> {
        if self.request.source_block.is_none() {
            return Ok(());
        }
        let snapshot = self.read_ports().await?;
        if self
            .snapshot
            .as_ref()
            .is_none_or(|current| snapshot.observed_seq >= current.observed_seq)
        {
            self.apply(snapshot);
        }
        if let Some(stop) = self.drain_events() {
            return Err(stop);
        }
        if let Some(stop) = self.complete_blob().await {
            return Err(stop);
        }
        Ok(())
    }

    async fn read_ports(&mut self) -> Result<Snapshot, Stop> {
        let payload = json!({
            "session_id": &self.request.session,
            "block_id": self.request.source_block.as_deref().unwrap_or(""),
            "args": {}
        });
        let reply = self
            .rpc("com.superlogical.terminal.ports", payload)
            .await?;
        Ok(Snapshot::from_value(
            &reply.payload,
            reply.seq,
        ))
    }

    fn apply(&mut self, snapshot: Snapshot) {
        crate::android_log(&format!(
            "forward ports: seq={} authority={} ports={}",
            snapshot.observed_seq,
            snapshot.authority().name(),
            snapshot.ports.len()
        ));
        self.applied_seq = snapshot.observed_seq;
        self.blob = snapshot
            .truncated
            .then_some(snapshot.blob_id)
            .flatten()
            .map(|id| PendingBlob {
                id,
                observed_seq: snapshot.observed_seq,
            });
        self.snapshot = Some(snapshot);
        self.dirty = true;
    }

    async fn complete_blob(&mut self) -> Option<Stop> {
        let pending = self.blob.clone()?;
        let Some(snapshot) = &self.snapshot else {
            return None;
        };
        if snapshot.observed_seq != pending.observed_seq {
            self.blob = None;
            return None;
        }
        let block = self.request.source_block.clone()?;
        let mut text = String::new();
        let mut offset = 0u64;
        loop {
            let payload = json!({
                "session_id": &self.request.session,
                "block_id": &block,
                "args": {"id": pending.id, "offset": offset, "max_bytes": 49152}
            });
            let reply = match self
                .rpc("com.superlogical.terminal.blob_read", payload)
                .await
            {
                Ok(reply) => reply,
                Err(stop) if stop.cancelled() => {
                    self.blob = None;
                    return Some(stop);
                }
                Err(_) => {
                    // A missing blob keeps the truncated snapshot and the forward.
                    self.blob = None;
                    return None;
                }
            };
            let chunk = reply.payload["data"].as_str().unwrap_or("");
            text.push_str(chunk);
            if reply.payload["eof"].as_bool().unwrap_or(true) {
                break;
            }
            let total = reply.payload["total"].as_u64().unwrap_or(0);
            let used = chunk.len() as u64;
            if used == 0 || text.len() as u64 >= total {
                self.blob = None;
                return None;
            }
            offset += used;
        }
        let Ok(value) = serde_json::from_str::<Value>(&text) else {
            self.blob = None;
            return None;
        };
        if self
            .snapshot
            .as_ref()
            .is_none_or(|snapshot| snapshot.observed_seq != pending.observed_seq)
        {
            return None;
        }
        let snapshot = Snapshot::from_value(&value, pending.observed_seq);
        self.apply(snapshot);
        self.blob = None;
        self.drain_events()
    }

    fn drain_events(&mut self) -> Option<Stop> {
        let events: Vec<Value> = self
            .control
            .as_mut()
            .map(|control| control.events.drain(..).collect())
            .unwrap_or_default();
        for message in events {
            if let Some(stop) = self.handle_event(&message) {
                return Some(stop);
            }
        }
        None
    }

    fn handle_event(&mut self, message: &Value) -> Option<Stop> {
        if message["type"] != "event" {
            return None;
        }
        if let Some(reason) = self.lifecycle(message) {
            return Some(if reason == Reason::TunnelClosed {
                Stop::lost(reason, lifecycle_message(reason))
            } else {
                Stop::closed(reason, lifecycle_message(reason))
            });
        }
        let (seq, payload) = self.port_event(message)?;
        if seq <= self.applied_seq {
            return None;
        }
        payload.as_object()?;
        let snapshot = Snapshot::from_value(&payload, seq);
        self.apply(snapshot);
        None
    }

    fn port_event(&self, message: &Value) -> Option<(u64, Value)> {
        let source = self.request.source_block.as_deref()?;
        let payload = &message["payload"];
        if payload["type"] != "session_event" {
            return None;
        }
        let body = &payload["payload"];
        if body["session_id"].as_str() != Some(self.request.session.as_str())
        {
            return None;
        }
        let event = &body["event"];
        if event["type"] != "block_event" {
            return None;
        }
        let block = &event["payload"];
        if block["block_id"].as_str() != Some(source) || block["name"].as_str() != Some("ports_changed")
        {
            return None;
        }
        Some((message["seq"].as_u64()?, block["payload"].clone()))
    }

    fn lifecycle(&self, message: &Value) -> Option<Reason> {
        let payload = &message["payload"];
        if payload["type"] == "session_destroyed"
            && payload["payload"]["session_id"].as_str() == Some(self.request.session.as_str())
        {
            return Some(Reason::SessionClosed);
        }
        if payload["type"] != "session_event" {
            return None;
        }
        let body = &payload["payload"];
        if body["session_id"].as_str() != Some(self.request.session.as_str())
        {
            return None;
        }
        let event = &body["event"];
        let inner = &event["payload"];
        let source = self.request.source_block.as_deref();
        match event["type"].as_str() {
            Some("block_closed") => {
                let id = inner["block_id"]
                    .as_str()
                    .or_else(|| event["block_id"].as_str())
                    .unwrap_or("");
                if source.is_some_and(|source| source == id) {
                    Some(Reason::SourceBlockClosed)
                } else if id == self.tunnel && !self.tunnel.is_empty() {
                    Some(Reason::TunnelClosed)
                } else {
                    None
                }
            }
            Some("block_event") => {
                if inner["block_id"].as_str().is_some_and(|id| source == Some(id))
                    && inner["creator_name"] == "com.superlogical.terminal"
                    && inner["name"].as_str() == Some("child_exited")
                {
                    Some(Reason::SourceProgramExited)
                } else {
                    None
                }
            }
            _ => None,
        }
    }

    fn snapshot_changed(&mut self) -> bool {
        let changed = self.dirty;
        self.dirty = false;
        changed
    }

    fn port_check(&self) -> Option<Stop> {
        if self.port_mode == PortMode::Disabled || self.phase == Phase::Resolving {
            return None;
        }
        let resolved = self.resolved.as_ref()?;
        let snapshot = self.snapshot.as_ref()?;
        if !snapshot.authority().complete() {
            return None;
        }
        if snapshot.has(resolved.port, &resolved.host) {
            return None;
        }
        if snapshot.entries(resolved.port).is_empty() {
            Some(Stop::closed(
                Reason::PortGone,
                format!(
                    "Port {} closed, so the forward stopped",
                    resolved.port
                ),
            ))
        } else {
            Some(Stop::closed(
                Reason::PortAddressChanged,
                format!(
                    "Port {} moved to another address, so the forward stopped",
                    resolved.port
                ),
            ))
        }
    }

    async fn rpc(&mut self, method: &str, payload: Value) -> Result<CallReply, Stop> {
        let Worker {
            control, cancel, ..
        } = self;
        let control = control.as_mut().ok_or_else(|| {
            Stop::failed(Failure::Internal, "The forward control connection is closed")
        })?;
        let call = tokio::time::timeout(RPC_TIMEOUT, control.call_with_seq(method, payload));
        tokio::pin!(call);
        tokio::select! {
            biased;
            _=wait_cancel(cancel)=>Err(Stop::user()),
            reply=&mut call=>match reply {
                Err(_)=>Err(Stop::failed(Failure::ControlFailed,format!("{method} timed out"))),
                Ok(Err(error))=>Err(Stop::failed(Failure::ControlFailed,error)),
                Ok(Ok(reply))=>Ok(reply),
            },
        }
    }

    fn set_phase(&mut self, phase: Phase) {
        self.phase = phase;
        self.publish();
    }

    fn publish(&mut self) {
        self.revision += 1;
        let value = json!({
            "revision": self.revision,
            "phase": self.phase.name(),
            "outcome": self.outcome(),
            "reason": self.reason.map(Reason::name).unwrap_or(""),
            "failure": self.failure.map(Failure::name).unwrap_or(""),
            "error": self.error,
            "authority": self.snapshot.as_ref().map(Snapshot::authority).unwrap_or(PortAuthority::Unknown).name(),
            "portMode": self.port_mode.name(),
            "targetHost": self.resolved.as_ref().map(|r| r.host.as_str()).unwrap_or(""),
            "targetPort": self.resolved.as_ref().map(|r| r.port).unwrap_or(0),
            "localPort": self.local_port,
            "localUrl": if self.local_port==0 {String::new()} else {format!("http://127.0.0.1:{}",self.local_port)},
            "tunnelBlock": self.tunnel,
            "connections": self.connections,
            "choices": self.choices.iter().map(Choice::json).collect::<Vec<_>>(),
        });
        publish(&self.shared, &value);
    }

    fn outcome(&self) -> Option<&'static str> {
        match self.phase {
            Phase::Closed => Some("closed"),
            Phase::Failed => Some("failed"),
            _ => None,
        }
    }

    async fn finish(mut self, stop: Stop) {
        if self.phase.terminal() {
            return;
        }
        self.reason = Some(stop.reason);
        self.failure = stop.failure;
        self.error = stop.error;
        self.set_phase(Phase::Stopping);
        self.listener = None;
        for stream in self.streams.drain(..) {
            stream.abort();
        }
        self.connections = 0;
        if let (Some(control), tunnel) = (self.control.as_mut(), &self.tunnel)
            && !tunnel.is_empty()
        {
            let close = control.call(
                "block.close",
                json!({"session_id": &self.request.session, "block_id": tunnel}),
            );
            let _ = tokio::time::timeout(CLEANUP_TIMEOUT, close).await;
        }
        if let Some(mut control) = self.control.take() {
            let _ = tokio::time::timeout(CLEANUP_TIMEOUT, control.close()).await;
        }
        self.phase = if stop.failure.is_some() {
            Phase::Failed
        } else {
            Phase::Closed
        };
        self.publish();
    }
}

fn lifecycle_message(reason: Reason) -> &'static str {
    match reason {
        Reason::SourceBlockClosed => "The terminal block closed",
        Reason::SourceProgramExited => "The terminal program exited",
        Reason::SessionClosed => "The session closed",
        Reason::TunnelClosed => "The tunnel block closed",
        Reason::ControlLost => "The Rex connection was lost",
        Reason::PortGone => "The port closed",
        Reason::PortAddressChanged => "The port moved",
        _ => "The forward stopped",
    }
}

async fn wait_cancel(cancel: &mut watch::Receiver<bool>) {
    let _ = cancel.wait_for(|&c| c).await;
}
fn cancelled(cancel: &mut watch::Receiver<bool>) -> bool {
    *cancel.borrow()
}

/// Bridge one loopback TCP connection to one `rex.data.bytes.v1` stream.
///
/// A TCP EOF becomes an empty binary frame; an empty frame or normal stream
/// close becomes a TCP write shutdown. An error drops both sides.
async fn bridge(endpoint: Endpoint, ticket: [u8; 16], tcp: TcpStream) {
    if let Err(error) = bridge_inner(endpoint, ticket, tcp).await {
        crate::android_log(&format!("port forward stream ended: {error}"));
    }
}

async fn bridge_inner(endpoint: Endpoint, ticket: [u8; 16], tcp: TcpStream) -> Result<()> {
    let mut ws = endpoint.open("/api/data", BYTES).await?;
    let mut hello = [0u8; 17];
    hello[0] = 1;
    hello[1..].copy_from_slice(&ticket);
    ws.send(Message::Binary(hello.to_vec().into())).await?;
    let welcome = tokio::time::timeout(HANDSHAKE_TIMEOUT, transport::receive(&mut ws))
        .await
        .context("tunnel handshake timed out")??;
    let Message::Binary(bytes) = welcome else {
        bail!("expected a binary tunnel welcome");
    };
    ensure!(
        bytes.len() == 32 && bytes[0] == 0x81 && bytes[17..20] == [0, 0, 0],
        "tunnel data handshake rejected"
    );
    let (mut sink, mut stream) = StreamExt::split::<Message>(&mut *ws);
    let (mut reader, mut writer) = tcp.into_split();
    let mut buffer = vec![0u8; 16384];
    let mut local_closed = false;
    let mut remote_closed = false;
    while !(local_closed && remote_closed) {
        tokio::select! {
            biased;
            frame=stream.next(), if !remote_closed=>match frame {
                None=>{remote_closed=true;let _=writer.shutdown().await;}
                Some(Err(error))=>return Err(error.into()),
                Some(Ok(Message::Binary(data)))=>{
                    if data.is_empty() {remote_closed=true;let _=writer.shutdown().await;}
                    else if data.len()>MAX_STREAM_CHUNK {bail!("tunnel stream chunk too large")}
                    else {writer.write_all(&data).await?;}
                }
                Some(Ok(Message::Close(_)))=>{remote_closed=true;let _=writer.shutdown().await;}
                Some(Ok(_))=>(),
            },
            read=reader.read(&mut buffer), if !local_closed=>match read {
                Ok(0)=>{local_closed=true;sink.send(Message::Binary(Vec::new().into())).await?;}
                Ok(count)=>sink.send(Message::Binary(buffer[..count].to_vec().into())).await?,
                Err(error)=>return Err(error.into()),
            },
        }
    }
    let _ = tokio::time::timeout(
        CLEANUP_TIMEOUT,
        sink.send(Message::Close(None)),
    )
    .await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ports(value: Value, seq: u64) -> Snapshot {
        Snapshot::from_value(&value, seq)
    }

    #[test]
    fn authority_follows_the_documented_order() {
        assert_eq!(
            ports(json!({"status":"ok","ports":[]}), 1).authority(),
            PortAuthority::Complete
        );
        assert_eq!(
            ports(json!({"status":"ok","truncated":true,"id":7,"ports":[]}), 1).authority(),
            PortAuthority::Truncated
        );
        assert_eq!(
            ports(json!({"status":"unsupported","ports":[]}), 1).authority(),
            PortAuthority::Unsupported
        );
        assert_eq!(
            ports(json!({"status":"partial","ports":[]}), 1).authority(),
            PortAuthority::Partial
        );
        assert_eq!(
            ports(json!({"status":"future","ports":[]}), 1).authority(),
            PortAuthority::Partial
        );
    }

    #[test]
    fn address_preference_is_loopback_first() {
        let snapshot = ports(
            json!({"status":"ok","ports":[
                {"port":5173,"address":"0.0.0.0","protocol":"tcp"},
                {"port":5173,"address":"::1","protocol":"tcp"},
                {"port":5173,"address":"192.168.1.4","protocol":"tcp"}
            ],"processes":[]}),
            3,
        );
        let entries = snapshot.entries(5173);
        assert_eq!(
            preferred_address(&entries, Some("localhost")),
            Some("127.0.0.1".into())
        );
        assert_eq!(
            preferred_address(&entries, Some("192.168.1.4")),
            Some("192.168.1.4".into())
        );
        assert!(snapshot.has(5173, "::1"));
        assert!(!snapshot.has(5173, "127.0.0.2"));
    }

    #[test]
    fn only_tcp_entries_are_forwardable() {
        let snapshot = ports(
            json!({"status":"ok","ports":[
                {"port":53,"address":"127.0.0.1","protocol":"udp"},
                {"port":8080,"address":"127.0.0.1","protocol":"tcp"}
            ],"processes":[]}),
            1,
        );
        assert_eq!(snapshot.distinct_ports(), vec![8080]);
    }

    #[test]
    fn requests_need_a_block_or_a_port() {
        let timeout = Duration::from_secs(3);
        assert!(
            Request::parse(
                &json!({"endpoint":"http://127.0.0.1:7297","session":"session:x"}),
                timeout
            )
            .is_err()
        );
        let request = Request::parse(
            &json!({"endpoint":"http://127.0.0.1:7297","session":"session:x","targetPort":5173}),
            timeout,
        )
        .unwrap();
        assert_eq!(request.target_port, Some(5173));
    }

    #[test]
    fn wildcard_and_localhost_normalize_to_loopback() {
        assert_eq!(normalize_address("0.0.0.0"), "127.0.0.1");
        assert_eq!(normalize_address("::"), "::1");
        assert_eq!(normalize_address("[::1]"), "::1");
        assert_eq!(normalize_address("localhost"), "127.0.0.1");
        assert!(same_address("::1", "[::1]"));
    }
}
