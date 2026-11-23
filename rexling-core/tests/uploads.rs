//! Loopback uploads: real WebSocket control/data framing, no production hosts.
//! tungstenite's upgrade callback returns a large ErrorResponse; the size is fixed by the API.
#![allow(clippy::result_large_err)]
use futures_util::{SinkExt, StreamExt};
use rexling_core::{
    transport::{CONTROL, Endpoint},
    upload::{self, BYTES, Phase, Request},
};
use serde_json::{Value, json};
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;
use tokio_tungstenite::{
    WebSocketStream, accept_hdr_async,
    tungstenite::{
        Message,
        handshake::server::{Request as Upgrade, Response},
        protocol::{CloseFrame, frame::coding::CloseCode},
    },
};
type Ws = WebSocketStream<tokio::net::TcpStream>;
async fn accept(listener: &tokio::net::TcpListener, protocol: &str) -> Ws {
    let (tcp, _) = listener.accept().await.unwrap();
    let protocol = protocol.to_string();
    accept_hdr_async(tcp, move |request: &Upgrade, mut response: Response| {
        assert_eq!(request.headers()["Sec-WebSocket-Protocol"], protocol);
        response
            .headers_mut()
            .insert("Sec-WebSocket-Protocol", protocol.parse().unwrap());
        Ok(response)
    })
    .await
    .unwrap()
}
async fn request(ws: &mut Ws) -> Value {
    serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap()
}
async fn reply(ws: &mut Ws, r: &Value, payload: Value) {
    ws.send(Message::Text(
        json!({"type":"response","id":r["id"],"payload":payload})
            .to_string()
            .into(),
    ))
    .await
    .unwrap();
}
async fn run(mode: &'static str) -> (Phase, String, Vec<(Phase, u64, u64)>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = Endpoint::new(
        &format!("http://{}", listener.local_addr().unwrap()),
        Duration::from_secs(2),
    )
    .unwrap();
    static ID: AtomicU64 = AtomicU64::new(1);
    let path = std::env::temp_dir().join(format!(
        "rexling-upload-test-{}-{}",
        std::process::id(),
        ID.fetch_add(1, Ordering::Relaxed)
    ));
    let bytes = if mode == "slow" { 512 * 1024 } else { 70000 };
    let expected = (0..bytes).map(|i| (i % 251) as u8).collect::<Vec<_>>();
    std::fs::write(&path, &expected).unwrap();
    let (cancel_tx, cancel_rx) = oneshot::channel();
    let (started_tx, started_rx) = oneshot::channel();
    let received_count = Arc::new(AtomicU64::new(0));
    let server_received = received_count.clone();
    let server = tokio::spawn(async move {
        let mut control = accept(&listener, CONTROL).await;
        let hello = request(&mut control).await;
        assert_eq!(hello["api_revision"], 11);
        assert_eq!(hello["api_min_revision"], 7);
        control.send(Message::Text(json!({"type":"welcome","protocol":CONTROL,"api_revision":if mode=="old" {8}else{11},"limits":{"max_message_bytes":65536}}).to_string().into())).await.unwrap();
        if mode == "old" {
            return;
        }
        let attach = request(&mut control).await;
        assert_eq!(attach["method"], "session.attach");
        assert_eq!(attach["payload"]["session_id"], "session:original");
        reply(&mut control, &attach, json!({"view":{}})).await;
        let begin = request(&mut control).await;
        assert_eq!(begin["method"], "com.superlogical.terminal.upload_begin");
        assert_eq!(begin["payload"]["block_id"], "block:original");
        assert_eq!(begin["payload"]["args"]["size"], expected.len());
        if mode == "missing" || mode == "limit" {
            control.send(Message::Text(json!({"type":"response","id":begin["id"],"error":{"code":if mode=="missing" {"method_not_found"}else{"too_large"},"message":"fixture rejection"}}).to_string().into())).await.unwrap();
            return;
        }
        reply(
            &mut control,
            &begin,
            json!({"ticket":"AAAAAAAAAAAAAAAAAAAAAA","expires_in_ms":60000}),
        )
        .await;
        let mut stream = accept(&listener, BYTES).await;
        let hello = stream.next().await.unwrap().unwrap().into_data();
        assert_eq!(
            &hello[..],
            &[1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        );
        if mode == "cancel" {
            let _ = started_tx.send(());
            let close = stream.next().await.unwrap().unwrap();
            assert!(matches!(close,Message::Close(Some(c)) if c.code==CloseCode::Normal));
            return;
        }
        let mut welcome = vec![0; 32];
        welcome[0] = 0x81;
        welcome[24] = 1;
        stream.send(Message::Binary(welcome.into())).await.unwrap();
        let mut received = Vec::new();
        loop {
            let message = stream.next().await.unwrap().unwrap();
            match message {
                Message::Ping(_) => {
                    stream.flush().await.unwrap();
                }
                Message::Binary(bytes) => {
                    if bytes.is_empty() {
                        break;
                    }
                    assert!(bytes.len() <= 16384);
                    received.extend_from_slice(&bytes);
                    server_received.store(received.len() as u64, Ordering::SeqCst);
                    if mode == "slow" {
                        tokio::time::sleep(Duration::from_millis(15)).await;
                    }
                }
                other => panic!("Unexpected upload message: {other:?}"),
            }
        }
        assert_eq!(received, expected);
        if mode == "lost" {
            return;
        }
        let code = if mode == "rejected" {
            CloseCode::Library(4006)
        } else {
            CloseCode::Normal
        };
        stream
            .send(Message::Close(Some(CloseFrame {
                code,
                reason: "".into(),
            })))
            .await
            .unwrap();
    });
    let events = Arc::new(Mutex::new(Vec::new()));
    let observed = events.clone();
    let cancellation = tokio::spawn(async move {
        if mode == "cancel" {
            started_rx.await.unwrap();
            cancel_tx.send(()).unwrap();
        } else {
            std::future::pending::<()>().await;
        }
    });
    let result = upload::upload(
        Request {
            endpoint,
            session: "session:original",
            block: "block:original",
            path: &path,
            name: "image.png",
            reference: "upload:00000000-0000-4000-8000-000000000001",
        },
        cancel_rx,
        move |p, s, t| {
            if p == Phase::Sending {
                assert!(
                    s <= received_count.load(Ordering::SeqCst),
                    "Progress cannot precede remote receipt"
                );
            }
            observed.lock().unwrap().push((p, s, t));
        },
    )
    .await;
    server.await.unwrap();
    cancellation.abort();
    std::fs::remove_file(path).unwrap();
    let events = events.lock().unwrap().clone();
    (result.0, result.1, events)
}
#[tokio::test]
async fn streams_chunks_half_closes_and_waits_for_server_confirmation() {
    let (phase, error, events) = run("ok").await;
    assert_eq!(phase, Phase::Done, "{error}");
    assert!(events.iter().any(|e| e.0 == Phase::Finishing));
    assert_eq!(events.last(), Some(&(Phase::Done, 70000, 70000)));
}
#[tokio::test]
async fn older_servers_are_unsupported_before_reservation() {
    assert_eq!(run("old").await.0, Phase::Unsupported);
}
#[tokio::test]
async fn missing_method_is_the_only_rpc_fallback() {
    assert_eq!(run("missing").await.0, Phase::Unsupported);
    assert_eq!(run("limit").await.0, Phase::Failed);
}
#[tokio::test]
async fn lost_confirmation_is_uncertain_not_retryable() {
    assert_eq!(run("lost").await.0, Phase::Uncertain);
}
#[tokio::test]
async fn failed_final_close_never_reports_success() {
    assert_eq!(run("rejected").await.0, Phase::Uncertain);
}
#[tokio::test]
async fn cancellation_closes_stream_while_waiting_for_welcome() {
    assert_eq!(run("cancel").await.0, Phase::Canceled);
}

#[tokio::test]
async fn slow_receiver_reports_multiple_confirmed_progress_updates_before_finishing() {
    let (phase, error, events) = run("slow").await;
    assert_eq!(phase, Phase::Done, "{error}");
    let progress = events
        .iter()
        .filter(|e| e.0 == Phase::Sending && e.1 > 0 && e.1 < e.2)
        .collect::<Vec<_>>();
    assert!(
        progress.len() >= 4,
        "Expected intermediate confirmed progress: {events:?}"
    );
    assert!(progress.windows(2).all(|p| p[1].1 > p[0].1));
    let finishing = events.iter().position(|e| e.0 == Phase::Finishing).unwrap();
    assert!(
        events[..finishing]
            .iter()
            .any(|e| e.0 == Phase::Sending && e.1 == 512 * 1024)
    );
}
