//! Safe, single-thread-owned libghostty-vt replica for Rex's terminal stream.
use crate::snapshot::SnapshotBuffer;
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::{
    ffi::c_void,
    marker::PhantomData,
    ptr::{self, NonNull},
    rc::Rc,
};

type ApcFn = unsafe extern "C" fn(*mut c_void, *const u8, usize);
type CellFn = unsafe extern "C" fn(
    *mut c_void,
    u16,
    u16,
    *const u32,
    usize,
    *const u8,
    *const u8,
    u32,
    u8,
    *const u8,
    usize,
);
#[repr(C)]
struct RawPlacement {
    image_id: u32,
    col: i32,
    row: i32,
    cols: u32,
    rows: u32,
    offset_x: u32,
    offset_y: u32,
    width: u32,
    height: u32,
    src_x: u32,
    src_y: u32,
    src_width: u32,
    src_height: u32,
    z: i32,
}
type PlacementFn = unsafe extern "C" fn(*mut c_void, *const RawPlacement);

#[repr(C)]
struct RawImageData {
    image_id: u32,
    generation: u64,
    width: u32,
    height: u32,
    format: u32,
    pixels: *const u8,
    pixel_len: usize,
}
type ImageFn = unsafe extern "C" fn(*mut c_void, *const RawImageData);
#[repr(C)]
#[derive(Default)]
struct RawFrame {
    columns: u16,
    rows: u16,
    cursor_x: u16,
    cursor_y: u16,
    cursor_visible: u8,
    foreground: [u8; 3],
    background: [u8; 3],
    scroll_total: u64,
    scroll_offset: u64,
    scroll_visible: u64,
    input_flags: u8,
    full: u8,
}
unsafe extern "C" {
    fn rp_new(cols: u16, rows: u16, out: *mut *mut c_void) -> i32;
    fn rp_decode(bytes: *const u8, len: usize, out: *mut *mut c_void, offset: *mut usize) -> i32;
    fn rp_free(term: *mut c_void);
    fn rp_write(
        term: *mut c_void,
        bytes: *const u8,
        len: usize,
        consumed: *mut usize,
        callback: ApcFn,
        userdata: *mut c_void,
    ) -> i32;
    fn rp_resize(term: *mut c_void, cols: u16, rows: u16, cw: u32, ch: u32) -> i32;
    fn rp_color(term: *mut c_void, which: i32, rgb: *const u8) -> i32;
    fn rp_palette(term: *mut c_void, index: u8, rgb: *const u8) -> i32;
    fn rp_text(term: *mut c_void, out: *mut *mut u8, len: *mut usize) -> i32;
    fn rp_encode(term: *mut c_void, out: *mut *mut u8, len: *mut usize) -> i32;
    fn rp_bytes_free(bytes: *mut u8, len: usize);
    fn rp_scroll(term: *mut c_void, lines: i32, bottom: i32);
    fn rp_input_flags(term: *mut c_void, flags: *mut u8) -> i32;
    fn rp_wheel(
        term: *mut c_void,
        down: i32,
        col: i32,
        row: i32,
        cw: u32,
        ch: u32,
        out: *mut u8,
        capacity: usize,
        len: *mut usize,
    ) -> i32;
    fn rp_click(
        term: *mut c_void,
        col: i32,
        row: i32,
        cw: u32,
        ch: u32,
        out: *mut u8,
        capacity: usize,
        len: *mut usize,
    ) -> i32;
    fn rp_frame(
        term: *mut c_void,
        frame: *mut RawFrame,
        callback: CellFn,
        placement_callback: PlacementFn,
        image_callback: ImageFn,
        userdata: *mut c_void,
    ) -> i32;
    fn rp_frame_dirty(
        term: *mut c_void,
        frame: *mut RawFrame,
        callback: CellFn,
        placement_callback: PlacementFn,
        image_callback: ImageFn,
        userdata: *mut c_void,
    ) -> i32;
    fn rp_next_full(term: *mut c_void);
    fn rp_is_held(term: *mut c_void) -> i32;
}
fn check(code: i32, action: &str) -> Result<()> {
    ensure!(code == 0, "libghostty {action} failed (result {code})");
    Ok(())
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImagePlacement {
    pub image_id: u32,
    pub col: i32,
    pub row: i32,
    pub cols: u32,
    pub rows: u32,
    pub offset_x: u32,
    pub offset_y: u32,
    pub width: u32,
    pub height: u32,
    pub src_x: u32,
    pub src_y: u32,
    pub src_width: u32,
    pub src_height: u32,
    pub z: i32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImagePayload {
    pub image_id: u32,
    pub generation: u64,
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Cell {
    pub x: u16,
    pub y: u16,
    pub text: String,
    pub foreground: [u8; 3],
    pub background: [u8; 3],
    pub attributes: u32,
    pub width: u8,
    pub hyperlink: Option<String>,
}
#[derive(Debug)]
pub struct Frame {
    pub columns: u16,
    pub rows: u16,
    pub cursor: Option<(u16, u16)>,
    /// The cursor position even while hidden; TUIs such as pi park it at their input.
    pub cursor_at: Option<(u16, u16)>,
    pub foreground: [u8; 3],
    pub background: [u8; 3],
    /// True when `cells` holds every row, so a reader may replace its whole screen.
    pub full: bool,
    pub cells: Vec<Cell>,
    /// Pixels whose generation changed since the previous frame of this terminal.
    /// A repainting program must not copy megabytes across the wire twice; the
    /// renderer keeps the last payload it received for every image id.
    pub images: Vec<ImagePayload>,
    pub placements: Vec<ImagePlacement>,
    pub scroll_total: u64,
    pub scroll_offset: u64,
    pub scroll_visible: u64,
    pub input_flags: u8,
}
impl Frame {
    pub fn text(&self) -> String {
        (0..self.rows)
            .map(|y| {
                self.cells
                    .iter()
                    .filter(|c| c.y == y && c.width > 0)
                    .map(|c| if c.text.is_empty() { " " } else { &c.text })
                    .collect::<String>()
                    .trim_end()
                    .to_owned()
            })
            .collect::<Vec<_>>()
            .join("\n")
            .trim_end()
            .to_owned()
    }
    /// ANSI output is only a diagnostic renderer; Android will draw these cells itself.
    pub fn ansi(&self, local_cols: u16, local_rows: u16) -> Vec<u8> {
        use std::fmt::Write;
        let mut s = String::from("\x1b[?25l\x1b[0m\x1b[2J\x1b[H");
        for c in &self.cells {
            if c.x >= local_cols || c.y >= local_rows || c.width == 0 {
                continue;
            }
            let (fg, bg) = if c.attributes & 8 != 0 {
                (c.background, c.foreground)
            } else {
                (c.foreground, c.background)
            };
            let _ = write!(
                s,
                "\x1b[{};{}H\x1b[0;38;2;{};{};{};48;2;{};{};{}m",
                c.y + 1,
                c.x + 1,
                fg[0],
                fg[1],
                fg[2],
                bg[0],
                bg[1],
                bg[2]
            );
            for (mask, code) in [(1, 1), (2, 3), (4, 2), (16, 8), (32, 9), (64, 4)] {
                if c.attributes & mask != 0 {
                    let _ = write!(s, "\x1b[{code}m");
                }
            }
            if c.text.is_empty() {
                s.push(' ');
            } else {
                s.push_str(&c.text);
            }
        }
        s.push_str("\x1b[0m");
        if let Some((x, y)) = self
            .cursor
            .filter(|(x, y)| *x < local_cols && *y < local_rows)
        {
            let _ = write!(s, "\x1b[{};{}H\x1b[?25h", y + 1, x + 1);
        }
        s.into_bytes()
    }
}

pub struct Terminal {
    handle: NonNull<c_void>,
    _owner_thread: PhantomData<Rc<()>>,
}
impl Drop for Terminal {
    fn drop(&mut self) {
        unsafe {
            rp_free(self.handle.as_ptr());
        }
    }
}
impl Terminal {
    pub fn new(columns: u16, rows: u16) -> Result<Self> {
        ensure!(
            columns > 0 && rows > 0,
            "terminal dimensions must be positive"
        );
        let mut handle = ptr::null_mut();
        check(unsafe { rp_new(columns, rows, &mut handle) }, "create")?;
        Ok(Self {
            handle: NonNull::new(handle).context("null terminal")?,
            _owner_thread: PhantomData,
        })
    }
    pub fn decode(bytes: &[u8]) -> Result<(Self, usize)> {
        let mut handle = ptr::null_mut();
        let mut offset = 0;
        check(
            unsafe { rp_decode(bytes.as_ptr(), bytes.len(), &mut handle, &mut offset) },
            "decode snapshot",
        )?;
        Ok((
            Self {
                handle: NonNull::new(handle).context("null decoded terminal")?,
                _owner_thread: PhantomData,
            },
            offset,
        ))
    }
    fn step(&mut self, bytes: &[u8]) -> Result<(usize, Vec<Vec<u8>>)> {
        unsafe extern "C" fn apc(userdata: *mut c_void, bytes: *const u8, len: usize) {
            // Synchronous callback, borrowed bytes copied before native code returns.
            unsafe {
                (&mut *userdata.cast::<Vec<Vec<u8>>>())
                    .push(std::slice::from_raw_parts(bytes, len).to_vec());
            }
        }
        let mut consumed = 0;
        let mut events = Vec::<Vec<u8>>::new();
        check(
            unsafe {
                rp_write(
                    self.handle.as_ptr(),
                    bytes.as_ptr(),
                    bytes.len(),
                    &mut consumed,
                    apc,
                    (&mut events as *mut Vec<Vec<u8>>).cast(),
                )
            },
            "write VT",
        )?;
        ensure!(
            consumed > 0 && consumed <= bytes.len(),
            "Ghostty parser made no progress"
        );
        Ok((consumed, events))
    }
    pub fn write(&mut self, mut bytes: &[u8]) -> Result<()> {
        while !bytes.is_empty() {
            let (n, _) = self.step(bytes)?;
            bytes = &bytes[n..];
        }
        Ok(())
    }
    pub fn resize(&mut self, cols: u16, rows: u16, cw: u32, ch: u32) -> Result<()> {
        ensure!(cols > 0 && rows > 0, "invalid resize");
        check(
            unsafe { rp_resize(self.handle.as_ptr(), cols, rows, cw, ch) },
            "resize",
        )
    }
    fn buffer(&mut self, encode: bool) -> Result<Vec<u8>> {
        let mut bytes = ptr::null_mut();
        let mut len = 0;
        let code = unsafe {
            if encode {
                rp_encode(self.handle.as_ptr(), &mut bytes, &mut len)
            } else {
                rp_text(self.handle.as_ptr(), &mut bytes, &mut len)
            }
        };
        check(code, if encode { "encode snapshot" } else { "format" })?;
        let result = if len == 0 {
            Vec::new()
        } else {
            unsafe { std::slice::from_raw_parts(bytes, len) }.to_vec()
        };
        unsafe {
            rp_bytes_free(bytes, len);
        }
        Ok(result)
    }
    pub fn text(&mut self) -> Result<String> {
        Ok(String::from_utf8(self.buffer(false)?)?)
    }
    pub fn encode(&mut self) -> Result<Vec<u8>> {
        self.buffer(true)
    }
    pub fn input_flags(&mut self) -> Result<u8> {
        let mut flags = 0;
        check(
            unsafe { rp_input_flags(self.handle.as_ptr(), &mut flags) },
            "input modes",
        )?;
        Ok(flags)
    }
    pub fn wheel(&mut self, down: bool, col: i32, row: i32, cw: u32, ch: u32) -> Result<Vec<u8>> {
        let mut bytes = [0u8; 64];
        let mut len = 0;
        check(
            unsafe {
                rp_wheel(
                    self.handle.as_ptr(),
                    i32::from(down),
                    col,
                    row,
                    cw,
                    ch,
                    bytes.as_mut_ptr(),
                    bytes.len(),
                    &mut len,
                )
            },
            "wheel encoding",
        )?;
        ensure!(len <= bytes.len(), "mouse encoder exceeded buffer");
        Ok(bytes[..len].to_vec())
    }
    /// Encode a whole tap atomically using the application's negotiated mouse mode.
    pub fn click(&mut self, col: i32, row: i32, cw: u32, ch: u32) -> Result<Vec<u8>> {
        if self.input_flags()? & 1 == 0 {
            return Ok(Vec::new());
        }
        let mut bytes = [0u8; 128];
        let mut len = 0;
        check(
            unsafe {
                rp_click(
                    self.handle.as_ptr(),
                    col,
                    row,
                    cw,
                    ch,
                    bytes.as_mut_ptr(),
                    bytes.len(),
                    &mut len,
                )
            },
            "click encoding",
        )?;
        ensure!(len <= bytes.len(), "mouse encoder exceeded buffer");
        Ok(bytes[..len].to_vec())
    }
    pub fn scroll_viewport(&mut self, lines: i32) {
        unsafe { rp_scroll(self.handle.as_ptr(), lines, 0) };
    }
    pub fn scroll_to_bottom(&mut self) {
        unsafe { rp_scroll(self.handle.as_ptr(), 0, 1) };
    }
    pub fn is_held(&self) -> bool {
        unsafe { rp_is_held(self.handle.as_ptr()) != 0 }
    }
    /// Every row. A reader replaces its whole screen with the result.
    pub fn frame(&mut self) -> Result<Frame> {
        self.collect(false)
    }
    /// Only the rows the terminal marked dirty; other rows are absent from `cells`.
    pub fn frame_dirty(&mut self) -> Result<Frame> {
        self.collect(true)
    }
    /// Ask the next frame for every row, for a reader that has no previous screen.
    pub fn set_full_frame(&mut self) {
        unsafe { rp_next_full(self.handle.as_ptr()) };
    }
    fn collect(&mut self, dirty_only: bool) -> Result<Frame> {
        struct FrameCollector {
            cells: Vec<Cell>,
            images: Vec<ImagePayload>,
            placements: Vec<ImagePlacement>,
        }
        unsafe extern "C" fn cell(
            userdata: *mut c_void,
            x: u16,
            y: u16,
            points: *const u32,
            len: usize,
            fg: *const u8,
            bg: *const u8,
            attributes: u32,
            width: u8,
            uri: *const u8,
            uri_len: usize,
        ) {
            let points = if len == 0 {
                &[]
            } else {
                unsafe { std::slice::from_raw_parts(points, len) }
            };
            let text = points
                .iter()
                .map(|p| char::from_u32(*p).unwrap_or('\u{fffd}'))
                .collect();
            let mut foreground = [0; 3];
            let mut background = [0; 3];
            unsafe {
                let collector = &mut *userdata.cast::<FrameCollector>();
                foreground.copy_from_slice(std::slice::from_raw_parts(fg, 3));
                background.copy_from_slice(std::slice::from_raw_parts(bg, 3));
                collector.cells.push(Cell {
                    x,
                    y,
                    text,
                    foreground,
                    background,
                    attributes,
                    width,
                    hyperlink: if uri_len == 0 {
                        None
                    } else {
                        Some(
                            String::from_utf8_lossy(std::slice::from_raw_parts(uri, uri_len))
                                .into_owned(),
                        )
                    },
                });
            }
        }
        unsafe extern "C" fn on_placement(userdata: *mut c_void, raw: *const RawPlacement) {
            let (collector, raw) = unsafe { (&mut *userdata.cast::<FrameCollector>(), &*raw) };
            collector.placements.push(ImagePlacement {
                image_id: raw.image_id,
                col: raw.col,
                row: raw.row,
                cols: raw.cols,
                rows: raw.rows,
                offset_x: raw.offset_x,
                offset_y: raw.offset_y,
                width: raw.width,
                height: raw.height,
                src_x: raw.src_x,
                src_y: raw.src_y,
                src_width: raw.src_width,
                src_height: raw.src_height,
                z: raw.z,
            });
        }
        unsafe extern "C" fn on_image(userdata: *mut c_void, raw: *const RawImageData) {
            let (collector, raw) = unsafe { (&mut *userdata.cast::<FrameCollector>(), &*raw) };
            if raw.width == 0 || raw.height == 0 || raw.width > 4096 || raw.height > 4096 {
                return;
            }
            let expected_pixels = (raw.width as usize) * (raw.height as usize);
            if raw.pixel_len == 0 || raw.pixels.is_null() {
                return;
            }
            let slice = unsafe { std::slice::from_raw_parts(raw.pixels, raw.pixel_len) };
            let rgba = match raw.format {
                1 => {
                    if slice.len() == expected_pixels * 4 {
                        slice.to_vec()
                    } else {
                        return;
                    }
                }
                0 => {
                    if slice.len() != expected_pixels * 3 {
                        return;
                    }
                    let mut buf = Vec::with_capacity(expected_pixels * 4);
                    for p in slice.chunks_exact(3) {
                        buf.extend_from_slice(&[p[0], p[1], p[2], 255]);
                    }
                    buf
                }
                4 => {
                    if slice.len() != expected_pixels {
                        return;
                    }
                    let mut buf = Vec::with_capacity(expected_pixels * 4);
                    for &g in slice {
                        buf.extend_from_slice(&[g, g, g, 255]);
                    }
                    buf
                }
                3 => {
                    if slice.len() != expected_pixels * 2 {
                        return;
                    }
                    let mut buf = Vec::with_capacity(expected_pixels * 4);
                    for p in slice.chunks_exact(2) {
                        buf.extend_from_slice(&[p[0], p[0], p[0], p[1]]);
                    }
                    buf
                }
                _ => return,
            };
            collector.images.push(ImagePayload {
                image_id: raw.image_id,
                generation: raw.generation,
                width: raw.width,
                height: raw.height,
                rgba,
            });
        }
        let mut raw = RawFrame::default();
        let mut collector = FrameCollector {
            cells: Vec::new(),
            images: Vec::new(),
            placements: Vec::new(),
        };
        let read = if dirty_only { rp_frame_dirty } else { rp_frame };
        check(
            unsafe {
                read(
                    self.handle.as_ptr(),
                    &mut raw,
                    cell,
                    on_placement,
                    on_image,
                    (&mut collector as *mut FrameCollector).cast(),
                )
            },
            "render state",
        )?;
        Ok(Frame {
            full: raw.full != 0,
            columns: raw.columns,
            rows: raw.rows,
            cursor: (raw.cursor_visible & 1 != 0).then_some((raw.cursor_x, raw.cursor_y)),
            cursor_at: (raw.cursor_visible & 2 != 0).then_some((raw.cursor_x, raw.cursor_y)),
            foreground: raw.foreground,
            background: raw.background,
            scroll_total: raw.scroll_total,
            scroll_offset: raw.scroll_offset,
            scroll_visible: raw.scroll_visible,
            input_flags: raw.input_flags,
            cells: collector.cells,
            images: collector.images,
            placements: collector.placements,
        })
    }
    fn theme(&mut self, event: &Value) -> Result<()> {
        for (which, name) in ["foreground", "background", "cursor"].iter().enumerate() {
            let rgb = event
                .get(*name)
                .filter(|v| !v.is_null())
                .map(|v| color(v.as_str().context("theme color must be text")?))
                .transpose()?;
            check(
                unsafe {
                    rp_color(
                        self.handle.as_ptr(),
                        which as i32,
                        rgb.as_ref().map_or(ptr::null(), |v| v.as_ptr()),
                    )
                },
                "set theme color",
            )?;
        }
        check(
            unsafe { rp_color(self.handle.as_ptr(), 3, ptr::null()) },
            "reset palette",
        )?;
        if let Some(entries) = event.get("palette").filter(|v| !v.is_null()) {
            for entry in entries.as_array().context("palette must be an array")? {
                let (index, rgb) = entry
                    .as_str()
                    .context("palette entry must be text")?
                    .split_once('=')
                    .context("palette entry needs index=color")?;
                let index = index.parse::<u8>()?;
                let rgb = color(rgb)?;
                check(
                    unsafe { rp_palette(self.handle.as_ptr(), index, rgb.as_ptr()) },
                    "set palette color",
                )?;
            }
        }
        Ok(())
    }
}
fn color(s: &str) -> Result<[u8; 3]> {
    let s = s.strip_prefix('#').unwrap_or(s);
    ensure!(s.len() == 6 && s.is_ascii(), "invalid RGB color");
    Ok([
        u8::from_str_radix(&s[0..2], 16)?,
        u8::from_str_radix(&s[2..4], 16)?,
        u8::from_str_radix(&s[4..6], 16)?,
    ])
}

pub struct Replica {
    nonce: u64,
    snapshot: SnapshotBuffer,
    terminal: Option<Terminal>,
    pub events: Vec<Value>,
    pub snapshot_bytes: usize,
}
impl Replica {
    pub fn new(nonce: u64) -> Self {
        Self {
            nonce,
            snapshot: SnapshotBuffer::default(),
            terminal: None,
            events: Vec::new(),
            snapshot_bytes: 0,
        }
    }
    pub fn ready(&self) -> bool {
        self.terminal.is_some()
    }
    pub fn terminal(&mut self) -> Result<&mut Terminal> {
        self.terminal.as_mut().context("snapshot is incomplete")
    }
    pub fn feed(&mut self, bytes: &[u8]) -> Result<()> {
        if self.terminal.is_none() {
            let Some((snapshot, tail)) = self.snapshot.push(bytes)? else {
                return Ok(());
            };
            let (terminal, consumed) = Terminal::decode(&snapshot)?;
            ensure!(
                consumed == snapshot.len(),
                "snapshot decoder boundary mismatch"
            );
            self.snapshot_bytes = consumed;
            self.terminal = Some(terminal);
            return self.live(&tail);
        }
        self.live(bytes)
    }
    fn live(&mut self, mut bytes: &[u8]) -> Result<()> {
        while !bytes.is_empty() {
            let (n, apcs) = self.terminal()?.step(bytes)?;
            bytes = &bytes[n..];
            for apc in apcs {
                let Ok(apc) = std::str::from_utf8(&apc) else {
                    continue;
                };
                let Some(json) = apc.strip_prefix(&format!("rex1;{};", self.nonce)) else {
                    continue;
                };
                let event: Value =
                    serde_json::from_str(json).context("invalid nonce-matching Rex APC")?;
                match event["type"].as_str() {
                    Some("resize") => {
                        let cols =
                            u16::try_from(event["cols"].as_u64().context("resize cols missing")?)?;
                        let rows =
                            u16::try_from(event["rows"].as_u64().context("resize rows missing")?)?;
                        let cw = u32::try_from(event["cell_width_px"].as_u64().unwrap_or(0))?;
                        let ch = u32::try_from(event["cell_height_px"].as_u64().unwrap_or(0))?;
                        self.terminal()?.resize(cols, rows, cw, ch)?;
                    }
                    Some("theme") => self.terminal()?.theme(&event)?,
                    _ => (),
                }
                ensure!(
                    self.events.len() < 4096,
                    "too many undrained Rex APC events"
                );
                self.events.push(event);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn restored_scrollback_scrolls_locally_and_keeps_new_output_anchored() {
        let mut original = Terminal::new(30, 4).unwrap();
        for i in 0..40 {
            original
                .write(format!("HISTORY_{i:03}\r\n").as_bytes())
                .unwrap();
        }
        let snapshot = original.encode().unwrap();
        let (mut terminal, _) = Terminal::decode(&snapshot).unwrap();
        let bottom = terminal.frame().unwrap();
        assert!(bottom.scroll_total > bottom.scroll_visible);
        assert!(bottom.text().contains("HISTORY_039"));
        terminal.scroll_viewport(-10000);
        let top = terminal.frame().unwrap();
        assert_eq!(top.scroll_offset, 0);
        assert!(top.text().contains("HISTORY_000"));
        terminal.write(b"NEW_WHILE_SCROLLED\r\n").unwrap();
        assert!(terminal.frame().unwrap().text().contains("HISTORY_000"));
        terminal.scroll_to_bottom();
        assert!(
            terminal
                .frame()
                .unwrap()
                .text()
                .contains("NEW_WHILE_SCROLLED")
        );
        terminal.write(b"\x1b[?1049hALTERNATE").unwrap();
        terminal.scroll_viewport(-1000);
        let alternate = terminal.frame().unwrap();
        assert_eq!(alternate.scroll_total, alternate.scroll_visible);
        assert!(alternate.text().contains("ALTERNATE"));
    }
    #[test]
    fn resize_keeps_an_at_bottom_viewport_pinned_to_new_output() {
        // A keyboard opening shrinks the grid mid-stream. The viewport was pinned to
        // the active area, so reflow and the redraw that follows must stay at the
        // bottom instead of leaving the user parked in history.
        let below = |frame: &Frame| {
            (frame.scroll_total - frame.scroll_visible).saturating_sub(frame.scroll_offset)
        };
        let mut terminal = Terminal::new(80, 24).unwrap();
        for i in 0..200 {
            terminal
                .write(format!("HISTORY_{i:03}\r\n").as_bytes())
                .unwrap();
        }
        assert_eq!(below(&terminal.frame().unwrap()), 0);
        terminal.resize(52, 12, 8, 16).unwrap();
        let shrunk = terminal.frame().unwrap();
        assert_eq!(below(&shrunk), 0, "resize while at bottom keeps the bottom");
        assert!(shrunk.text().contains("HISTORY_199"));
        // The program answers SIGWINCH by clearing and redrawing.
        terminal.write(b"\x1b[2J\x1b[H").unwrap();
        for i in 200..240 {
            terminal
                .write(format!("HISTORY_{i:03}\r\n").as_bytes())
                .unwrap();
        }
        let redrawn = terminal.frame().unwrap();
        assert_eq!(below(&redrawn), 0, "the redraw after a resize stays at bottom");
        assert!(redrawn.text().contains("HISTORY_239"));
    }
    #[test]
    fn resize_keeps_a_scrolled_viewport_on_its_row() {
        let mut terminal = Terminal::new(80, 24).unwrap();
        for i in 0..200 {
            terminal
                .write(format!("HISTORY_{i:03}\r\n").as_bytes())
                .unwrap();
        }
        terminal.scroll_viewport(-10000);
        assert!(terminal.frame().unwrap().text().contains("HISTORY_000"));
        terminal.resize(52, 12, 8, 16).unwrap();
        // Explicitly scrolling back is a different intent than following output, so
        // reflow keeps the row the reader chose rather than jumping to the bottom.
        assert!(terminal.frame().unwrap().text().contains("HISTORY_000"));
    }
    #[test]
    fn restores_binary_snapshot_then_handles_fragmented_vt_and_nonce_apcs() {
        let mut terminal = Terminal::new(40, 6).unwrap();
        terminal
            .write("before \x1b[1;38;2;12;200;90mgreen\x1b[0m λ🦀\r\n".as_bytes())
            .unwrap();
        let snapshot = terminal.encode().unwrap();
        let mut replica = Replica::new(9876543210123456789);
        for byte in &snapshot {
            replica.feed(&[*byte]).unwrap();
        }
        assert_eq!(
            replica.terminal().unwrap().text().unwrap(),
            terminal.text().unwrap()
        );
        let frame = replica.terminal().unwrap().frame().unwrap();
        let g = frame.cells.iter().find(|c| c.text == "g").unwrap();
        assert_eq!(g.foreground, [12, 200, 90]);
        assert_ne!(g.attributes & 1, 0);
        let live = "\x1b_rex1;1;{\"type\":\"resize\",\"cols\":1,\"rows\":1}\x1b\\\x1b_rex1;9876543210123456789;{\"type\":\"resize\",\"cols\":55,\"rows\":9}\x1b\\after 🦀";
        for b in live.as_bytes() {
            replica.feed(&[*b]).unwrap();
        }
        let frame = replica.terminal().unwrap().frame().unwrap();
        assert_eq!((frame.columns, frame.rows), (55, 9));
        assert!(frame.text().contains("after 🦀"));
        assert_eq!(replica.events.len(), 1);
        let theme = "\x1b_rex1;9876543210123456789;{\"type\":\"theme\",\"background\":\"#112233\",\"palette\":[\"2=abcdef\"]}\x1b\\\x1b[32mT";
        replica.feed(theme.as_bytes()).unwrap();
        let frame = replica.terminal().unwrap().frame().unwrap();
        assert_eq!(frame.background, [17, 34, 51]);
        assert_eq!(
            frame
                .cells
                .iter()
                .find(|c| c.text == "T")
                .unwrap()
                .foreground,
            [171, 205, 239]
        );
    }
    #[test]
    fn native_decoder_rejects_corruption_and_truncation() {
        let mut terminal = Terminal::new(20, 4).unwrap();
        terminal.write(b"test").unwrap();
        let mut bytes = terminal.encode().unwrap();
        assert!(Terminal::decode(&bytes[..bytes.len() - 1]).is_err());
        bytes[25] ^= 0x40;
        assert!(Terminal::decode(&bytes).is_err());
    }

    #[test]
    fn snapshot_restores_incomplete_utf8_and_csi_and_alternate_screen() {
        for (prefix, suffix) in [
            (b"\xf0\x9f".as_slice(), b"\xa6\x80".as_slice()),
            (b"\x1b[38;2;12;200;90", b"mX"),
        ] {
            let mut original = Terminal::new(20, 4).unwrap();
            original.write(prefix).unwrap();
            let bytes = original.encode().unwrap();
            let (mut decoded, _) = Terminal::decode(&bytes).unwrap();
            decoded.write(suffix).unwrap();
            original.write(suffix).unwrap();
            assert_eq!(decoded.text().unwrap(), original.text().unwrap());
        }
        let mut original = Terminal::new(20, 4).unwrap();
        original
            .write(b"PRIMARY\x1b[?1049h\x1b[HALTERNATE")
            .unwrap();
        let (mut decoded, _) = Terminal::decode(&original.encode().unwrap()).unwrap();
        assert!(decoded.frame().unwrap().text().contains("ALTERNATE"));
        decoded.write(b"\x1b[?1049l").unwrap();
        assert!(decoded.frame().unwrap().text().contains("PRIMARY"));
    }
    #[test]
    fn frame_retains_color_roles_for_client_side_appearance() {
        let mut terminal = Terminal::new(20, 3).unwrap();
        terminal
            .write(b"A\x1b[31mB\x1b[38;2;12;34;56mC\x1b[0m\x1b[42m ")
            .unwrap();
        let frame = terminal.frame().unwrap();
        let a = &frame.cells[0];
        let b = &frame.cells[1];
        let c = &frame.cells[2];
        let bg = &frame.cells[3];
        assert_ne!(a.attributes & 128, 0);
        assert_ne!(a.attributes & 256, 0);
        assert_ne!(b.attributes & 512, 0);
        assert_eq!((b.attributes >> 11) & 255, 1);
        assert_eq!(c.attributes & (128 | 512), 0);
        assert_eq!(c.foreground, [12, 34, 56]);
        assert_ne!(bg.attributes & 1024, 0);
        assert_eq!((bg.attributes >> 19) & 255, 2);
    }
    #[test]
    fn clicks_obey_mouse_modes_and_pair_press_with_release() {
        let mut terminal = Terminal::new(80, 24).unwrap();
        assert!(terminal.click(4, 5, 10, 20).unwrap().is_empty());
        terminal.write(b"\x1b[?1000h\x1b[?1006h").unwrap();
        assert_eq!(
            terminal.click(4, 5, 10, 20).unwrap(),
            b"\x1b[<0;5;6M\x1b[<0;5;6m"
        );
        assert_eq!(
            terminal.click(-20, 999, 0, 0).unwrap(),
            b"\x1b[<0;1;24M\x1b[<0;1;24m"
        );
        let snapshot = terminal.encode().unwrap();
        let (mut restored, _) = Terminal::decode(&snapshot).unwrap();
        assert_eq!(
            restored.click(79, 23, 10, 20).unwrap(),
            b"\x1b[<0;80;24M\x1b[<0;80;24m"
        );
        restored.write(b"\x1b[?1006l").unwrap();
        assert_eq!(restored.click(4, 5, 10, 20).unwrap(), b"\x1b[M %&\x1b[M#%&");
        restored.write(b"\x1b[?1000l").unwrap();
        assert!(restored.click(4, 5, 10, 20).unwrap().is_empty());
    }

    #[test]
    fn mouse_modes_survive_snapshots_and_encode_wheel_direction() {
        let mut terminal = Terminal::new(80, 24).unwrap();
        terminal
            .write(b"\x1b[?1049h\x1b[?1007l\x1b[?1000h\x1b[?1006h")
            .unwrap();
        assert_eq!(terminal.input_flags().unwrap() & 7, 3);
        assert_eq!(
            terminal.wheel(false, 4, 5, 10, 20).unwrap(),
            b"\x1b[<64;5;6M"
        );
        assert_eq!(
            terminal.wheel(true, 4, 5, 10, 20).unwrap(),
            b"\x1b[<65;5;6M"
        );
        let snapshot = terminal.encode().unwrap();
        let (mut restored, _) = Terminal::decode(&snapshot).unwrap();
        assert_eq!(restored.frame().unwrap().input_flags & 7, 3);
        restored.write(b"\x1b[?1000l\x1b[?1007h").unwrap();
        assert_eq!(restored.input_flags().unwrap() & 7, 6);
    }

    #[test]
    fn osc8_destinations_survive_wrapping_snapshot_and_metadata_only_replacement() {
        let mut terminal = Terminal::new(8, 5).unwrap();
        terminal
            .write(b"\x1b]8;id=docs;https://example.com/one\x1b\\Open docs now\x1b]8;;\x1b\\!")
            .unwrap();
        let first = terminal.frame().unwrap();
        let linked: Vec<_> = first
            .cells
            .iter()
            .filter(|c| c.hyperlink.is_some())
            .collect();
        assert!(linked.len() >= 12);
        assert!(linked.iter().any(|c| c.y > 0));
        assert!(
            linked
                .iter()
                .all(|c| c.hyperlink.as_deref() == Some("https://example.com/one"))
        );
        assert!(
            first
                .cells
                .iter()
                .any(|c| c.text == "!" && c.hyperlink.is_none())
        );
        let snapshot = terminal.encode().unwrap();
        let (mut restored, _) = Terminal::decode(&snapshot).unwrap();
        assert_eq!(first.cells, restored.frame().unwrap().cells);
        restored
            .write(b"\x1b[H\x1b]8;;https://example.com/two\x07Open\x1b]8;;\x07")
            .unwrap();
        let next = restored.frame().unwrap();
        assert_eq!(next.cells[0].text, first.cells[0].text);
        assert_eq!(
            next.cells[0].hyperlink.as_deref(),
            Some("https://example.com/two")
        );
        assert_ne!(next.cells[0], first.cells[0]);
    }

    #[test]
    fn soft_wrap_flags_survive_snapshots_and_distinguish_hard_newlines() {
        let mut terminal = Terminal::new(10, 5).unwrap();
        terminal.write(b"https://example.com/abc\r\nNEXT").unwrap();
        let frame = terminal.frame().unwrap();
        let flags = |row: u16| {
            frame
                .cells
                .iter()
                .find(|c| c.x == 0 && c.y == row)
                .unwrap()
                .attributes
        };
        assert_ne!(flags(0) & (1 << 29), 0);
        assert_ne!(flags(0) & (1 << 27), 0);
        assert_ne!(flags(1) & (1 << 28), 0);
        assert_ne!(flags(1) & (1 << 27), 0);
        assert_ne!(flags(2) & (1 << 28), 0);
        assert_eq!(flags(2) & (1 << 27), 0);
        assert_eq!(flags(3) & (1 << 28), 0);
        let (mut restored, _) = Terminal::decode(&terminal.encode().unwrap()).unwrap();
        assert_eq!(frame.cells, restored.frame().unwrap().cells);
    }

    #[test]
    fn kitty_graphics_png_decodes_and_places_images() {
        let mut terminal = Terminal::new(80, 24).unwrap();
        terminal.resize(80, 24, 8, 16).unwrap();
        let kitty_cmd = b"\x1b_Ga=T,f=100,i=42,q=2;iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==\x1b\\";
        terminal.write(kitty_cmd).unwrap();
        let frame = terminal.frame().unwrap();
        assert_eq!(frame.images.len(), 1);
        let img = &frame.images[0];
        assert_eq!(img.image_id, 42);
        assert_eq!(img.width, 1);
        assert_eq!(img.height, 1);
        assert_eq!(img.rgba.as_slice(), &[255, 0, 0, 255][..]);
        assert_eq!(frame.placements.len(), 1);
        let pl = &frame.placements[0];
        assert_eq!(pl.image_id, 42);
    }

    #[test]
    fn test_tb_chunk() {
        let mut terminal = Terminal::new(100, 50).unwrap();
        terminal.resize(100, 50, 8, 16).unwrap();
        let chunks: Vec<&str> = vec![
            "eJztwTEBAAAAwqDqi24MH6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAeBrip94w"
        ];
        for (i, chunk_str) in chunks.iter().enumerate() {
            let is_last = i == chunks.len() - 1;
            let m = if is_last { 0 } else { 1 };
            let cmd = if i == 0 {
                format!("\x1b_Ga=T,f=32,o=z,s=1280,v=816,t=d,i=1,p=1,C=1,z=0,q=2,m={};{}\x1b\\", m, chunk_str)
            } else {
                format!("\x1b_Gm={};{}\x1b\\", m, chunk_str)
            };
            terminal.write(cmd.as_bytes()).unwrap();
        }
        let frame = terminal.frame().unwrap();
        assert_eq!(frame.images.len(), 1);
        assert_eq!(frame.images[0].image_id, 1);
        assert_eq!(frame.images[0].width, 1280);
        assert_eq!(frame.images[0].height, 816);
        assert_eq!(frame.images[0].rgba.len(), 1280 * 816 * 4);
        assert_eq!(frame.placements.len(), 1);
        assert_eq!(frame.placements[0].image_id, 1);
        assert_eq!(frame.placements[0].width, 1280);
        assert_eq!(frame.placements[0].height, 816);
    }

    #[test]
    fn kitty_pixels_only_cross_once_per_generation() {
        let mut terminal = Terminal::new(80, 24).unwrap();
        terminal.resize(80, 24, 8, 16).unwrap();
        // A repainting program re-sends a whole screen image on every frame. Only a
        // changed generation may copy megabytes across the boundary again.
        terminal
            .write(b"\x1b_Ga=T,f=32,s=1,v=1,i=7;/////w==\x1b\\")
            .unwrap();
        let first = terminal.frame().unwrap();
        assert_eq!(first.images.len(), 1);
        assert_eq!(first.placements.len(), 1);
        let idle = terminal.frame().unwrap();
        assert!(idle.images.is_empty(), "unchanged pixels were copied again");
        assert_eq!(idle.placements.len(), 1, "placements still describe the image");
        terminal
            .write(b"\x1b_Ga=T,f=32,s=1,v=1,i=7;AAD//w==\x1b\\")
            .unwrap();
        let changed = terminal.frame().unwrap();
        assert_eq!(changed.images.len(), 1);
        assert_eq!(changed.images[0].rgba.as_slice(), &[0, 0, 255, 255][..]);
    }

    #[test]
    fn test_many_placements_deduplication() {
        let mut terminal = Terminal::new(100, 50).unwrap();
        terminal
            .write(b"\x1b_Ga=T,f=32,s=1,v=1,i=1;/////w==\x1b\\")
            .unwrap();
        for _ in 0..300 {
            terminal.write(b"\x1b_Ga=p,i=1\x1b\\").unwrap();
        }
        let frame = terminal.frame().unwrap();
        assert_eq!(frame.images.len(), 1);
        assert_eq!(frame.images[0].image_id, 1);
        assert!(frame.placements.len() > 1);
    }
}