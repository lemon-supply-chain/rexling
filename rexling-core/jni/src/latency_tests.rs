//! Real actor + Ghostty + loopback protocol peers with deliberately held replies.
//! tungstenite's upgrade callback returns a large ErrorResponse; the size is fixed by the API.
#![allow(clippy::result_large_err)]
use super::*;
use futures_util::{SinkExt, StreamExt, stream::FuturesUnordered};
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio::sync::Semaphore;
use tokio_tungstenite::{
    accept_hdr_async,
    tungstenite::{
        Message,
        handshake::server::{Request, Response},
    },
};

const NONCE: u64 = 11418691820941138289;
const SNAPSHOT: &[u8] = include_bytes!("../../tests/fixtures/ghostty-remote.bin");
struct Peer {
    endpoint: Endpoint,
    gate: Arc<Semaphore>,
    snapshot_gate: Arc<Semaphore>,
    mirror_gate: Arc<Semaphore>,
    mirror_disconnect: Arc<Semaphore>,
    writes: Arc<Mutex<Vec<String>>>,
    acknowledged: Arc<AtomicUsize>,
    methods: Arc<Mutex<Vec<String>>>,
    output: mpsc::Sender<Vec<u8>>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Peer {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Peer {
    async fn new(delay: Duration, reject: bool, drop_first: bool) -> Self {
        Self::with_floating(delay, reject, drop_first, false).await
    }
    async fn with_floating(
        delay: Duration,
        reject: bool,
        drop_first: bool,
        floating: bool,
    ) -> Self {
        Self::with_snapshot(delay, reject, drop_first, floating, false).await
    }
    async fn with_snapshot(
        delay: Duration,
        reject: bool,
        drop_first: bool,
        floating: bool,
        slow_snapshot: bool,
    ) -> Self {
        Self::with_options(delay, reject, drop_first, floating, slow_snapshot, false).await
    }
    async fn with_options(
        delay: Duration,
        reject: bool,
        drop_first: bool,
        floating: bool,
        slow_snapshot: bool,
        tiled: bool,
    ) -> Self {
        Self::with_views(
            delay,
            reject,
            drop_first,
            floating,
            slow_snapshot,
            tiled,
            Vec::new(),
        )
        .await
    }
    async fn with_views(
        delay: Duration,
        reject: bool,
        drop_first: bool,
        floating: bool,
        slow_snapshot: bool,
        tiled: bool,
        views: Vec<Value>,
    ) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = Endpoint::new(
            &format!("http://{}", listener.local_addr().unwrap()),
            Duration::from_secs(3),
        )
        .unwrap();
        let gate = Arc::new(Semaphore::new(0));
        let snapshot_gate = Arc::new(Semaphore::new(if slow_snapshot { 0 } else { 128 }));
        let history_gate = snapshot_gate.clone();
        let mirror_gate = Arc::new(Semaphore::new(0));
        let mirror_disconnect = Arc::new(Semaphore::new(0));
        let ticket_gate = mirror_gate.clone();
        let close_mirror = mirror_disconnect.clone();
        let writes = Arc::new(Mutex::new(Vec::new()));
        let acknowledged = Arc::new(AtomicUsize::new(0));
        let methods = Arc::new(Mutex::new(Vec::new()));
        let (output_tx, output_rx) = mpsc::channel::<Vec<u8>>(128);
        let output_rx = Arc::new(tokio::sync::Mutex::new(output_rx));
        let (g, w, a, m) = (
            gate.clone(),
            writes.clone(),
            acknowledged.clone(),
            methods.clone(),
        );
        let output = output_tx.clone();
        let views = Arc::new(Mutex::new(std::collections::VecDeque::from(views)));
        let task = tokio::spawn(async move {
            let mut peers = tokio::task::JoinSet::new();
            loop {
                let Ok((socket, _)) = listener.accept().await else {
                    break;
                };
                socket.set_nodelay(true).unwrap();
                let (gate, writes, acks, methods, output, rx) = (
                    g.clone(),
                    w.clone(),
                    a.clone(),
                    m.clone(),
                    output_tx.clone(),
                    output_rx.clone(),
                );
                let history_gate = history_gate.clone();
                let ticket_gate = ticket_gate.clone();
                let close_mirror = close_mirror.clone();
                let views = views.clone();
                peers.spawn(async move {
                    let mut ws=accept_hdr_async(socket,|request:&Request,mut response:Response|{
                        response.headers_mut().insert("Sec-WebSocket-Protocol",request.headers()["Sec-WebSocket-Protocol"].clone());Ok(response)
                    }).await.unwrap();
                    let Some(Ok(hello))=ws.next().await else{return;};
                    if hello.is_binary() {
                        let mirror=tiled && hello.into_data().get(1)==Some(&1);
                        let mut welcome=vec![0;32];welcome[0]=0x81;welcome[1..17].fill(1);
                        welcome[20..24].copy_from_slice(&60000u32.to_le_bytes());welcome[24..32].copy_from_slice(&NONCE.to_le_bytes());
                        ws.send(Message::Binary(welcome.into())).await.unwrap();
                        if slow_snapshot {
                            let mut end=10;
                            loop {
                                let tag=u16::from_le_bytes(SNAPSHOT[end..end+2].try_into().unwrap());
                                end+=10+u32::from_le_bytes(SNAPSHOT[end+2..end+6].try_into().unwrap()) as usize;
                                if tag==5 {break;}
                            }
                            ws.send(Message::Binary(SNAPSHOT[..end].to_vec().into())).await.unwrap();
                            let permit=history_gate.acquire().await.unwrap();permit.forget();
                            ws.send(Message::Binary(SNAPSHOT[end..].to_vec().into())).await.unwrap();
                        } else {ws.send(Message::Binary(SNAPSHOT.to_vec().into())).await.unwrap();}
                        if mirror {
                            tokio::select! {
                                permit=close_mirror.acquire() => {permit.unwrap().forget();let _=ws.close(None).await;},
                                _=ws.next()=>{},
                            }
                            return;
                        }
                        let mut rx=rx.lock().await;
                        loop {tokio::select! {
                            data=rx.recv()=>{let Some(data)=data else{break;};if ws.send(Message::Binary(data.into())).await.is_err(){break;}},
                            msg=ws.next()=>if !matches!(msg,Some(Ok(msg)) if !msg.is_close()){break;}
                        }}
                    } else {
                        ws.send(Message::Text(json!({"type":"welcome","protocol":"rex.control.v1","api_revision":8,"limits":{"max_message_bytes":1_048_576},"client":{"id":"client:test","resumed":false}}).to_string().into())).await.unwrap();
                        let (mut sink,mut stream)=ws.split();
                        let mut replies=FuturesUnordered::<Pin<Box<dyn Future<Output=(Value,bool)>+Send>>>::new();
                        loop {tokio::select! {
                            msg=stream.next()=>{
                                let Some(Ok(msg))=msg else{break;};if msg.is_close(){break;}
                                if !msg.is_text(){continue;}
                                let request:Value=serde_json::from_str(msg.to_text().unwrap()).unwrap();
                                let method=request["method"].as_str().unwrap().to_owned();methods.lock().unwrap().push(method.clone());
                                let input=method=="com.superlogical.terminal.write" ||
                                    (method=="block.event" && request["payload"]["event"]["type"]!="focus");
                                let mirror_ticket=tiled && method=="data.connect" && request["payload"]["block_id"]=="block:mirror";
                                let mut response=json!({"type":"response","id":request["id"],"payload":{}});
                                if input {
                                    let text=if method=="block.event" {request["payload"]["event"]["payload"]["text"].as_str().unwrap()} else {request["payload"]["args"]["data"].as_str().unwrap()};
                                    let first={let mut w=writes.lock().unwrap();w.push(text.into());w.len()==1};
                                    let _=output.send(b"\r\nVT_BEFORE_ACK\r\n".to_vec()).await;
                                    if drop_first && first {break;}
                                    if reject {response["error"]=json!({"code":"not_owner","message":"Control changed","status":403});}
                                } else if method=="data.connect" {response["payload"]=json!({"ticket":if mirror_ticket {"AQAAAAAAAAAAAAAAAAAAAA"} else {"AAAAAAAAAAAAAAAAAAAAAA"}});}
                                else if method=="session.list" || method=="session.attach_all" {response["payload"]=json!({"sessions":[]});}
                                else if method=="session.view" || method=="session.attach" {
                                    let mut view=json!({"windows":[{"window_id":"window:test","layers":[{"blocks":[{"block_id":"block:test","creator_name":"com.superlogical.terminal","owner":if reject {"client:other"} else {"client:test"}}]}]}]});
                                    if floating {
                                        view["windows"][0]["layers"][0]["kind"]=json!("floating");
                                        view["windows"][0]["layers"][0]["layout"]=json!({"block_id":"block:test","rect":[0,0,1,1]});
                                        view["windows"][0]["layers"].as_array_mut().unwrap().push(json!({"kind":"tiled","blocks":[{"block_id":"block:tiled","creator_name":"com.superlogical.terminal"}],"layout":{"block_id":"block:tiled","rect":[0,0,1,1]}}));
                                    }
                                    if tiled {
                                        let layer=&mut view["windows"][0]["layers"][0];
                                        layer["kind"]=json!("tiled");
                                        layer["blocks"].as_array_mut().unwrap().push(json!({"block_id":"block:mirror","creator_name":"com.superlogical.terminal","owner":"client:test"}));
                                        layer["layout"]=json!({"rect":[0,0,1,1],"split":{"split_id":"split:test","direction":"horizontal","ratio":0.5,
                                            "before":{"rect":[0,0,0.5,1],"block_id":"block:test"},"after":{"rect":[0.5,0,0.5,1],"block_id":"block:mirror"}}});
                                    }
                                    response["payload"]=if method=="session.attach" {json!({"view":view})}
                                        else {views.lock().unwrap().pop_front().unwrap_or(view)};
                                }
                                let gate=gate.clone();
                                let ticket_gate=ticket_gate.clone();
                                replies.push(Box::pin(async move {
                                    if mirror_ticket {ticket_gate.acquire().await.unwrap().forget();}
                                    if input {let permit=gate.acquire().await.unwrap();permit.forget();tokio::time::sleep(delay).await;}
                                    (response,input)
                                }));
                            },
                            reply=replies.next(),if !replies.is_empty()=>{
                                let (response,input)=reply.unwrap();if sink.send(Message::Text(response.to_string().into())).await.is_err(){break;}
                                if input {acks.fetch_add(1,Ordering::SeqCst);}
                            }
                        }}
                    }
                });
            }
        });
        Self {
            endpoint,
            gate,
            snapshot_gate,
            mirror_gate,
            mirror_disconnect,
            writes,
            acknowledged,
            methods,
            output,
            task,
        }
    }
    async fn actor(&self, limit: usize, batch: bool) -> (Actor, Arc<Mutex<Shared>>) {
        let shared = Arc::new(Mutex::new(Shared {
            revision: 0,
            json: String::new(),
            activity: Arc::default(),
            pending_previews: BTreeMap::new(),
            frame: None,
            key: String::new(),
            wire: crate::updates::WireBase::default(),
        }));
        let mut actor = Actor::new(shared.clone(), Arc::new(crate::updates::Updates::default()));
        let mut control = Control::connect(self.endpoint.clone(), None, Trace::default())
            .await
            .unwrap();
        let mut data = Data::open(
            &self.endpoint,
            &mut control,
            "session:test",
            "block:test",
            Trace::default(),
        )
        .await
        .unwrap();
        let mut replica = Replica::new(data.nonce);
        replica.feed(&data.read().await.unwrap()).unwrap();
        actor.endpoint = Some(self.endpoint.clone());
        actor.control = Some(control);
        actor.data = Some(data);
        actor.replica = Some(replica);
        actor.phase = "connected".into();
        actor.controlled = true;
        actor.connection_epoch = 1;
        actor.selected_session = "session:test".into();
        actor.selected_block = "block:test".into();
        actor.input_limit = limit;
        actor.batch_text = batch;
        actor.program_refresh = tokio::time::Instant::now() + Duration::from_secs(3600);
        actor.activity_refresh = actor.program_refresh;
        (actor, shared)
    }
}
async fn until(mut condition: impl FnMut() -> bool) {
    tokio::time::timeout(Duration::from_secs(4), async {
        while !condition() {
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("actor did not reach expected state");
}
async fn input(tx: &mpsc::Sender<Value>, text: &str) {
    tx.send(json!({"type":"input","text":text})).await.unwrap();
}
async fn shutdown(tx: &mpsc::Sender<Value>) {
    tx.send(json!({"type":"shutdown"})).await.unwrap();
}

fn floating_view(minimized: &str) -> Value {
    let layers: Vec<_> = ["block:tiled", "block:test", "block:floating"].into_iter().map(|id| {
        json!({"kind":if id=="block:tiled" {"tiled"} else {"floating"},"layer_id":format!("layer:{id}"),"bounds":[0,0,1,1],
            "state":if id == minimized {"minimized"} else {"normal"},
            "layout":{"block_id":id,"rect":[0,0,1,1]},
            "blocks":[{"block_id":id,"creator_name":"com.superlogical.terminal"}]})
    }).collect();
    let focused = if minimized == "block:test" {
        "block:floating"
    } else {
        "block:test"
    };
    json!({"windows":[{"window_id":"window:test","focused_block_id":focused,"layers":layers}]})
}

#[tokio::test]
async fn refresh_stops_when_all_three_views_minimize_the_selected_pane() {
    let views = ["block:test", "block:floating", "block:test", ""]
        .map(floating_view)
        .to_vec();
    let peer = Peer::with_views(Duration::ZERO, false, false, false, false, false, views).await;
    let (mut actor, shared) = peer.actor(1, false).await;
    actor.mirror.request(
        "session:test",
        vec![crate::mirror::Wanted {
            block: "block:tiled".into(),
            columns: 80,
            rows: 24,
        }],
    );
    actor.refresh_view().await.unwrap();
    assert_eq!(
        peer.methods
            .lock()
            .unwrap()
            .iter()
            .filter(|method| *method == "session.view")
            .count(),
        3
    );
    assert!(actor.selected_block.is_empty() && actor.data.is_none() && !actor.controlled);
    assert!(actor.mirror.wants("session:test", "block:tiled"));
    assert!(actor.error.contains("Floating panes are changing"));
    let awaiting_selection = || {
        serde_json::from_str::<Value>(&shared.lock().unwrap().json).unwrap()["awaitingPaneSelection"]
            == true
    };
    assert!(awaiting_selection());
    actor
        .command(json!({"type":"block","id":"block:tiled"}))
        .await
        .unwrap();
    assert_eq!(actor.selected_block, "block:tiled");
    assert!(!awaiting_selection());
}

#[tokio::test]
async fn acknowledged_restore_opens_the_stream_before_fetching_the_view() {
    let peer = Peer::with_views(
        Duration::ZERO,
        false,
        false,
        false,
        false,
        false,
        vec![floating_view("")],
    )
    .await;
    let (mut actor, _) = peer.actor(1, false).await;
    actor.set_view(&floating_view("block:floating"));
    peer.methods.lock().unwrap().clear();
    actor
        .command(json!({"type":"block","id":"block:floating","restoreMinimized":true}))
        .await
        .unwrap();
    let methods = peer.methods.lock().unwrap();
    let relevant: Vec<_> = methods
        .iter()
        .map(String::as_str)
        .filter(|method| {
            matches!(
                *method,
                "session.set_layer_state" | "data.connect" | "session.view"
            )
        })
        .collect();
    assert_eq!(
        relevant,
        ["session.set_layer_state", "data.connect", "session.view"]
    );
    assert_eq!(actor.selected_block, "block:floating");
}

#[tokio::test]
async fn synchronized_redraw_never_publishes_the_intermediate_blank_screen() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (mut actor, shared) = peer.actor(1, false).await;
    actor.dirty = true;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        until(|| shared.lock().unwrap().frame.is_some()).await;
        let complete = shared.lock().unwrap().frame.clone().unwrap();
        peer.output
            .send(b"\x1b[?2026h\x1b[2J\x1b[H".to_vec())
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(
            Arc::ptr_eq(shared.lock().unwrap().frame.as_ref().unwrap(), &complete),
            "Clearing inside a synchronized redraw must retain the old complete frame"
        );
        peer.output.send(b"partial redraw".to_vec()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(
            Arc::ptr_eq(shared.lock().unwrap().frame.as_ref().unwrap(), &complete),
            "A partial redraw must not reach the Android renderer"
        );
        peer.output
            .send(b" completed\x1b[?2026l".to_vec())
            .await
            .unwrap();
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|frame| frame.text().contains("partial redraw completed"))
        })
        .await;
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn output_and_rendering_continue_before_input_acknowledgment() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    let scenario = async {
        input(&tx, "hello").await;
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|f| f.text().contains("VT_BEFORE_ACK"))
        })
        .await;
        assert_eq!(
            peer.acknowledged.load(Ordering::SeqCst),
            0,
            "output must paint while acknowledgment is held"
        );
        peer.gate.add_permits(1);
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 1).await;
        shutdown(&tx).await;
    };
    tokio::join!(actor.run(rx), scenario);
}

#[tokio::test]
async fn wheel_echo_renders_with_two_batches_in_flight_and_coalesces_waiting_motion() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (mut actor, shared) = peer.actor(8, true).await;
    actor
        .replica
        .as_mut()
        .unwrap()
        .feed(b"\x1b[?1049h\x1b[?1000h\x1b[?1006h")
        .unwrap();
    let (tx, rx) = mpsc::channel(128);
    let wheel = || json!({"type":"wheel","lines":1,"col":4,"row":5,"cw":10,"ch":20});
    tokio::join!(actor.run(rx), async {
        tx.send(wheel()).await.unwrap();
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|frame| frame.text().contains("VT_BEFORE_ACK"))
        })
        .await;
        assert_eq!(peer.acknowledged.load(Ordering::SeqCst), 0);
        tx.send(wheel()).await.unwrap();
        until(|| peer.writes.lock().unwrap().len() == 2).await;
        for _ in 0..8 {
            tx.send(wheel()).await.unwrap();
        }
        until(|| tx.capacity() == 128).await;
        assert_eq!(peer.writes.lock().unwrap().len(), 2);
        assert_eq!(peer.acknowledged.load(Ordering::SeqCst), 0);
        peer.gate.add_permits(1);
        until(|| peer.writes.lock().unwrap().len() == 3).await;
        assert_eq!(peer.writes.lock().unwrap()[2], "\x1b[<65;5;6M".repeat(8));
        peer.gate.add_permits(2);
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 3).await;
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn rejected_input_discards_unsent_text_without_reconnecting_or_taking_control() {
    let peer = Peer::new(Duration::ZERO, true, false).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        input(&tx, "first").await;
        input(&tx, "must-not-send").await;
        until(|| peer.writes.lock().unwrap().len() == 1).await;
        peer.gate.add_permits(1);
        until(|| {
            serde_json::from_str::<Value>(&shared.lock().unwrap().json).unwrap()["controlled"]
                == false
        })
        .await;
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["first"]);
        assert!(
            !peer
                .methods
                .lock()
                .unwrap()
                .iter()
                .any(|m| m == "session.own_block")
        );
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn connection_loss_discards_queued_input_and_stale_bindings_cannot_send() {
    let peer = Peer::new(Duration::ZERO, false, true).await;
    let (actor, shared) = peer.actor(1, false).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        // Wrong activation cannot write even while this connection is healthy.
        tx.send(json!({"type":"input","text":"wrong-pane","block":"block:other"}))
            .await
            .unwrap();
        tx.send(json!({"type":"input","text":"old-connection","connectionEpoch":0}))
            .await
            .unwrap();
        input(&tx, "uncertain").await;
        input(&tx, "queued").await;
        until(|| {
            serde_json::from_str::<Value>(&shared.lock().unwrap().json).unwrap()["phase"]
                == "reconnecting"
        })
        .await;
        let state: Value = serde_json::from_str(&shared.lock().unwrap().json).unwrap();
        assert!(state["notice"].as_str().unwrap().contains("not resent"));
        assert_eq!(*peer.writes.lock().unwrap(), ["uncertain"]);
        until(|| {
            let v: Value = serde_json::from_str(&shared.lock().unwrap().json).unwrap();
            v["phase"] == "connected" && v["connectionEpoch"] == 2
        })
        .await;
        tx.send(json!({"type":"input","text":"stale-after-reconnect","connectionEpoch":1}))
            .await
            .unwrap();
        peer.gate.add_permits(8);
        input(&tx, "fresh").await;
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 1).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["uncertain", "fresh"]);
        shutdown(&tx).await;
    });
}

async fn timed_run(limit: usize) -> Duration {
    let peer = Peer::new(Duration::from_millis(100), false, false).await;
    let (actor, _) = peer.actor(limit, false).await;
    let (tx, rx) = mpsc::channel(128);
    peer.gate.add_permits(24);
    let start = tokio::time::Instant::now();
    tokio::join!(actor.run(rx), async {
        for n in 0..24 {
            input(&tx, &format!("{n},")).await;
        }
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 24).await;
        shutdown(&tx).await;
    });
    assert_eq!(
        *peer.writes.lock().unwrap(),
        (0..24).map(|n| format!("{n},")).collect::<Vec<_>>()
    );
    start.elapsed()
}
#[tokio::test]
async fn bounded_pipeline_removes_the_per_keystroke_round_trip_backlog() {
    let serial = timed_run(1).await;
    let pipelined = timed_run(8).await;
    eprintln!(
        "24 ordered inputs, 100ms held replies: one in flight={serial:?}, eight={pipelined:?}"
    );
    assert!(
        pipelined < serial / 2,
        "pipelining must improve acknowledgment throughput without changing order"
    );
}

#[tokio::test]
async fn paste_remains_a_barrier_even_with_eight_input_requests_allowed() {
    let peer = Peer::new(Duration::ZERO, false, false).await;
    let (actor, _) = peer.actor(8, true).await;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        input(&tx, "before").await;
        tx.send(json!({"type":"paste","text":"PASTE"}))
            .await
            .unwrap();
        input(&tx, "after").await;
        until(|| peer.writes.lock().unwrap().len() == 1).await;
        tokio::time::sleep(Duration::from_millis(25)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before"]);
        peer.gate.add_permits(1);
        until(|| peer.writes.lock().unwrap().len() == 2).await;
        tokio::time::sleep(Duration::from_millis(25)).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before", "PASTE"]);
        peer.gate.add_permits(1);
        until(|| peer.writes.lock().unwrap().len() == 3).await;
        assert_eq!(*peer.writes.lock().unwrap(), ["before", "PASTE", "after"]);
        peer.gate.add_permits(1);
        until(|| peer.acknowledged.load(Ordering::SeqCst) == 3).await;
        shutdown(&tx).await;
    });
}

#[tokio::test(flavor = "current_thread")]
async fn minimized_floating_terminal_reopens_live_without_another_snapshot() {
    let peer = Peer::with_floating(Duration::ZERO, false, false, true).await;
    let (mut actor, _) = peer.actor(8, true).await;
    actor.controlled = false; // Viewing must never take ownership.
    actor.refresh_view().await.unwrap();
    let nonce = actor.data.as_ref().unwrap().nonce;
    actor.open_block("block:tiled".into(), None).await.unwrap();
    assert_eq!(
        actor.retained_floating.as_ref().unwrap().block,
        "block:test"
    );
    let cached = actor.retained_floating.as_mut().unwrap();
    cached
        .replica
        .feed(b"\r\nUPDATED_WHILE_MINIMIZED\r\n")
        .unwrap();
    let before = peer
        .methods
        .lock()
        .unwrap()
        .iter()
        .filter(|m| m.as_str() == "data.connect")
        .count();
    actor.open_block("block:test".into(), None).await.unwrap();
    assert_eq!(actor.data.as_ref().unwrap().nonce, nonce);
    assert!(
        actor
            .replica
            .as_mut()
            .unwrap()
            .terminal()
            .unwrap()
            .frame()
            .unwrap()
            .text()
            .contains("UPDATED_WHILE_MINIMIZED")
    );
    assert!(actor.dirty);
    assert!(actor.retained_floating.is_none());
    assert_eq!(
        before,
        peer.methods
            .lock()
            .unwrap()
            .iter()
            .filter(|m| m.as_str() == "data.connect")
            .count()
    );
    // Recovery must never reuse a stream from the old connection.
    actor.controlled = false;
    actor.open_block("block:tiled".into(), None).await.unwrap();
    assert!(actor.retained_floating.is_some());
    actor.lose_connections();
    assert!(actor.retained_floating.is_none());
}

#[tokio::test(flavor = "current_thread")]
async fn opening_a_floating_overlay_keeps_the_tiled_stream_for_return_focus() {
    let peer = Peer::with_floating(Duration::ZERO, false, false, true).await;
    let (mut actor, _) = peer.actor(8, true).await;
    actor.controlled = false;
    actor.refresh_view().await.unwrap();
    actor.open_block("block:tiled".into(), None).await.unwrap();
    let count_data_opens = || {
        peer.methods
            .lock()
            .unwrap()
            .iter()
            .filter(|m| m.as_str() == "data.connect")
            .count()
    };
    let before = count_data_opens();

    actor
        .command(json!({"type":"block","id":"block:test","keepTiled":true}))
        .await
        .unwrap();
    assert_eq!(actor.selected_block, "block:test");
    assert!(actor.mirror.wants("session:test", "block:tiled"));

    actor
        .command(json!({"type":"block","id":"block:tiled","keepTiled":true}))
        .await
        .unwrap();
    assert_eq!(actor.selected_block, "block:tiled");
    assert!(actor.mirror.wants("session:test", "block:test"));
    assert!(actor.retained_floating.is_none());
    actor
        .command(
            json!({"type":"mirror","session":"session:test","window":"window:test",
        "panes":[{"block":"block:test","rows":24,"columns":80}]}),
        )
        .await
        .unwrap();
    assert!(actor.mirror.wants("session:test", "block:test"));
    assert_eq!(count_data_opens(), before);
}

#[tokio::test(flavor = "current_thread")]
async fn retained_floating_stream_consumes_hidden_output_without_publishing_or_clipboard_replay() {
    let peer = Peer::with_floating(Duration::ZERO, false, false, true).await;
    let (mut actor, shared) = peer.actor(8, true).await;
    actor.refresh_view().await.unwrap();
    actor.open_block("block:tiled".into(), None).await.unwrap();
    let before = peer
        .methods
        .lock()
        .unwrap()
        .iter()
        .filter(|m| m.as_str() == "data.connect")
        .count();
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        until(|| shared.lock().unwrap().frame.is_some()).await;
        peer.output
            .send(b"\r\nHIDDEN_LIVE_UPDATE\r\n\x1b]52;c;aGlkZGVu\x07".to_vec())
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert!(
            !shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .unwrap()
                .text()
                .contains("HIDDEN_LIVE_UPDATE")
        );
        tx.send(json!({"type":"block","id":"block:test","navigationId":1}))
            .await
            .unwrap();
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|f| f.text().contains("HIDDEN_LIVE_UPDATE"))
        })
        .await;
        let state: Value = serde_json::from_str(&shared.lock().unwrap().json).unwrap();
        assert!(
            state["clipboard"].is_null(),
            "hidden clipboard writes cannot replay on reopen"
        );
        assert_eq!(
            before,
            peer.methods
                .lock()
                .unwrap()
                .iter()
                .filter(|m| m.as_str() == "data.connect")
                .count()
        );
        shutdown(&tx).await;
    });
}

#[tokio::test(flavor = "current_thread")]
async fn renders_the_screen_while_initial_history_is_still_blocked_on_the_network() {
    let peer = Peer::with_snapshot(Duration::ZERO, false, false, false, true).await;
    let (mut actor, shared) = peer.actor(8, true).await;
    assert!(actor.replica.as_ref().unwrap().ready());
    assert!(!actor.replica.as_ref().unwrap().snapshot_complete());
    actor.dirty = true;
    let (tx, rx) = mpsc::channel(128);
    tokio::join!(actor.run(rx), async {
        until(|| shared.lock().unwrap().frame.is_some()).await;
        assert_eq!(
            peer.snapshot_gate.available_permits(),
            0,
            "first frame must precede history delivery"
        );
        let first = shared.lock().unwrap().frame.clone().unwrap();
        assert!(!first.text().trim().is_empty());
        peer.snapshot_gate.add_permits(1);
        peer.output
            .send(b"\r\nLIVE_AFTER_HISTORY\r\n".to_vec())
            .await
            .unwrap();
        until(|| {
            shared
                .lock()
                .unwrap()
                .frame
                .as_ref()
                .is_some_and(|f| f.text().contains("LIVE_AFTER_HISTORY"))
        })
        .await;
        shutdown(&tx).await;
    });
}

#[tokio::test]
async fn slow_mirror_ticket_does_not_block_input_and_two_pane_focus_reuses_both_streams() {
    let peer = Peer::with_options(Duration::ZERO, false, false, false, false, true).await;
    let (mut actor, shared) = peer.actor(1, false).await;
    actor.refresh_view().await.unwrap();
    actor.dirty = true;
    let (tx, rx) = mpsc::channel(128);
    let ticket_count = || {
        peer.methods
            .lock()
            .unwrap()
            .iter()
            .filter(|m| *m == "data.connect")
            .count()
    };
    let mirror = |block| {
        json!({"type":"mirror","session":"session:test","window":"window:test",
        "panes":[{"block":block,"columns":40,"rows":20}]})
    };
    tokio::join!(actor.run(rx), async {
        tx.send(mirror("block:mirror")).await.unwrap();
        until(|| ticket_count() == 2).await;
        input(&tx, "while-the-ticket-is-held").await;
        tokio::time::timeout(
            Duration::from_secs(1),
            until(|| {
                shared
                    .lock()
                    .unwrap()
                    .frame
                    .as_ref()
                    .is_some_and(|frame| frame.text().contains("VT_BEFORE_ACK"))
            }),
        )
        .await
        .expect("focused input must echo before the held mirror ticket times out");
        peer.gate.add_permits(1);
        peer.mirror_gate.add_permits(1);
        until(|| {
            shared
                .lock()
                .unwrap()
                .pending_previews
                .contains_key("block:mirror")
        })
        .await;

        // A failed mirror retries independently and advertises stale content until it has a frame.
        peer.mirror_disconnect.add_permits(1);
        until(|| {
            shared
                .lock()
                .unwrap()
                .json
                .contains(r#""mirrorWaiting":["block:mirror"]"#)
        })
        .await;
        until(|| ticket_count() == 3).await;
        peer.mirror_gate.add_permits(1);
        until(|| {
            shared
                .lock()
                .unwrap()
                .json
                .contains(r#""mirrorWaiting":[]"#)
        })
        .await;

        for (next, previous) in [
            ("block:mirror", "block:test"),
            ("block:test", "block:mirror"),
        ] {
            tx.send(json!({"type":"block","id":next})).await.unwrap();
            until(|| {
                let state = shared.lock().unwrap();
                state.key.ends_with(next) && state.frame.is_some()
            })
            .await;
            tx.send(mirror(previous)).await.unwrap();
            until(|| {
                shared
                    .lock()
                    .unwrap()
                    .pending_previews
                    .contains_key(previous)
            })
            .await;
            assert_eq!(
                ticket_count(),
                3,
                "focus handoff must preserve both data streams"
            );
        }
        shutdown(&tx).await;
    });
}
