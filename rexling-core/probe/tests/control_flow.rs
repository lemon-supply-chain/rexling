//! Exercise control demultiplexing against a small protocol peer, not a Rex process.
//! tungstenite's upgrade callback returns a large ErrorResponse; the size is fixed by the API.
#![allow(clippy::result_large_err)]
use futures_util::{SinkExt, StreamExt};
use rex_probe::{
    control::{Control, Trace},
    transport::{CONTROL, Endpoint},
};
use serde_json::{Value, json};
use std::time::Duration;
use tokio_tungstenite::{
    accept_hdr_async,
    tungstenite::{
        Message,
        handshake::server::{Request, Response},
    },
};

async fn peer(gap: bool) -> (Endpoint, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut ws = accept_hdr_async(stream, |request: &Request, mut response: Response| {
            assert_eq!(request.uri().path(), "/api/control");
            assert_eq!(request.headers()["Sec-WebSocket-Protocol"], CONTROL);
            assert!(!request.headers().contains_key("Authorization"));
            assert!(!request.headers().contains_key("Tailscale-User-Login"));
            response
                .headers_mut()
                .insert("Sec-WebSocket-Protocol", CONTROL.parse().unwrap());
            Ok(response)
        })
        .await
        .unwrap();
        let hello = ws.next().await.unwrap().unwrap();
        let hello: Value = serde_json::from_str(hello.to_text().unwrap()).unwrap();
        assert_eq!(hello["type"], "hello");
        assert_eq!(hello["api_min_revision"], 7);
        ws.send(Message::Text(json!({"type":"welcome","protocol":CONTROL,"api_revision":7,"limits":{"max_message_bytes":65536},"client":{"id":"client:test","reconnect_token":"secret"}}).to_string().into())).await.unwrap();
        let request: Value =
            serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        ws.send(Message::Ping(vec![1, 2].into())).await.unwrap();
        ws.send(Message::Text(
            json!({"type":"future_extension"}).to_string().into(),
        ))
        .await
        .unwrap();
        ws.send(Message::Text(
            json!({"type":"event","seq":if gap {2} else {1},"payload":{"type":"session_created"}})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
        if !gap {
            ws.send(Message::Text(
                json!({"type":"response","id":request["id"],"seq":1,"payload":{"sessions":[]}})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
            loop {
                let frame = ws.next().await.unwrap().unwrap();
                if frame.is_pong() {
                    continue;
                }
                if frame.is_close() {
                    break;
                }
                let request: Value = serde_json::from_str(frame.to_text().unwrap()).unwrap();
                ws.send(Message::Text(json!({"type":"response","id":request["id"],"seq":1,"error":{"code":"not_found","status":404,"message":"missing"}}).to_string().into())).await.unwrap();
            }
        }
    });
    (
        Endpoint::new(&format!("ws://{address}"), Duration::from_secs(2)).unwrap(),
        task,
    )
}

#[tokio::test]
async fn events_and_ping_can_precede_a_response() {
    let (endpoint, task) = peer(false).await;
    let mut client = Control::connect(endpoint, None, Trace::default())
        .await
        .unwrap();
    assert_eq!(
        client.call("session.list", json!({})).await.unwrap(),
        json!({"sessions":[]})
    );
    assert_eq!(client.events.len(), 1);
    assert_eq!(client.last_seq(), 1);
    let error = client
        .call("missing", json!({}))
        .await
        .unwrap_err()
        .to_string();
    assert!(error.contains("not_found") && error.contains("404"));
    client.close().await.unwrap();
    task.await.unwrap();
}

#[tokio::test]
async fn sequence_gaps_require_resynchronization() {
    let (endpoint, task) = peer(true).await;
    let mut client = Control::connect(endpoint, None, Trace::default())
        .await
        .unwrap();
    let error = client
        .call("session.list", json!({}))
        .await
        .unwrap_err()
        .to_string();
    assert!(error.contains("sequence gap"));
    task.await.unwrap();
}
