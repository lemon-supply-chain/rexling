//! Deterministic metrics for the terminal wire pipeline; no network, no device.
//!
//! `cargo test -p rexling-jni --release pipeline_metrics -- --ignored --nocapture`
//!
//! These numbers are a regression baseline. They are recorded in
//! `docs/term-pipeline-metrics.md` next to the exact command that produced them.
use crate::engine::Shared;
use crate::updates;
use base64::{Engine, engine::general_purpose::STANDARD};
use rex_core::ghostty::{Frame, Replica, Terminal};
use std::{
    sync::Arc,
    time::Instant,
};

const COLUMNS: u16 = 88;
const ROWS: u16 = 64;
const CELL: (u32, u32) = (16, 30);
/// The same pixel footprint as the terminal-browser block that motivated the work.
const IMAGE: (u32, u32) = (1408, 1920);
const SAMPLES: usize = 5;

fn millis(start: Instant) -> f64 {
    start.elapsed().as_secs_f64() * 1e3
}

/// One Kitty `a=T,f=32` transmission of `IMAGE`, in 4096-byte chunks, base64 encoded.
/// A different `seed` means different pixels, so every frame is a new generation.
fn image_payload(seed: u8) -> String {
    let (w, h) = IMAGE;
    let mut raw = vec![seed; (w as usize) * (h as usize) * 4];
    raw[0] = seed.wrapping_add(1);
    let encoded = STANDARD.encode(&raw);
    let chunks: Vec<&[u8]> = encoded.as_bytes().chunks(4096).collect();
    let mut out = String::with_capacity(encoded.len() + 128);
    for (index, chunk) in chunks.iter().enumerate() {
        let more = u8::from(index + 1 != chunks.len());
        if index == 0 {
            out.push_str(&format!(
                "\x1b_Ga=T,f=32,s={w},v={h},i=1,p=1,C=1,z=0,q=2,m={more};"
            ));
        } else {
            out.push_str(&format!("\x1b_Gm={more};"));
        }
        out.push_str(std::str::from_utf8(chunk).expect("base64 is ASCII"));
        out.push_str("\x1b\\");
    }
    out
}

/// A replica as the server starts one: a Ghostty snapshot of an 88×64 terminal.
fn fresh() -> Replica {
    let mut terminal = Terminal::new(COLUMNS, ROWS).expect("terminal");
    terminal
        .resize(COLUMNS, ROWS, CELL.0, CELL.1)
        .expect("resize");
    let snapshot = terminal.encode().expect("encode snapshot");
    let mut replica = Replica::new(0xfeed);
    replica.feed(&snapshot).expect("feed snapshot");
    assert!(replica.ready(), "replica must be ready after a snapshot");
    replica
}

fn frame_of(replica: &mut Replica) -> Frame {
    replica.terminal().expect("terminal").frame().expect("frame")
}

fn best(mut run: impl FnMut()) -> f64 {
    let mut best = f64::MAX;
    for _ in 0..SAMPLES {
        let start = Instant::now();
        run();
        best = best.min(millis(start));
    }
    best
}

fn shared(frame: Arc<Frame>) -> Shared {
    Shared {
        revision: 1,
        force_full_frame: false,
        json: "{}".into(),
        activity: Arc::default(),
        pending_previews: std::collections::BTreeMap::new(),
        frame: Some(frame),
        key: "session:block".into(),
        wire: updates::WireBase::default(),
    }
}

fn frame_flag(packet: &[u8]) -> u8 {
    let metadata = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
    packet[16 + metadata]
}

#[test]
#[ignore = "metrics harness; run explicitly with --ignored --nocapture"]
fn pipeline_metrics() {
    let mut lines = Vec::<String>::new();
    let mut report = |name: &str, value: String| lines.push(format!("{name}\t{value}"));

    // --- ingest: how fast a full-screen repaint is parsed --------------------
    let repaint = image_payload(11);
    let mut ingest_target = fresh();
    let ingest_ms = best(|| {
        ingest_target
            .feed(repaint.as_bytes())
            .expect("feed repaint");
    });
    let mb = repaint.len() as f64 / 1e6;
    report("ingest.repaint_mb", format!("{mb:.2}"));
    report(
        "ingest.mb_per_s",
        format!("{:.1}", mb / (ingest_ms.max(0.001) / 1e3)),
    );

    // --- frame(): reading the render state ---------------------------------
    let mut terminal = fresh();
    terminal
        .feed(b"\x1b[1;1Hfirst row text")
        .expect("feed text");
    frame_of(&mut terminal); // establish the render state once

    let idle_ms = best(|| {
        frame_of(&mut terminal);
    });
    let idle = frame_of(&mut terminal);
    report("frame.idle.cells", format!("{}", idle.cells.len()));
    report("frame.idle.placements", format!("{}", idle.placements.len()));
    report("frame.idle.ms", format!("{idle_ms:.3}"));

    let one_row_ms = best(|| {
        terminal
            .feed(b"\x1b[1;1Hexample text on one row")
            .expect("feed");
        frame_of(&mut terminal);
    });
    let one_row = frame_of(&mut terminal);
    report("frame.one_row.cells", format!("{}", one_row.cells.len()));
    report("frame.one_row.ms", format!("{one_row_ms:.3}"));

    let repaint_ms = best(|| {
        terminal.feed(repaint.as_bytes()).expect("feed repaint");
        frame_of(&mut terminal);
    });
    let painted = frame_of(&mut terminal);
    let image_bytes = painted.images.iter().map(|i| i.rgba.len()).sum::<usize>();
    report("frame.repaint.cells", format!("{}", painted.cells.len()));
    report("frame.repaint.placements", format!("{}", painted.placements.len()));
    report("frame.repaint.images", format!("{}", painted.images.len()));
    report("frame.repaint.image_bytes", format!("{image_bytes}"));
    report("frame.repaint.ms", format!("{repaint_ms:.3}"));

    // --- the dirty-row path the engine uses when nothing needs a full screen --
    let mut dirty = fresh();
    dirty
        .feed(b"\x1b[1;1Hfirst row text")
        .expect("feed text");
    frame_of(&mut dirty); // establish the render state, and the full base frame
    let dirty_idle_ms = best(|| {
        dirty.terminal().expect("terminal").frame_dirty().expect("frame");
    });
    let dirty_idle = dirty
        .terminal()
        .expect("terminal")
        .frame_dirty()
        .expect("frame");
    report("frame.dirty_idle.cells", format!("{}", dirty_idle.cells.len()));
    report("frame.dirty_idle.ms", format!("{dirty_idle_ms:.3}"));
    report("frame.dirty_idle.full", format!("{}", u8::from(dirty_idle.full)));

    // Alternate the row so every sample is a real change, then measure one of them.
    let mut left = false;
    let dirty_one_row_ms = best(|| {
        left = !left;
        dirty
            .feed(if left {
                b"\x1b[1;1Hexample text on one row"
            } else {
                b"\x1b[1;1Hexample text on one roW"
            })
            .expect("feed");
        dirty
            .terminal()
            .expect("terminal")
            .frame_dirty()
            .expect("frame");
    });
    left = !left;
    dirty
        .feed(if left {
            b"\x1b[1;1Hexample text on one row"
        } else {
            b"\x1b[1;1Hexample text on one roW"
        })
        .expect("feed");
    let dirty_one_row = dirty
        .terminal()
        .expect("terminal")
        .frame_dirty()
        .expect("frame");
    report(
        "frame.dirty_one_row.cells",
        format!("{}", dirty_one_row.cells.len()),
    );
    report(
        "frame.dirty_one_row.ms",
        format!("{dirty_one_row_ms:.3}"),
    );

    // --- wire packets ------------------------------------------------------
    let base = frame_of(&mut terminal);
    let mut state = shared(Arc::new(base));
    let full = updates::encode(&mut state, 0);
    report("wire.full.bytes", format!("{}", full.len()));
    report("wire.full.flag", format!("{}", frame_flag(&full)));

    state.revision += 1;
    let mut changed = frame_of(&mut terminal);
    changed.cells[1].text = "λ".into();
    state.frame = Some(Arc::new(changed));
    let after = state.revision - 1;
    let delta = updates::encode(&mut state, after);
    report("wire.one_row.bytes", format!("{}", delta.len()));
    report("wire.one_row.flag", format!("{}", frame_flag(&delta)));

    state.revision += 1;
    let after = state.revision - 1;
    let idle_packet = updates::encode(&mut state, after);
    report("wire.idle.bytes", format!("{}", idle_packet.len()));
    report(
        "wire.idle.flag",
        idle_packet
            .first()
            .map(|_| frame_flag(&idle_packet).to_string())
            .unwrap_or_else(|| "-".into()),
    );

    // A frame that really carries new pixels, so the packet includes them.
    let mut fresh_image = fresh();
    fresh_image
        .feed(image_payload(200).as_bytes())
        .expect("feed first image");
    let with_image = frame_of(&mut fresh_image);
    let mut image_state = shared(Arc::new(with_image));
    let image_packet = updates::encode(&mut image_state, 0);
    report("wire.full_image.bytes", format!("{}", image_packet.len()));
    report("wire.full_image.flag", format!("{}", frame_flag(&image_packet)));

    // encode() re-diffs the frame against the previous packet.
    let encode_ms = best(|| {
        let frame = frame_of(&mut fresh_image);
        let mut state = shared(Arc::new(frame));
        state.revision = 2;
        let mut next = frame_of(&mut fresh_image);
        next.cells[0].text = "z".into();
        state.frame = Some(Arc::new(next));
        updates::encode(&mut state, 1);
    });
    report("wire.encode_one_row.ms", format!("{encode_ms:.3}"));

    // A delta built from a dirty frame compares only the rows that arrived.
    let dirty_encode_ms = best(|| {
        let mut probe = fresh();
        probe.feed(b"\x1b[1;1Hfirst row text").expect("feed");
        let base = probe.terminal().expect("terminal").frame().expect("frame");
        let mut state = shared(Arc::new(base));
        state.revision = 2;
        probe
            .feed(b"\x1b[1;1Hexample text on one row")
            .expect("feed");
        let next = probe
            .terminal()
            .expect("terminal")
            .frame_dirty()
            .expect("frame");
        state.frame = Some(Arc::new(next));
        updates::encode(&mut state, 1);
    });
    report(
        "wire.encode_dirty_row.ms",
        format!("{dirty_encode_ms:.3}"),
    );

    // --- budget: how many frames per second this pipeline can carry ---------
    let text_fps = 1000.0 / (one_row_ms + ingest_ms).max(0.001);
    let paint_fps = 1000.0 / repaint_ms.max(0.001);
    report("budget.text_fps", format!("{text_fps:.0}"));
    report("budget.repaint_fps", format!("{paint_fps:.0}"));

    for line in lines {
        println!("METRIC {line}");
    }
}