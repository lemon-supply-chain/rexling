use crate::{
    control::{Control, Trace},
    transport::{self, Endpoint, TERMINAL},
};
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::SinkExt;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;

enum DataCommand {
    Ping(tokio::sync::oneshot::Sender<Result<()>>),
    Close(tokio::sync::oneshot::Sender<Result<()>>),
}

pub struct Data {
    cmd_tx: tokio::sync::mpsc::Sender<DataCommand>,
    chunk_rx: tokio::sync::mpsc::Receiver<Result<Vec<u8>>>,
    reader_handle: tokio::task::JoinHandle<()>,
    token: [u8; 16],
    pub received: u64,
    pub nonce: u64,
    pub resume_ms: u32,
    trace: Trace,
}

impl Drop for Data {
    fn drop(&mut self) {
        self.reader_handle.abort();
    }
}

pub struct Resume {
    token: [u8; 16],
    received: u64,
    nonce: u64,
    trace: Trace,
}
impl Resume {
    pub async fn connect(self, endpoint: &Endpoint) -> Result<Data> {
        let mut message = vec![2];
        message.extend(self.token);
        message.extend(self.received.to_le_bytes());
        Data::handshake(
            endpoint,
            message,
            self.received,
            Some(self.nonce),
            self.trace,
        )
        .await
    }
}
impl Data {
    pub async fn open(
        endpoint: &Endpoint,
        control: &mut Control,
        session: &str,
        block: &str,
        trace: Trace,
    ) -> Result<Self> {
        crate::android_log(&format!("Data::open: calling data.connect session={session}, block={block}"));
        let ticket = control.call("data.connect", json!({"session_id":session,"block_id":block,"data_ref_id":"terminal","subprotocol":TERMINAL})).await?;
        crate::android_log("Data::open: data.connect replied with ticket");
        let bytes = URL_SAFE_NO_PAD.decode(ticket["ticket"].as_str().context("no ticket")?)?;
        ensure!(bytes.len() == 16, "ticket must be 16 bytes");
        let mut hello = vec![1];
        hello.extend(bytes);
        Self::handshake(endpoint, hello, 0, None, trace).await
    }
    pub async fn resume(self, endpoint: &Endpoint) -> Result<Self> {
        self.disconnect().connect(endpoint).await
    }
    pub fn disconnect(self) -> Resume {
        let state = Resume {
            token: self.token,
            received: self.received,
            nonce: self.nonce,
            trace: self.trace.clone(),
        };
        // Dropping without a close frame models a network loss, preserving the stream.
        self.reader_handle.abort();
        state
    }
    async fn handshake(
        endpoint: &Endpoint,
        hello: Vec<u8>,
        received: u64,
        nonce: Option<u64>,
        trace: Trace,
    ) -> Result<Self> {
        crate::android_log("handshake: opening /api/data ws");
        let mut ws = endpoint.open("/api/data", TERMINAL).await?;
        crate::android_log("handshake: /api/data ws opened, sending hello and awaiting welcome");
        trace.record("data.send", json!({"type":if hello[0] == 1 {"HELLO"} else {"RESUME"},"bytes":hello.len(),"stream_offset":received}))?;
        let welcome = tokio::time::timeout(endpoint.timeout, async {
            ws.send(Message::Binary(hello.into())).await?;
            let frame = transport::receive(&mut ws).await?;
            let Message::Binary(bytes) = frame else {
                bail!("expected binary data welcome");
            };
            parse_welcome(&bytes)
        })
        .await
        .context("data handshake timeout")??;
        crate::android_log(&format!("handshake: welcome ok, nonce={}", welcome.nonce));
        if let Some(nonce) = nonce {
            ensure!(nonce == welcome.nonce, "nonce changed on data resume");
        }
        trace.record("data.recv", json!({"type":"WELCOME","bytes":32,"nonce":welcome.nonce,"resume_ms":welcome.resume_ms}))?;

        let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::channel::<DataCommand>(32);
        // Bounded so a stalled renderer cannot grow an unbounded backlog in memory;
        // the server's own spool holds the stream and resume replays the gap.
        let (chunk_tx, chunk_rx) = tokio::sync::mpsc::channel::<Result<Vec<u8>>>(256);

        let reader_handle = tokio::spawn(async move {
            loop {
                tokio::select! {
                    cmd = cmd_rx.recv() => {
                        match cmd {
                            Some(DataCommand::Ping(reply)) => {
                                let res = ws.send(Message::Ping(Vec::new().into())).await.map_err(Into::into);
                                let _ = reply.send(res);
                            }
                            Some(DataCommand::Close(reply)) => {
                                use tokio_tungstenite::tungstenite::protocol::{CloseFrame, frame::coding::CloseCode};
                                let res = ws.send(Message::Close(Some(CloseFrame {
                                    code: CloseCode::Normal,
                                    reason: "".into(),
                                }))).await.map_err(Into::into);
                                let _ = reply.send(res);
                                break;
                            }
                            None => {
                                break;
                            }
                        }
                    }
                    frame_res = transport::receive(&mut ws) => {
                        match frame_res {
                            Ok(Message::Binary(bytes)) => {
                                if bytes.is_empty() || bytes.len() > 65536 {
                                    let _ = chunk_tx.send(Err(anyhow::anyhow!("invalid stream chunk size"))).await;
                                    break;
                                }
                                if chunk_tx.send(Ok(bytes.to_vec())).await.is_err() {
                                    break;
                                }
                            }
                            Ok(_) => {
                                let _ = chunk_tx.send(Err(anyhow::anyhow!("expected binary stream data"))).await;
                                break;
                            }
                            Err(e) => {
                                let _ = chunk_tx.send(Err(e)).await;
                                break;
                            }
                        }
                    }
                }
            }
        });

        Ok(Self {
            cmd_tx,
            chunk_rx,
            reader_handle,
            token: welcome.token,
            received,
            nonce: welcome.nonce,
            resume_ms: welcome.resume_ms,
            trace,
        })
    }
    pub async fn read(&mut self) -> Result<Vec<u8>> {
        let chunk_res = self
            .chunk_rx
            .recv()
            .await
            .context("data reader channel closed")?;
        self.accept(chunk_res)
    }
    /// A chunk that already arrived, without waiting. Rendering fast streams must
    /// never spin on one 64 KiB message per loop iteration.
    pub fn try_read(&mut self) -> Option<Result<Vec<u8>>> {
        match self.chunk_rx.try_recv() {
            Ok(chunk) => Some(self.accept(chunk)),
            Err(tokio::sync::mpsc::error::TryRecvError::Empty) => None,
            Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                Some(Err(anyhow::anyhow!("data reader channel closed")))
            }
        }
    }
    fn accept(&mut self, chunk_res: Result<Vec<u8>>) -> Result<Vec<u8>> {
        let bytes = chunk_res?;
        self.received = self
            .received
            .checked_add(bytes.len() as u64)
            .context("stream offset overflow")?;
        self.trace.record(
            "data.recv",
            json!({"type":"stream","bytes":bytes.len(),"stream_offset":self.received}),
        )?;
        Ok(bytes)
    }
    pub fn summary(&self) -> Value {
        json!({"nonce":self.nonce,"resume_ms":self.resume_ms,"stream_offset":self.received})
    }
    pub async fn ping(&mut self) -> Result<()> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        // A reader busy delivering stream data already answers the server's own pings,
        // so a queued ping is best-effort and never blocks the caller.
        if self.cmd_tx.try_send(DataCommand::Ping(tx)).is_err() {
            return Ok(());
        }
        let _ = tokio::time::timeout(std::time::Duration::from_secs(5), rx).await;
        Ok(())
    }
    pub async fn close(&mut self) -> Result<()> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        if self.cmd_tx.try_send(DataCommand::Close(tx)).is_err() {
            // The reader is mid-chunk; dropping the socket keeps the stream resumable
            // instead of deadlocking the caller behind a full chunk queue.
            self.reader_handle.abort();
            return Ok(());
        }
        if let Ok(reply) = tokio::time::timeout(std::time::Duration::from_secs(2), rx).await {
            reply.context("close dropped")??;
        } else {
            self.reader_handle.abort();
        }
        Ok(())
    }
}

struct Welcome {
    token: [u8; 16],
    resume_ms: u32,
    nonce: u64,
}
fn parse_welcome(bytes: &[u8]) -> Result<Welcome> {
    if bytes.len() == 2 && bytes[0] == 0x82 {
        bail!(
            "data REJECT reason 0x{:02x}; start over with a fresh ticket if resuming",
            bytes[1]
        );
    }
    ensure!(
        bytes.len() == 32 && bytes[0] == 0x81,
        "malformed data WELCOME"
    );
    ensure!(
        bytes[17..20] == [0, 0, 0],
        "nonzero reserved data WELCOME bytes"
    );
    let nonce = u64::from_le_bytes(bytes[24..32].try_into()?);
    ensure!(nonce != 0, "zero channel nonce");
    Ok(Welcome {
        token: bytes[1..17].try_into()?,
        resume_ms: u32::from_le_bytes(bytes[20..24].try_into()?),
        nonce,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn welcome_layout_and_rejection() {
        let mut bytes = vec![0; 32];
        bytes[0] = 0x81;
        bytes[1..17].fill(42);
        bytes[20..24].copy_from_slice(&60000u32.to_le_bytes());
        bytes[24..32].copy_from_slice(&987654321u64.to_le_bytes());
        let w = parse_welcome(&bytes).unwrap();
        assert_eq!(w.token, [42; 16]);
        assert_eq!(w.resume_ms, 60000);
        assert_eq!(w.nonce, 987654321);
        assert!(parse_welcome(&bytes[..31]).is_err());
        bytes[24..32].fill(0);
        assert!(parse_welcome(&bytes).is_err());
        assert!(
            parse_welcome(&[0x82, 7])
                .err()
                .unwrap()
                .to_string()
                .contains("0x07")
        );
    }
}
