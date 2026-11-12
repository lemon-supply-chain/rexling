//! Local JNI format, not Rex wire protocol. One decoder per native handle.
use crate::engine::Shared;
use rex_core::ghostty::{Cell, Frame};
use std::sync::{Arc, Condvar, Mutex};

#[derive(Default)]
pub struct Updates {
    state: Mutex<(u64, bool)>,
    ready: Condvar,
}
impl Updates {
    pub fn publish(&self, revision: u64) {
        if let Ok(mut state) = self.state.lock() {
            state.0 = revision;
            self.ready.notify_all();
        }
    }
    pub fn close(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.1 = true;
            self.ready.notify_all();
        }
    }
    pub fn wait(&self, after: u64, ms: u64) -> i64 {
        let Ok(state) = self.state.lock() else {
            return -1;
        };
        let Ok((state, _)) =
            self.ready
                .wait_timeout_while(state, std::time::Duration::from_millis(ms), |s| {
                    !s.1 && s.0 <= after
                })
        else {
            return -1;
        };
        if state.1 { -1 } else { state.0 as i64 }
    }
}
#[derive(Default)]
pub struct WireBase {
    revision: u64,
    metadata: String,
    activity: Arc<serde_json::Value>,
    key: String,
    frame: Option<Arc<Frame>>,
    known_images: std::collections::HashMap<u32, u64>,
}
fn u16le(out: &mut Vec<u8>, v: u16) {
    out.extend(v.to_le_bytes());
}
fn u32le(out: &mut Vec<u8>, v: u32) {
    out.extend(v.to_le_bytes());
}
fn u64le(out: &mut Vec<u8>, v: u64) {
    out.extend(v.to_le_bytes());
}
fn text(out: &mut Vec<u8>, v: &str) {
    u32le(out, v.len() as u32);
    out.extend(v.as_bytes());
}
fn rgb(v: [u8; 3]) -> u32 {
    0xff000000 | ((v[0] as u32) << 16) | ((v[1] as u32) << 8) | v[2] as u32
}
/// A row the terminal handed out, indexed by viewport row. Rows the render state did
/// not touch are absent, so a dirty frame stays dirty all the way to the wire.
fn rows(frame: &Frame) -> Vec<Option<&[Cell]>> {
    let mut result = vec![None; frame.rows as usize];
    let mut start = 0;
    while start < frame.cells.len() {
        let y = frame.cells[start].y as usize;
        let end = start + frame.cells[start..].iter().take_while(|c| c.y as usize == y).count();
        if let Some(slot) = result.get_mut(y) {
            *slot = Some(&frame.cells[start..end]);
        }
        start = end;
    }
    result
}
pub fn encode(state: &mut Shared, after: u64) -> Vec<u8> {
    if after >= state.revision {
        return Vec::new();
    }
    let base = &state.wire;
    let reset = after != base.revision || base.key != state.key;
    let mut out = b"RXT4".to_vec();
    u64le(&mut out, state.revision);
    let previews = std::mem::take(&mut state.pending_previews);
    let activity_changed = reset || !Arc::ptr_eq(&base.activity, &state.activity);
    if reset || base.metadata != state.json || activity_changed || !previews.is_empty() {
        let mut metadata: serde_json::Value =
            serde_json::from_str(&state.json).expect("internal metadata JSON");
        if activity_changed {
            metadata["activity"] = state.activity.as_ref().clone();
        }
        if !previews.is_empty() {
            metadata["previews"] = serde_json::to_value(previews).expect("preview frames");
        }
        text(&mut out, &metadata.to_string());
    } else {
        text(&mut out, "");
    }
    // The frame the reader now holds; None when this packet carried no screen.
    let delivered: Option<Arc<Frame>> = match &state.frame {
        None => {
            out.push(1);
            None
        }
        Some(frame)
            if !reset
                && base
                    .frame
                    .as_ref()
                    .is_some_and(|old| Arc::ptr_eq(old, frame)) =>
        {
            out.push(0);
            Some(frame.clone())
        }
        Some(frame) => {
            let previous = base
                .frame
                .as_ref()
                .filter(|old| !reset && old.columns == frame.columns && old.rows == frame.rows);
            let next = rows(frame);
            let complete = next.iter().all(Option::is_some);
            // A reader without a base can only be given a whole screen, so ask the
            // terminal for every row and send nothing rather than half a screen.
            if previous.is_none() && !complete {
                state.force_full_frame = true;
                out.push(1);
                return settle(state, out, None);
            }
            // A full packet is only needed for a reader without a base; otherwise the
            // packet is a row delta even when the frame happens to hold every row.
            let full = previous.is_none();
            out.push(if full { 3 } else { 2 });
            u16le(&mut out, frame.columns);
            u16le(&mut out, frame.rows);
            let (x, y) = frame
                .cursor
                .map_or((-1, -1), |(x, y)| (i32::from(x), i32::from(y)));
            out.extend(x.to_le_bytes());
            out.extend(y.to_le_bytes());
            u32le(&mut out, rgb(frame.foreground));
            u32le(&mut out, rgb(frame.background));
            for v in [
                frame.scroll_total,
                frame.scroll_offset,
                frame.scroll_visible,
            ] {
                u64le(&mut out, v);
            }
            out.push(frame.input_flags);
            let old = previous.map(|old| rows(old));
            let changed: Vec<(u16, &[Cell])> = next
                .iter()
                .enumerate()
                .filter_map(|(y, row)| {
                    let row = (*row)?;
                    if full {
                        return Some((y as u16, row));
                    }
                    match old.as_ref().and_then(|old| old[y]) {
                        Some(same) if same == row => None,
                        _ => Some((y as u16, row)),
                    }
                })
                .collect();
            u16le(&mut out, changed.len() as u16);
            for (y, row) in changed {
                u16le(&mut out, y as u16);
                u16le(&mut out, row.len() as u16);
                for cell in row {
                    u16le(&mut out, cell.x);
                    out.push(cell.width);
                    u32le(&mut out, cell.attributes);
                    u32le(&mut out, rgb(cell.foreground));
                    u32le(&mut out, rgb(cell.background));
                    text(&mut out, &cell.text);
                }
                // Sparse contiguous link ranges keep ordinary rows cheap.
                let mut links: Vec<(u16, u16, &str)> = Vec::new();
                for cell in row {
                    if cell.width == 0 {
                        continue;
                    }
                    if let Some(uri) = cell.hyperlink.as_deref() {
                        if let Some(last) = links
                            .last_mut()
                            .filter(|last| last.1 == cell.x && last.2 == uri)
                        {
                            last.1 = cell.x + u16::from(cell.width);
                        } else {
                            links.push((cell.x, cell.x + u16::from(cell.width), uri));
                        }
                    }
                }
                u16le(&mut out, links.len() as u16);
                for (start, end, uri) in links {
                    u16le(&mut out, start);
                    u16le(&mut out, end);
                    text(&mut out, uri);
                }
            }
            let mut new_payloads = Vec::new();
            for img in &frame.images {
                let known =
                    !reset && base.known_images.get(&img.image_id).copied() == Some(img.generation);
                if !known {
                    new_payloads.push(img);
                }
            }
            let total_payload_size: usize = new_payloads.iter().map(|img| img.rgba.len() + 24).sum();
            out.reserve(total_payload_size + frame.placements.len() * 48);
            u16le(&mut out, new_payloads.len() as u16);
            for img in new_payloads {
                u32le(&mut out, img.image_id);
                u64le(&mut out, img.generation);
                u32le(&mut out, img.width);
                u32le(&mut out, img.height);
                u32le(&mut out, img.rgba.len() as u32);
                out.extend_from_slice(&img.rgba);
            }

            u16le(&mut out, frame.placements.len() as u16);
            for pl in &frame.placements {
                u32le(&mut out, pl.image_id);
                out.extend(pl.col.to_le_bytes());
                out.extend(pl.row.to_le_bytes());
                u32le(&mut out, pl.cols);
                u32le(&mut out, pl.rows);
                u32le(&mut out, pl.offset_x);
                u32le(&mut out, pl.offset_y);
                u32le(&mut out, pl.width);
                u32le(&mut out, pl.height);
                u32le(&mut out, pl.src_x);
                u32le(&mut out, pl.src_y);
                u32le(&mut out, pl.src_width);
                u32le(&mut out, pl.src_height);
                out.extend(pl.z.to_le_bytes());
            }
            Some(frame.clone())
        }
    };
    settle(state, out, delivered)
}

/// Record what the reader now holds. `delivered` is the frame this packet carried, so
/// `None` means the reader has no screen base and the next packet must be a full one.
fn settle(state: &mut Shared, out: Vec<u8>, delivered: Option<Arc<Frame>>) -> Vec<u8> {
    let mut known_images = std::collections::HashMap::new();
    if let Some(frame) = &delivered {
        for img in &frame.images {
            known_images.insert(img.image_id, img.generation);
        }
    }
    state.wire = WireBase {
        revision: state.revision,
        metadata: state.json.clone(),
        activity: state.activity.clone(),
        key: state.key.clone(),
        frame: delivered,
        known_images,
    };
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn notification_wakes_for_updates_and_shutdown() {
        let updates = Arc::new(Updates::default());
        let writer = updates.clone();
        let thread = std::thread::spawn(move || {
            writer.publish(4);
        });
        assert_eq!(updates.wait(0, 1000), 4);
        thread.join().unwrap();
        assert_eq!(updates.wait(4, 1), 4);
        updates.close();
        assert_eq!(updates.wait(4, 1), -1);
    }
}

#[cfg(test)]
mod wire_tests {
    use super::*;
    use std::collections::BTreeMap;
    fn frame() -> Frame {
        Frame {
            columns: 100,
            rows: 50,
            full: true,
            cursor: Some((0, 0)),
            cursor_at: Some((0, 0)),
            foreground: [255; 3],
            background: [0; 3],
            cells: (0..50)
                .flat_map(|y| {
                    (0..100).map(move |x| Cell {
                        x,
                        y,
                        text: "x".into(),
                        foreground: [255; 3],
                        background: [0; 3],
                        attributes: 0,
                        width: 1,
                        hyperlink: None,
                    })
                })
                .collect(),
            images: Vec::new(),
            placements: Vec::new(),
            scroll_total: 50,
            scroll_offset: 0,
            scroll_visible: 50,
            input_flags: 0,
        }
    }
    #[test]
    fn packets_diff_against_delivered_frame_and_reset_on_unknown_base() {
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(frame())),
            key: "a".into(),
            wire: WireBase::default(),
            activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        let metadata_len = u32::from_le_bytes(full[12..16].try_into().unwrap()) as usize;
        assert_eq!(full[16 + metadata_len], 3);
        let mut next = frame();
        next.cells[200].text = "λ🦀".into();
        state.frame = Some(Arc::new(next));
        state.revision = 3;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        state.revision = 4;
        let reset = encode(&mut state, 1);
        assert_eq!(
            reset[16 + u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize],
            3
        );
        state.key = "other host pane".into();
        state.revision = 5;
        let reset = encode(&mut state, 4);
        assert_eq!(
            reset[16 + u32::from_le_bytes(reset[12..16].try_into().unwrap()) as usize],
            3
        );
        assert!(encode(&mut state, 5).is_empty());
        println!(
            "wire payload bytes: full={} one-row delta={}",
            full.len(),
            delta.len()
        );
    }
    #[test]
    fn a_closed_notifier_wakes_a_blocked_reader() {
        let updates = Arc::new(Updates::default());
        let reader = updates.clone();
        let thread = std::thread::spawn(move || reader.wait(0, 1000));
        updates.close();
        assert_eq!(thread.join().unwrap(), -1);
    }
    /// Metadata length, then the frame flag, then the changed-row count.
    fn changed_rows(packet: &[u8]) -> u16 {
        let metadata = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
        // columns, rows, cursor x/y, fg, bg, three scrollbar words, input flags
        let at = 16 + metadata + 1 + 2 + 2 + 4 + 4 + 4 + 4 + 24 + 1;
        u16::from_le_bytes(packet[at..at + 2].try_into().unwrap())
    }
    /// Only some rows, as the terminal hands them out for a dirty frame.
    fn dirty(rows: &[u16]) -> Frame {
        let mut frame = frame();
        frame.full = false;
        frame.cells.retain(|cell| rows.contains(&cell.y));
        frame
    }

    #[test]
    fn a_dirty_frame_sends_only_the_rows_it_carries() {
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            activity: Arc::default(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(frame())),
            key: "a".into(),
            wire: WireBase::default(),
        };
        let full = encode(&mut state, 0);
        assert_eq!(full[16 + u32::from_le_bytes(full[12..16].try_into().unwrap()) as usize], 3);

        let mut next = dirty(&[7]);
        next.cells[0].text = "z".into();
        state.frame = Some(Arc::new(next));
        state.revision = 2;
        let delta = encode(&mut state, 1);
        let metadata = u32::from_le_bytes(delta[12..16].try_into().unwrap()) as usize;
        assert_eq!(delta[16 + metadata], 2, "a dirty frame is a row delta");
        assert_eq!(changed_rows(&delta), 1);
        assert!(delta.len() * 10 < full.len(), "one row must not cost a screen");
    }

    #[test]
    fn a_dirty_frame_without_a_base_waits_for_a_full_one() {
        // A reader that never received the screen cannot apply row 7 alone.
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            activity: Arc::default(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(dirty(&[7]))),
            key: "a".into(),
            wire: WireBase::default(),
        };
        let packet = encode(&mut state, 0);
        let metadata = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
        assert_eq!(packet[16 + metadata], 1, "no half a screen for a reader without a base");
        assert!(state.force_full_frame, "the terminal is asked for every row next time");

        // The engine obeys the request, so the next packet replaces the whole screen.
        state.force_full_frame = false;
        state.revision = 2;
        state.frame = Some(Arc::new(frame()));
        let complete = encode(&mut state, 1);
        let metadata = u32::from_le_bytes(complete[12..16].try_into().unwrap()) as usize;
        assert_eq!(complete[16 + metadata], 3);
        assert_eq!(changed_rows(&complete), 50);
        assert!(!state.force_full_frame);
    }

    #[test]
    fn hyperlink_only_changes_emit_a_sparse_row_delta_and_removal() {
        let mut first = frame();
        for c in &mut first.cells[..3] {
            c.hyperlink = Some("https://example.com/one".into());
        }
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(first)),
            key: "a".into(),
            wire: WireBase::default(),
            activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        assert_eq!(&full[..4], b"RXT4");
        assert_eq!(
            full.windows(b"https://example.com/one".len())
                .filter(|w| *w == b"https://example.com/one")
                .count(),
            1
        );
        let mut next = frame();
        for c in &mut next.cells[..3] {
            c.hyperlink = Some("https://example.com/two".into());
        }
        state.frame = Some(Arc::new(next));
        state.revision = 2;
        let delta = encode(&mut state, 1);
        assert_eq!(delta[16], 2);
        assert!(delta.len() < full.len() / 20);
        assert!(
            delta
                .windows(b"https://example.com/two".len())
                .any(|w| w == b"https://example.com/two")
        );
        assert!(delta.ends_with(&[0, 0]));
        state.frame = Some(Arc::new(frame()));
        state.revision = 3;
        let removed = encode(&mut state, 2);
        assert_eq!(removed[16], 2);
        assert!(removed.ends_with(&[0, 0]));
    }

    #[test]
    fn activity_catalog_is_sent_only_when_changed_and_resets_clear_it() {
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            activity: Arc::new(serde_json::json!([{"title":"Task"}])),
            pending_previews: BTreeMap::new(),
            frame: None,
            key: "a".into(),
            wire: WireBase::default(),
        };
        let metadata = |packet: &[u8]| -> serde_json::Value {
            let n = u32::from_le_bytes(packet[12..16].try_into().unwrap()) as usize;
            serde_json::from_slice(&packet[16..16 + n]).unwrap()
        };
        assert!(metadata(&encode(&mut state, 0))["activity"].is_array());
        state.revision = 2;
        state.json = "{\"titles\":{\"a\":\"Changed\"}}".into();
        assert!(metadata(&encode(&mut state, 1)).get("activity").is_none());
        state.revision = 3;
        state.activity = Arc::default();
        assert!(metadata(&encode(&mut state, 2))["activity"].is_null());
        state.revision = 4;
        assert!(metadata(&encode(&mut state, 1))["activity"].is_null());
    }

    #[test]
    fn images_are_serialized_and_cached_on_subsequent_deltas() {
        let mut first = frame();
        first.images.push(rex_core::ghostty::ImagePayload {
            image_id: 10,
            generation: 1,
            width: 100,
            height: 100,
            rgba: vec![255; 40000],
        });
        first.placements.push(rex_core::ghostty::ImagePlacement {
            image_id: 10,
            col: 5,
            row: 6,
            cols: 20,
            rows: 10,
            offset_x: 0,
            offset_y: 0,
            width: 160,
            height: 160,
            src_x: 0,
            src_y: 0,
            src_width: 100,
            src_height: 100,
            z: 0,
        });
        let mut state = Shared {
            revision: 1,
            force_full_frame: false,
            json: "{}".into(),
            pending_previews: BTreeMap::new(),
            frame: Some(Arc::new(first)),
            key: "a".into(),
            wire: WireBase::default(),
            activity: Arc::default(),
        };
        let full = encode(&mut state, 0);
        assert!(full.len() > 40000);
        assert_eq!(&full[..4], b"RXT4");

        let mut next = frame();
        next.images.push(rex_core::ghostty::ImagePayload {
            image_id: 10,
            generation: 1,
            width: 100,
            height: 100,
            rgba: vec![255; 40000],
        });
        next.placements.push(rex_core::ghostty::ImagePlacement {
            image_id: 10,
            col: 6,
            row: 6,
            cols: 20,
            rows: 10,
            offset_x: 0,
            offset_y: 0,
            width: 160,
            height: 160,
            src_x: 0,
            src_y: 0,
            src_width: 100,
            src_height: 100,
            z: 0,
        });
        state.frame = Some(Arc::new(next));
        state.revision = 2;
        let delta = encode(&mut state, 1);
        assert!(delta.len() < 200);
    }
}
