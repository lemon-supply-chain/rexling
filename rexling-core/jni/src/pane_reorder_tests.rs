//! Real control RPCs against an isolated peer; no live user sessions are touched.
use super::*;
use futures_util::{SinkExt,StreamExt};
use tokio_tungstenite::{accept_hdr_async,tungstenite::{Message,handshake::server::{Request,Response}}};

fn view(ids:&[String])->Value {
    let block=|id:&str|json!({"block_id":id,"creator_name":"com.superlogical.terminal","owner":"client:test","label":id});
    json!({"windows":[{"window_id":"w","layers":[
        {"kind":"tiled","blocks":[block(&ids[0]),block(&ids[1])],"layout":{"rect":[0,0,1,1],"split":{
            "before":{"block_id":ids[0],"rect":[0,0,0.4,1]},"after":{"block_id":ids[1],"rect":[0.4,0,0.6,1]}}}},
        {"kind":"floating","blocks":[block(&ids[2])],"layout":{"block_id":ids[2],"rect":[0.2,0.2,0.6,0.6]}}
    ]}]})
}

async fn exercise(reject_second:bool,stale:bool) {
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint=Endpoint::new(&format!("http://{}",listener.local_addr().unwrap()),Duration::from_secs(3)).unwrap();
    let calls=Arc::new(Mutex::new(Vec::<Value>::new()));let captured=calls.clone();
    let peer=tokio::spawn(async move {
        let (socket,_)=listener.accept().await.unwrap();
        let mut ws=accept_hdr_async(socket,|request:&Request,mut response:Response|{
            response.headers_mut().insert("Sec-WebSocket-Protocol",request.headers()["Sec-WebSocket-Protocol"].clone());Ok(response)
        }).await.unwrap();
        ws.next().await.unwrap().unwrap();
        ws.send(Message::Text(json!({"type":"welcome","protocol":"rex.control.v1","api_revision":8,"limits":{"max_message_bytes":1_048_576},"client":{"id":"client:test","resumed":false}}).to_string().into())).await.unwrap();
        let mut ids=vec!["a".to_owned(),"b".to_owned(),"c".to_owned()];let mut swaps=0;
        while let Some(Ok(message))=ws.next().await {
            if message.is_close(){break;}if !message.is_text(){continue;}
            let request:Value=serde_json::from_str(message.to_text().unwrap()).unwrap();
            captured.lock().unwrap().push(request.clone());
            let mut response=json!({"type":"response","id":request["id"],"payload":{}});
            match request["method"].as_str().unwrap() {
                "session.view"=>response["payload"]=view(&ids),
                "session.swap_blocks"=>{
                    swaps+=1;
                    if reject_second && swaps==2 {response["error"]=json!({"code":"conflict","message":"fixture rejects second swap","status":409});}
                    else {
                        assert_eq!(request["payload"]["session_id"],"s");
                        let i=ids.iter().position(|id|request["payload"]["block_id"]==*id).unwrap();
                        let j=ids.iter().position(|id|request["payload"]["other_block_id"]==*id).unwrap();ids.swap(i,j);
                        response["payload"]=json!({"revision":swaps});
                    }
                },
                method=>panic!("Unexpected control/resize/navigation call: {method}"),
            }
            ws.send(Message::Text(response.to_string().into())).await.unwrap();
        }
    });
    let control=Control::connect(endpoint,None,Trace::default()).await.unwrap();
    let shared=Arc::new(Mutex::new(Shared{revision:0,json:String::new(),pending_previews:BTreeMap::new(),frame:None,key:String::new(),wire:crate::updates::WireBase::default(),activity:Arc::default()}));
    let mut actor=Actor::new(shared,Arc::new(crate::updates::Updates::default()));
    actor.control=Some(control);actor.visible=true;actor.phase="connected".into();actor.selected_session="s".into();actor.selected_block="a".into();actor.navigation_id=11;
    actor.set_view(&view(&["a".into(),"b".into(),"c".into()]));
    actor.programs=json!({"a":["codex"],"b":["nvim"],"c":["fish"]});actor.titles=json!({"a":"Task"});
    actor.execute_command(json!({"type":"move_pane","session":"s","window":"w","id":"a","before":"","blocks":if stale {json!(["b","a","c"])} else {json!(["a","b","c"])},"requestId":7})).await;
    let ids:Vec<_>=actor.windows[0]["blocks"].as_array().unwrap().iter().map(|b|b["id"].as_str().unwrap()).collect();
    assert_eq!(ids,if stale {vec!["a","b","c"]} else if reject_second {vec!["b","a","c"]} else {vec!["b","c","a"]});
    assert_eq!(actor.selected_block,"a");assert!(actor.controlled);assert_eq!(actor.navigation_id,11);assert_eq!(actor.pane_move_id,7);
    assert_eq!(actor.programs["a"],json!(["codex"]));assert_eq!(actor.titles["a"],"Task");
    let recorded=calls.lock().unwrap().clone();
    assert_eq!(recorded.iter().filter(|r|r["method"]=="session.swap_blocks").count(),if stale {0} else {2});
    if !reject_second && !stale {
        assert!(actor.error.is_empty());
        let layout=&actor.windows[0]["layout"];
        assert_eq!(layout[0]["rect"],json!([0,0,0.4,1]));assert_eq!(layout[2]["rect"],json!([0.2,0.2,0.6,0.6]));assert_eq!(layout[2]["id"],"a");assert_eq!(layout[2]["floating"],true);
    } else {assert!(!actor.error.is_empty());}
    if reject_second {assert!(actor.error.contains("partially updated"));}
    actor.control.as_mut().unwrap().close().await.unwrap();peer.await.unwrap();
}

#[test] fn swaps_sync_order_preserve_geometry_control_selection_and_programs() {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(exercise(false,false));
}
#[test] fn partial_failure_refreshes_actual_order_acknowledges_and_never_replays() {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(exercise(true,false));
}
#[test] fn stale_layout_is_rejected_before_any_swap() {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(exercise(false,true));
}
