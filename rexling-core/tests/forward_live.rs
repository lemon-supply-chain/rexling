//! Manual end-to-end check against an installed Rex server.
//!
//! Run with a reachable server and python3:
//! `REX_SERVER=unix:///.../server.sock cargo test --test forward_live -- --ignored`
//!
//! It creates a disposable session, serves a directory over HTTP, forwards it
//! to a phone-style loopback listener, fetches it, stops the server process,
//! and asserts that the forward closes from a complete `ports_changed` event.

use rexling_core::{
    control::Control,
    forward,
    transport::Endpoint,
};
use serde_json::{Value, json};
use std::{
    process::Command,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{mpsc, watch},
};

fn rex(arguments: &[&str]) -> Value {
    let binary = std::env::var("REX_BIN").unwrap_or_else(|_| {
        "/Applications/Rex Beta.app/Contents/Helpers/Rex Server.rex/Contents/MacOS/rex".into()
    });
    let output = Command::new(binary)
        .args(arguments)
        .output()
        .expect("run the Rex CLI");
    assert!(
        output.status.success(),
        "rex {:?} failed: {}",
        arguments,
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).expect("Rex JSON output")
}

fn free_port() -> u16 {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
    listener.local_addr().unwrap().port()
}

async fn wait_for(shared: &forward::Shared, timeout: Duration, predicate: impl Fn(&Value) -> bool) -> Value {
    let started = Instant::now();
    loop {
        let state = forward::snapshot(shared);
        if predicate(&state) {
            return state;
        }
        assert!(
            started.elapsed() < timeout,
            "timed out waiting for forward state: {state}"
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

async fn ports_for(control: &mut Control,session: &str,block: &str) -> Value {
    control
        .call(
            "com.superlogical.terminal.ports",
            json!({"session_id":session,"block_id":block,"args":{}}),
        )
        .await
        .unwrap()
}

fn kill_session(session: &str) {
    let binary = std::env::var("REX_BIN").unwrap_or_else(|_| {
        "/Applications/Rex Beta.app/Contents/Helpers/Rex Server.rex/Contents/MacOS/rex".into()
    });
    let _ = Command::new(binary).arg("kill").arg(session).status();
}

#[tokio::test(flavor = "current_thread")]
#[ignore = "needs a running Rex server and python3"]
async fn forward_serves_http_and_closes_when_the_port_goes_away() {
    let server = std::env::var("REX_SERVER").expect("REX_SERVER");
    let endpoint = Endpoint::new(&server, Duration::from_secs(10)).unwrap();
    let session = rex(&["session", "new", "rexling-fwd-test", "--json"]);
    let session_id = session["session_id"].as_str().unwrap().to_string();
    let port = free_port();
    // The terminal child stays alive after the server dies, so the forward
    // must close from the port event rather than from the block lifecycle.
    let script = format!(
        "python3 -m http.server {port} --bind 127.0.0.1 & child=$!; sleep 10; kill $child; echo server stopped; sleep 600"
    );
    let block = rex(&["run", "-s", &session_id, "--json", "--", "bash", "-c", &script]);
    let block_id = block["block_ids"][0].as_str().unwrap().to_string();

    let mut control = Control::connect_forward(endpoint.clone()).await.unwrap();
    control.attach(&session_id).await.unwrap();
    let mut observed = false;
    for _ in 0..75 {
        let reply = control
            .call(
                "com.superlogical.terminal.ports",
                json!({"session_id":session_id,"block_id":block_id,"args":{}}),
            )
            .await
            .unwrap();
        let entry = reply["ports"]
            .as_array()
            .and_then(|ports| ports.iter().find(|entry| entry["port"] == port));
        if entry.is_some() {
            observed = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    assert!(observed, "the HTTP server never started listening");

    let shared = forward::shared();
    let (cancel, cancel_rx) = watch::channel(false);
    let (_select, select_rx) = mpsc::unbounded_channel();
    let worker = tokio::spawn(forward::run(
        forward::Request {
            endpoint: endpoint.clone(),
            session: session_id.clone(),
            source_block: Some(block_id.clone()),
            target_host: Some("127.0.0.1".into()),
            target_port: Some(port),
            default_port: None,
            label_tag: None,
        },
        shared.clone(),
        cancel_rx,
        select_rx,
    ));
    let active = wait_for(&shared, Duration::from_secs(20), |state| {
        state["phase"] == "active" || state["phase"] == "failed"
    })
    .await;
    assert_eq!(active["phase"], "active", "{active}");
    let local = active["localPort"].as_u64().unwrap() as u16;

    let mut tcp = tokio::net::TcpStream::connect(("127.0.0.1", local))
        .await
        .expect("connect to the forwarded port");
    tcp.write_all(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .await
        .unwrap();
    let mut response = Vec::new();
    tcp.read_to_end(&mut response).await.unwrap();
    let response = String::from_utf8_lossy(&response);
    assert!(response.starts_with("HTTP/1.0 200 OK"), "{response}");
    drop(tcp);
    let released = wait_for(&shared, Duration::from_secs(5), |state| {
        state["connections"] == 0
    })
    .await;
    assert_eq!(released["connections"], 0, "{released}");

    let closed = wait_for(&shared, Duration::from_secs(90), |state| {
        state["phase"] == "closed" || state["phase"] == "failed"
    })
    .await;
    assert_eq!(closed["phase"], "closed", "{closed}");
    assert_eq!(closed["reason"], "port_gone", "{closed}");
    let _ = cancel.send(true);
    let _ = worker.await;
    kill_session(&session_id);
}

#[tokio::test(flavor = "current_thread")]
#[ignore = "needs a running Rex server and python3"]
async fn auto_selection_asks_for_a_port_and_accepts_the_pick() {
    let server = std::env::var("REX_SERVER").expect("REX_SERVER");
    let endpoint = Endpoint::new(&server, Duration::from_secs(10)).unwrap();
    let session = rex(&["session", "new", "rexling-fwd-select", "--json"]);
    let session_id = session["session_id"].as_str().unwrap().to_string();
    let first = free_port();
    let mut second = free_port();
    while second == first {
        second = free_port();
    }
    let script = format!(
        "python3 -m http.server {first} --bind 127.0.0.1 & python3 -m http.server {second} --bind 127.0.0.1 & sleep 120"
    );
    let block = rex(&["run", "-s", &session_id, "--json", "--", "bash", "-c", &script]);
    let block_id = block["block_ids"][0].as_str().unwrap().to_string();

    let mut control = Control::connect_forward(endpoint.clone()).await.unwrap();
    control.attach(&session_id).await.unwrap();
    for _ in 0..75 {
        let reply = ports_for(&mut control, &session_id, &block_id).await;
        if reply["ports"].as_array().is_some_and(|ports| {
            [first, second].iter().all(|wanted| {
                ports.iter().any(|entry| entry["port"] == *wanted)
            })
        }) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }

    let shared = forward::shared();
    let (cancel, cancel_rx) = watch::channel(false);
    let (select, select_rx) = mpsc::unbounded_channel();
    let worker = tokio::spawn(forward::run(
        forward::Request {
            endpoint: endpoint.clone(),
            session: session_id.clone(),
            source_block: Some(block_id),
            target_host: None,
            target_port: None,
            default_port: None,
            label_tag: None,
        },
        shared.clone(),
        cancel_rx,
        select_rx,
    ));
    let waiting = wait_for(&shared, Duration::from_secs(20), |state| {
        state["phase"] == "awaiting_selection" || state["phase"] == "failed"
    })
    .await;
    assert_eq!(waiting["phase"], "awaiting_selection", "{waiting}");
    let choices = waiting["choices"].as_array().unwrap();
    assert_eq!(choices.len(), 2, "{waiting}");

    select.send(first).unwrap();
    let active = wait_for(&shared, Duration::from_secs(20), |state| {
        state["phase"] == "active" || state["phase"] == "failed"
    })
    .await;
    assert_eq!(active["phase"], "active", "{active}");
    assert_eq!(active["targetPort"].as_u64(), Some(first as u64), "{active}");
    let _ = cancel.send(true);
    let _ = worker.await;
    kill_session(&session_id);
}

#[tokio::test(flavor = "current_thread")]
#[ignore = "needs a running Rex server and python3"]
async fn closing_the_session_stops_its_forwards() {
    let server = std::env::var("REX_SERVER").expect("REX_SERVER");
    let endpoint = Endpoint::new(&server, Duration::from_secs(10)).unwrap();
    let session = rex(&["session", "new", "rexling-fwd-lifecycle", "--json"]);
    let session_id = session["session_id"].as_str().unwrap().to_string();
    let port = free_port();
    let script = format!("python3 -m http.server {port} --bind 127.0.0.1 & sleep 120");
    let block = rex(&["run", "-s", &session_id, "--json", "--", "bash", "-c", &script]);
    let block_id = block["block_ids"][0].as_str().unwrap().to_string();
    let mut control = Control::connect_forward(endpoint.clone()).await.unwrap();
    control.attach(&session_id).await.unwrap();
    for _ in 0..75 {
        let reply = ports_for(&mut control, &session_id, &block_id).await;
        if reply["ports"].as_array().is_some_and(|ports| ports.iter().any(|entry| entry["port"] == port)) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let shared = forward::shared();
    let (cancel, cancel_rx) = watch::channel(false);
    let (_select, select_rx) = mpsc::unbounded_channel();
    let worker = tokio::spawn(forward::run(
        forward::Request {
            endpoint: endpoint.clone(),
            session: session_id.clone(),
            source_block: Some(block_id),
            target_host: Some("127.0.0.1".into()),
            target_port: Some(port),
            default_port: None,
            label_tag: None,
        },
        shared.clone(),
        cancel_rx,
        select_rx,
    ));
    wait_for(&shared, Duration::from_secs(20), |state| {
        state["phase"] == "active" || state["phase"] == "failed"
    })
    .await;
    control.close().await.ok();
    kill_session(&session_id);
    let closed = wait_for(&shared, Duration::from_secs(30), |state| {
        state["phase"] == "closed" || state["phase"] == "failed"
    })
    .await;
    assert_eq!(closed["phase"], "closed", "{closed}");
    assert_ne!(closed["reason"], "", "{closed}");
    let _ = cancel.send(true);
    let _ = worker.await;
}
