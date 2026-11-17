//! Read-only status snapshots. This connection never opens PTY data or takes ownership.
use anyhow::{Context,Result};
use rex_core::{control::{Control,Trace},transport::Endpoint};
use serde_json::{Value,json};
use std::time::Duration;

fn plain(value: &Value,limit: usize)->String {
    value.as_str().unwrap_or("").chars().filter(|c|!c.is_control() && !matches!(*c,'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}')).take(limit).collect()
}
fn stable_title(value: &str)->String {
    // Animated braille prefixes are redundant beside the sheet's explicit status.
    let mut chars=value.chars();let first=chars.next();
    if first.is_some_and(|c|('\u{2800}'..='\u{28ff}').contains(&c)) && chars.clone().next().is_some_and(char::is_whitespace) {chars.as_str().trim_start().to_owned()} else {value.to_owned()}
}
/// Codex's explicit attention title, not arbitrary task text containing those words.
pub(crate) fn codex_attention_title(title: &str)->bool {
    let Some(rest)=title.strip_prefix('[') else {return false;};
    let Some((marker,rest))=rest.split_once(']') else {return false;};
    !marker.is_empty() && marker.len()<=8 && marker.chars().all(|c|c==' ' || c=='.' || c=='!') &&
        marker.chars().any(|c|c=='.' || c=='!') && rest.trim_start().starts_with("Action Required | ")
}
pub fn records(process: &Value,status: &Value,title: &Value,session: &Value,window: &Value,block: &Value)->Vec<Value> {
    status["records"].as_array().into_iter().flatten().filter_map(|record| {
        let state=record["state"].as_str()?;
        if !matches!(state,"working"|"blocked"|"done"|"error"|"idle") {return None;}
        // Completed/error reports intentionally survive process exit. Live states
        // with a known owner must still belong to the foreground process.
        let owner=&record["owner"];let foreground=&process["foreground"];
        if !matches!(state,"done"|"error") && owner.is_object() &&
            (owner["pid"]!=foreground["pid"] || owner["name"]!=foreground["name"]) {return None;}
        let app=record["effective_app"].as_str().or(record["app"].as_str()).or(owner["name"].as_str()).unwrap_or("");
        let label=plain(&record["title"],256);
        let root=record["id"].as_str().is_none_or(str::is_empty);
        let live=if root && owner["pid"].is_number() && owner["pid"]==foreground["pid"] && owner["name"]==foreground["name"] {super::engine::program_title(process,status,title)} else {String::new()};
        let label=stable_title(&if !live.is_empty() {live} else {label});
        let mut item=json!({"session":session["session_id"],"sessionLabel":plain(&session["label"],256),
            "window":window["window_id"],"windowLabel":plain(&window["label"],256),
            "block":block["block_id"],"blockLabel":plain(&block["label"],256),
            "id":plain(&record["id"],128),"app":app,"owner":plain(&owner["name"],64),"ownerPid":owner["pid"],"reportedTitle":plain(&record["title"],256),
            "title":label,"message":plain(&record["msg"],2048),"state":state,"kind":plain(&record["kind"],32),
            "progress":record["progress"].as_u64().filter(|p|*p<=100),"updatedAt":plain(&record["updated_at"],64)});
        let eligible=root && state=="working" && owner["name"]=="codex" && owner["pid"].is_number() &&
            owner["pid"]==foreground["pid"] && owner["name"]==foreground["name"];
        item["codexTitleEligible"]=json!(eligible);
        item["codexTitleAttention"]=json!(eligible && title["owner"]["pid"]==owner["pid"] && title["owner"]["name"]==owner["name"] && codex_attention_title(title["title"].as_str().unwrap_or("")));
        item["attentionKey"]=json!(json!([item["session"],item["block"],item["id"]]).to_string());
        let stamp=json!([item["state"],item["kind"],item["updatedAt"],item["message"],item["ownerPid"]]).to_string();
        let hash=stamp.bytes().fold(0xcbf29ce484222325u64,|h,b|(h^u64::from(b)).wrapping_mul(0x100000001b3));
        item["attentionVersion"]=json!(format!("{hash:016x}"));
        Some(item)
    }).collect()
}
/// Only the root report of this exact process follows the terminal's OSC title.
pub fn apply_title(items: &mut Value,session: &str,block: &str,event: &Value)->bool {
    let Some(records)=items.as_array_mut() else {return false;};
    let mut changed=false;
    for record in records {
        if record["session"]==session && record["block"]==block && record["id"].as_str().is_none_or(str::is_empty)
            && event["owner"]["pid"].is_number() && record["ownerPid"]==event["owner"]["pid"] && record["owner"]==event["owner"]["name"] {
            let title=stable_title(&plain(&event["title"],256));
            let next=if title.is_empty() {record["reportedTitle"].clone()} else {json!(title)};
            if record["title"]!=next {record["title"]=next;changed=true;}
            if record["codexTitleEligible"]==true {
                let attention=json!(codex_attention_title(event["title"].as_str().unwrap_or("")));
                if record["codexTitleAttention"]!=attention {record["codexTitleAttention"]=attention;changed=true;}
            }
        }
    }
    changed
}
pub fn pane_key(session: &str,block: &str)->String {json!([session,block]).to_string()}
pub fn attention_count(items: &Value,seen: &Value)->usize {
    items.as_array().into_iter().flatten().filter(|item| {
        matches!(item["state"].as_str(),Some("blocked"|"error"|"done")) && item["attentionKey"].as_str().is_some_and(|key|seen.get(key)!=Some(&item["attentionVersion"]))
    }).count()
}
async fn open(endpoint: Endpoint)->Result<(Control,Value)> {
    let mut control=Control::connect(endpoint,None,Trace::default()).await?;
    control.call("client.set_label",json!({"label":"rex-android-activity"})).await?;
    // Passive server attachment permits read-only methods without joining sessions.
    let sessions=control.call("session.attach_all",json!({"event_scope":"session"})).await?["sessions"].clone();
    Ok((control,sessions))
}
async fn read(control: &mut Control,pane: &Value)->Result<Vec<Value>> {
    let session=&pane["session"];let window=&pane["window"];let block=&pane["block"];
    let sid=session["session_id"].as_str().context("missing session")?;
    let bid=block["block_id"].as_str().context("missing block")?;
    let status=match control.terminal(sid,bid,"program_status",json!({})).await {
        Ok(v)=>v,
        Err(e) if e.downcast_ref::<rex_core::control::RpcError>().is_some_and(|e|matches!(e.code.as_str(),"session_not_found"|"not_found"))=>return Ok(Vec::new()),
        Err(e)=>return Err(e),
    };
    if status["records"].as_array().is_none_or(Vec::is_empty) {return Ok(Vec::new());}
    let process=control.terminal(sid,bid,"process",json!({})).await?;
    let title=control.terminal(sid,bid,"title",json!({})).await.unwrap_or(Value::Null);
    Ok(records(&process,&status,&title,session,window,block))
}
pub async fn scan(endpoint: Endpoint)->Result<Value> {
    tokio::time::timeout(Duration::from_secs(15),async {
        let (mut control,sessions)=open(endpoint).await?;
        let mut items=Vec::new();let mut panes=Vec::new();let mut partial=false;
        let deadline=tokio::time::Instant::now()+Duration::from_secs(10);
        'sessions: for (index,session) in sessions.as_array().into_iter().flatten().enumerate() {
            if index>=64 || panes.len()>=256 || tokio::time::Instant::now()>deadline {partial=true;break;}
            let Some(sid)=session["session_id"].as_str() else {continue;};
            let view=match control.call("session.view",json!({"session_id":sid})).await {Ok(v)=>v,Err(_)=>{partial=true;continue;}};
            for window in view["windows"].as_array().into_iter().flatten() {
                for block in window["layers"].as_array().into_iter().flatten().flat_map(|l|l["blocks"].as_array().into_iter().flatten()) {
                    if block["creator_name"]!="com.superlogical.terminal" {continue;}
                    if panes.len()>=256 || items.len()>=1024 || tokio::time::Instant::now()>deadline {partial=true;break 'sessions;}
                    let pane=json!({"session":{"session_id":sid,"label":plain(&session["label"],256)},
                        "window":{"window_id":window["window_id"],"label":plain(&window["label"],256)},
                        "block":{"block_id":block["block_id"],"label":plain(&block["label"],256)}});
                    match read(&mut control,&pane).await {Ok(records)=>items.extend(records.into_iter().take(1024-items.len())),Err(_)=>partial=true}
                    panes.push(pane);
                }
            }
        }
        let _=control.close().await;
        Ok::<_,anyhow::Error>(json!({"full":true,"items":items,"panes":panes,"partial":partial}))
    }).await.context("Activity refresh timed out")?
}
pub async fn refresh(endpoint: Endpoint,panes: Vec<Value>)->Result<Value> {
    tokio::time::timeout(Duration::from_secs(15),async {
        let (mut control,_)=open(endpoint).await?;let mut items=Vec::new();let mut replaced=Vec::new();let mut partial=false;
        for pane in panes.into_iter().take(256) {
            match read(&mut control,&pane).await {
                Ok(records)=>{replaced.push(pane_key(pane["session"]["session_id"].as_str().unwrap_or(""),pane["block"]["block_id"].as_str().unwrap_or("")));items.extend(records.into_iter().take(1024-items.len()));},
                Err(_)=>partial=true,
            }
            if items.len()>=1024 {partial=true;break;}
        }
        let _=control.close().await;
        Ok::<_,anyhow::Error>(json!({"full":false,"items":items,"replace":replaced,"partial":partial}))
    }).await.context("Activity refresh timed out")?
}
#[cfg(test)] mod tests {
    use super::*;
    #[test] fn live_owners_are_checked_but_completed_records_survive_exit() {
        let process=json!({"foreground":{"pid":2,"name":"fish"}});
        let status=json!({"records":[
            {"state":"working","owner":{"pid":1,"name":"codex"}},
            {"state":"blocked","owner":{"pid":2,"name":"other"}},
            {"state":"done","owner":{"pid":1,"name":"codex"},"title":"Ready","msg":"Result"},
            {"state":"error","owner":{"pid":1,"name":"codex"}},
            {"state":"future"},{"state":"working","owner":null}]});
        let items=records(&process,&status,&json!({}),&json!({"session_id":"s"}),&json!({"window_id":"w"}),&json!({"block_id":"b"}));
        assert_eq!(items.len(),3);assert_eq!(items[0]["state"],"done");assert_eq!(items[0]["app"],"codex");
        assert!(items.iter().all(|v|v["session"]=="s" && v["block"]=="b"));
    }
    #[test] fn root_titles_follow_live_osc_but_children_and_exited_owners_keep_their_titles() {
        let process=json!({"foreground":{"pid":2,"name":"codex"}});
        let status=json!({"records":[
            {"state":"working","owner":{"pid":2,"name":"codex"},"title":"Status title"},
            {"state":"blocked","id":"child","owner":{"pid":2,"name":"codex"},"title":"Child task"},
            {"state":"done","owner":{"pid":1,"name":"codex"},"title":"Previous result"}]});
        let mut items=json!(records(&process,&status,&json!({"owner":{"pid":2,"name":"codex"},"title":"Live task"}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(items[0]["title"],"Live task");assert_eq!(items[1]["title"],"Child task");assert_eq!(items[2]["title"],"Previous result");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":2,"name":"codex"},"title":"⠙ Changed task"}));
        assert_eq!(items[0]["title"],"Changed task");assert_eq!(items[1]["title"],"Child task");assert_eq!(items[2]["title"],"Previous result");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":99,"name":"codex"},"title":"Wrong owner"}));
        assert_eq!(items[0]["title"],"Changed task");
        apply_title(&mut items,"other","b",&json!({"owner":{"pid":2,"name":"codex"},"title":"Wrong session"}));
        assert_eq!(items[0]["title"],"Changed task");
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":2,"name":"codex"},"title":""}));
        assert_eq!(items[0]["title"],"Status title");
    }

    #[test] fn seen_stamps_ignore_title_animation_and_new_reports_realert() {
        let process=json!({"foreground":{"pid":1,"name":"codex"}});
        let report=|msg:&str|json!({"records":[{"state":"blocked","kind":"permission","owner":{"pid":1,"name":"codex"},"msg":msg,"updated_at":"2026-11-02T00:00:00Z"}]});
        let mut items=json!(records(&process,&report("Approve"),&json!({}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(attention_count(&items,&json!({})),1);
        let key=items[0]["attentionKey"].as_str().unwrap().to_owned();let seen=json!({key:items[0]["attentionVersion"]});
        assert_eq!(attention_count(&items,&seen),0);
        apply_title(&mut items,"s","b",&json!({"owner":{"pid":1,"name":"codex"},"title":"⠋ Task"}));
        assert!(!apply_title(&mut items,"s","b",&json!({"owner":{"pid":1,"name":"codex"},"title":"⠙ Task"})));
        assert_eq!(attention_count(&items,&seen),0);
        let changed=json!(records(&process,&report("A new approval"),&json!({}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(attention_count(&changed,&seen),1);
    }

    #[test] fn completions_badge_until_seen_and_new_finishes_relight_it() {
        for app in ["codex","claude"] {
            let process=json!({"foreground":{"pid":7,"name":app}});
            let report=|state:&str,msg:&str|json!({"records":[{"state":state,"owner":{"pid":7,"name":app},"msg":msg}]});
            let make=|state:&str,msg:&str|json!(records(&process,&report(state,msg),&json!({}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
            let mut items=make("done","Result ready");
            assert_eq!(attention_count(&items,&json!({})),1);
            let key=items[0]["attentionKey"].as_str().unwrap().to_owned();let seen=json!({key:items[0]["attentionVersion"]});
            assert_eq!(attention_count(&items,&seen),0);
            apply_title(&mut items,"s","b",&json!({"owner":{"pid":7,"name":app},"title":"New animated title"}));
            assert_eq!(attention_count(&items,&seen),0);
            assert_eq!(attention_count(&make("done","Next result"),&seen),1);
            for state in ["working","idle"] {assert_eq!(attention_count(&make(state,""),&json!({})),0);}
        }
    }

    #[test] fn codex_attention_signal_is_explicit_owned_root_work_and_clears_on_resume() {
        let process=json!({"foreground":{"pid":7,"name":"codex"}});
        let status=json!({"records":[{"state":"working","owner":{"pid":7,"name":"codex"}},
            {"state":"working","id":"child","owner":{"pid":7,"name":"codex"}}]});
        for title in ["[!] Action Required | Task","[ . ] Action Required | Task","[.  ] Action Required | Task"] {
            let mut items=json!(records(&process,&status,&json!({"title":title,"owner":{"pid":7,"name":"codex"}}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
            assert_eq!(items[0]["state"],"working"); // Native emits a guarded signal; Kotlin preference controls inference.
            assert_eq!(items[0]["codexTitleAttention"],true);assert_eq!(items[1]["codexTitleAttention"],false);
            assert!(!apply_title(&mut items,"s","b",&json!({"title":"Normal","owner":{"pid":8,"name":"codex"}})));
            assert!(apply_title(&mut items,"s","b",&json!({"title":"⠋ Task","owner":{"pid":7,"name":"codex"}})));
            assert_eq!(items[0]["codexTitleAttention"],false);
        }
        for title in ["Action Required | Task","[abc] Action Required | Task","[!] Discuss Action Required | Task","[!] Action Required","[ ] Action Required | Task"] {assert!(!codex_attention_title(title));}
        let wrong=json!(records(&process,&status,&json!({"title":"[!] Action Required | Task","owner":{"pid":8,"name":"codex"}}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(wrong[0]["codexTitleAttention"],false);
        let claude=json!(records(&json!({"foreground":{"pid":7,"name":"claude"}}),&json!({"records":[{"state":"working","owner":{"pid":7,"name":"claude"}}]}),&json!({"title":"[!] Action Required | Task","owner":{"pid":7,"name":"claude"}}),&json!({"session_id":"s"}),&json!({}),&json!({"block_id":"b"})));
        assert_eq!(claude[0]["codexTitleEligible"],false);
    }

}
