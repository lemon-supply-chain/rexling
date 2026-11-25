//! Loopback protocol peers only; no production server or network settings.
//! tungstenite's upgrade callback returns a large ErrorResponse; the size is fixed by the API.
#![allow(clippy::result_large_err)]
use anyhow::Context;
use futures_util::{SinkExt, StreamExt};
use rexling_core::{
    control::{Control, Trace},
    transport::{CONTROL, Endpoint},
};
use serde_json::{Value, json};
use std::{future::Future, time::Duration};
use tokio_tungstenite::{
    WebSocketStream, accept_hdr_async,
    tungstenite::{
        Message,
        handshake::server::{Request, Response},
    },
};

type Socket = WebSocketStream<tokio::net::TcpStream>;
async fn peer<F, Fut>(timeout: Duration, handler: F) -> (Endpoint, tokio::task::JoinHandle<()>)
where
    F: FnOnce(Socket) -> Fut + Send + 'static,
    Fut: Future<Output = ()> + Send + 'static,
{
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut ws = accept_hdr_async(stream, |request: &Request, mut response: Response| {
            assert_eq!(request.headers()["Sec-WebSocket-Protocol"], CONTROL);
            response
                .headers_mut()
                .insert("Sec-WebSocket-Protocol", CONTROL.parse().unwrap());
            Ok(response)
        })
        .await
        .unwrap();
        let hello: Value =
            serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
        assert_eq!(hello["type"], "hello");
        ws.send(Message::Text(json!({"type":"welcome","protocol":CONTROL,"api_revision":8,"limits":{"max_message_bytes":65536},"client":{"id":"client:test","reconnect_token":"secret"}}).to_string().into())).await.unwrap();
        handler(ws).await;
    });
    (
        Endpoint::new(&format!("http://{address}"), timeout).unwrap(),
        task,
    )
}
async fn request(ws: &mut Socket) -> Value {
    loop {
        let message = ws.next().await.unwrap().unwrap();
        if message.is_text() {
            return serde_json::from_str(message.to_text().unwrap()).unwrap();
        }
    }
}

#[tokio::test]
async fn response_ids_demultiplex_outstanding_requests_without_losing_events() {
    let (endpoint, server) = peer(Duration::from_secs(2), |mut ws| async move {
        let a = request(&mut ws).await;
        let b = request(&mut ws).await;
        ws.send(Message::Ping(vec![1].into())).await.unwrap();
        ws.send(Message::Text(
            json!({"type":"event","seq":1,"payload":{"type":"session_changed"}})
                .to_string()
                .into(),
        ))
        .await
        .unwrap();
        for item in [b, a] {
            ws.send(Message::Text(
                json!({"type":"response","id":item["id"],"payload":item["payload"]})
                    .to_string()
                    .into(),
            ))
            .await
            .unwrap();
        }
        while ws
            .next()
            .await
            .is_some_and(|m| m.is_ok_and(|m| !m.is_close()))
        {}
    })
    .await;
    let mut control = Control::connect(endpoint, None, Trace::default())
        .await
        .unwrap();
    let first = control.start_call("one", json!({"value":1})).await.unwrap();
    let second = control.start_call("two", json!({"value":2})).await.unwrap();
    assert_eq!(control.next_message().await.unwrap()["type"], "event");
    assert_eq!(control.last_seq(), 1);
    control.next_message().await.unwrap();
    control.next_message().await.unwrap();
    assert_eq!(first.receive().await.unwrap()["value"], 1);
    assert_eq!(second.receive().await.unwrap()["value"], 2);
    control.close().await.unwrap();
    server.await.unwrap();
}

#[tokio::test]
async fn ordinary_calls_can_dispatch_other_pending_replies_and_keep_rpc_error_codes() {
    let (endpoint,server)=peer(Duration::from_secs(2),|mut ws|async move {
        let a=request(&mut ws).await;let b=request(&mut ws).await;
        ws.send(Message::Text(json!({"type":"response","id":a["id"],"error":{"code":"not_owner","message":"Control changed","status":403}}).to_string().into())).await.unwrap();
        ws.send(Message::Text(json!({"type":"event","seq":1,"payload":{"type":"session_changed"}}).to_string().into())).await.unwrap();
        ws.send(Message::Text(json!({"type":"response","id":b["id"],"payload":{"ok":true}}).to_string().into())).await.unwrap();
        while ws.next().await.is_some_and(|m|m.is_ok_and(|m|!m.is_close())) {}
    }).await;
    let mut control = Control::connect(endpoint, None, Trace::default())
        .await
        .unwrap();
    let input = control.start_call("write", json!({})).await.unwrap();
    assert_eq!(control.call("view", json!({})).await.unwrap()["ok"], true);
    assert_eq!(
        input
            .receive()
            .await
            .unwrap_err()
            .downcast_ref::<rexling_core::control::RpcError>()
            .unwrap()
            .code,
        "not_owner"
    );
    assert_eq!(control.events.len(), 1);
    control.close().await.unwrap();
    server.await.unwrap();
}

#[tokio::test]
async fn request_timeout_does_not_resend_the_request() {
    let count = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let seen = count.clone();
    let (endpoint, server) = peer(Duration::from_millis(100), move |mut ws| async move {
        while let Some(Ok(message)) = ws.next().await {
            if message.is_close() {
                break;
            }
            if message.is_text() {
                seen.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            }
        }
    })
    .await;
    let mut control = Control::connect(endpoint, None, Trace::default())
        .await
        .unwrap();
    let input = control
        .start_call("write", json!({"data":"once"}))
        .await
        .unwrap();
    assert!(
        input
            .receive()
            .await
            .unwrap_err()
            .to_string()
            .contains("outcome is unknown")
    );
    assert_eq!(count.load(std::sync::atomic::Ordering::SeqCst), 1);
    control.close().await.unwrap();
    server.await.unwrap();
}

#[tokio::test]
#[ignore = "requires an explicitly supplied owned Rex server with python3"]
async fn live_ordered_key_pipeline_preserves_utf8_keys_and_paste_order() {
    use rexling_core::{data::Data, ghostty::Replica};
    let server = std::env::var("REX_TEST_SERVER").expect("set REX_TEST_SERVER");
    let endpoint = Endpoint::new(&server, Duration::from_secs(10)).unwrap();
    let mut control = Control::connect(endpoint.clone(), None, Trace::default())
        .await
        .unwrap();
    let pieces = [
        "a".to_owned(),
        "λ".repeat(140),
        "\x1b[D".into(),
        (0u8..32).map(char::from).collect(),
        "\x7f".into(),
        "🦀".into(),
        "pre".into(),
        "-".into(),
    ];
    let expected = format!("{}\x1b[200~PASTE λ\x1b[201~post", pieces.concat());
    let script = format!(
        "import os,sys,tty,time\ntty.setraw(0)\nprint('\\x1b[>31u\\x1b[?2004hPIPE_READY\\r',flush=True)\nb=b''\nwhile len(b)<{}: b+=os.read(0,{}-len(b))\nprint('\\r\\nPIPE_HEX:'+b.hex()+'\\r',flush=True)\ntime.sleep(60)\n",
        expected.len(),
        expected.len()
    );
    let created=control.call("session.create",json!({"label":format!("rexlink-pipeline-test-{}",std::process::id()),"initial_windows":[{"layout":{"block":{"flavor":"com.superlogical.terminal.shell","options":{"shell":"none","command":["python3","-u","-c",script]}}}}]})).await.unwrap();
    let session = created["session_id"].as_str().unwrap();
    let block = created["initial_windows"][0]["block_ids"][0]
        .as_str()
        .unwrap();
    let result=async {
        control.attach(session).await?;
        control.call("session.own_block",json!({"session_id":session,"block_ids":[block],"mode":"take"})).await?;
        let mut data=Data::open(&endpoint,&mut control,session,block,Trace::default()).await?;
        let mut replica=Replica::new(data.nonce);
        tokio::time::timeout(Duration::from_secs(10),async {
            loop {replica.feed(&data.read().await?)?;if replica.ready() && replica.terminal()?.text()?.contains("PIPE_READY"){break;}}
            Ok::<_,anyhow::Error>(())
        }).await.context("waiting for raw terminal readiness")??;
        let mut pending=Vec::new();
        for piece in pieces {pending.push(control.start_call("block.event",json!({"session_id":session,"block_id":block,"event":{"type":"key","payload":{"key":"unidentified","mods":"","consumed_mods":"","text":piece,"unshifted_codepoint":0,"composing":false,"action":"press"}}})).await?);}
        // Every write has been sent before we read any acknowledgment.
        for reply in pending {
            let reply=reply.receive();tokio::pin!(reply);
            loop {tokio::select! {result=&mut reply=>{result?;break;},message=control.next_message()=>{message?;}}}
        }
        control.call("block.event",json!({"session_id":session,"block_id":block,"event":{"type":"paste","payload":{"text":"PASTE λ"}}})).await?;
        control.call("block.event",json!({"session_id":session,"block_id":block,"event":{"type":"key","payload":{"key":"unidentified","mods":"","consumed_mods":"","text":"post","unshifted_codepoint":0,"composing":false,"action":"press"}}})).await?;
        let hex:String=expected.as_bytes().iter().map(|b|format!("{b:02x}")).collect();
        tokio::time::timeout(Duration::from_secs(10),async {
            loop {replica.feed(&data.read().await?)?;if replica.terminal()?.text()?.split_whitespace().collect::<String>().contains(&format!("PIPE_HEX:{hex}")){break;}}
            Ok::<_,anyhow::Error>(())
        }).await.with_context(||format!("waiting for ordered input echo; terminal={:?}",replica.terminal().and_then(|t|t.text())))??;
        data.close().await?;Ok::<_,anyhow::Error>(())
    }.await;
    let cleanup = control
        .call("session.destroy", json!({"session_id":session}))
        .await;
    result.unwrap();
    cleanup.unwrap();
    control.close().await.unwrap();
}
