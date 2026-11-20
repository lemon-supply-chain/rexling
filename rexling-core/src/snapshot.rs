//! Frame complete decoder operations without interpreting snapshot payloads.
//! Ghostty validates record order, contents and CRCs. Its synchronous reader
//! must never see temporary network starvation (which it treats as fatal EOF).
use anyhow::{Result, ensure};

const LIMIT: usize = 128 * 1024 * 1024;
#[derive(Clone, Copy, Debug)]
pub(crate) struct SnapshotStep {
    end: usize,
    cursor: usize,
    pub finish: bool,
    pub ready: bool,
}
#[derive(Default)]
pub struct SnapshotBuffer {
    bytes: Vec<u8>,
    prefix_scan: usize,
    cursor: usize,
    consumed: usize,
    ready: bool,
    complete: bool,
}
impl SnapshotBuffer {
    pub(crate) fn append(&mut self, chunk: &[u8]) -> Result<()> {
        ensure!(!self.complete, "snapshot already complete");
        ensure!(
            self.bytes
                .len()
                .checked_add(chunk.len())
                .is_some_and(|n| n <= LIMIT),
            "snapshot exceeds 128 MiB"
        );
        self.bytes.extend_from_slice(chunk);
        Ok(())
    }
    fn header(&self, offset: usize) -> Result<Option<(u16, usize)>> {
        if self.bytes.len().saturating_sub(offset) < 10 {
            return Ok(None);
        }
        let tag = u16::from_le_bytes(self.bytes[offset..offset + 2].try_into()?);
        let len = u32::from_le_bytes(self.bytes[offset + 2..offset + 6].try_into()?) as usize;
        let end = offset
            .checked_add(10)
            .and_then(|n| n.checked_add(len))
            .filter(|n| *n <= LIMIT)
            .ok_or_else(|| anyhow::anyhow!("snapshot record exceeds limit"))?;
        Ok(Some((tag, end)))
    }
    pub(crate) fn next_step(&mut self) -> Result<Option<SnapshotStep>> {
        if self.complete {
            return Ok(None);
        }
        if !self.ready {
            if self.prefix_scan == 0 {
                if self.bytes.len() < 10 {
                    return Ok(None);
                }
                ensure!(
                    &self.bytes[..8] == b"GHOSTSNP",
                    "invalid Ghostty snapshot signature"
                );
                ensure!(
                    u16::from_le_bytes(self.bytes[8..10].try_into()?) == 1,
                    "unsupported Ghostty snapshot version"
                );
                self.prefix_scan = 10;
            }
            while let Some((tag, end)) = self.header(self.prefix_scan)? {
                if end > self.bytes.len() {
                    return Ok(None);
                }
                self.prefix_scan = end;
                if tag == 5 {
                    return Ok(Some(SnapshotStep {
                        end,
                        cursor: end,
                        finish: false,
                        ready: true,
                    }));
                }
                ensure!(tag != 6, "snapshot FINISH before READY");
            }
            return Ok(None);
        }
        let mut cursor = self.cursor;
        while let Some((tag, end)) = self.header(cursor)? {
            if end > self.bytes.len() {
                return Ok(None);
            }
            if tag == 4 || !(1..=10).contains(&tag) {
                // HISTORY manifests and optional unknown records precede a
                // page, image, or FINISH in the same decoder_next operation.
                cursor = end;
                continue;
            }
            if tag == 3 {
                // Ghostty reads ahead after a PAGE to attach optional Kitty
                // placements. Supply the next known header too, including
                // any intervening unknown records, or the whole placement.
                let mut following = end;
                while let Some((next, next_end)) = self.header(following)? {
                    if !(1..=10).contains(&next) {
                        if next_end > self.bytes.len() {
                            return Ok(None);
                        }
                        following = next_end;
                        continue;
                    }
                    if next == 10 {
                        if next_end > self.bytes.len() {
                            return Ok(None);
                        }
                        return Ok(Some(SnapshotStep {
                            end: next_end,
                            cursor: next_end,
                            finish: false,
                            ready: false,
                        }));
                    }
                    return Ok(Some(SnapshotStep {
                        end: following + 10,
                        cursor: following,
                        finish: false,
                        ready: false,
                    }));
                }
                return Ok(None);
            }
            // An image payload consumes one record. FINISH can already have
            // its header buffered by the previous PAGE; zero new bytes is OK.
            // Other known tags are passed through so Ghostty rejects ordering.
            return Ok(Some(SnapshotStep {
                end,
                cursor: end,
                finish: tag == 6,
                ready: false,
            }));
        }
        Ok(None)
    }
    pub(crate) fn input(&self, step: SnapshotStep) -> &[u8] {
        &self.bytes[self.consumed..step.end]
    }
    pub(crate) fn advance(&mut self, step: SnapshotStep, consumed: usize) -> Result<()> {
        ensure!(
            self.consumed.checked_add(consumed) == Some(step.end),
            "snapshot decoder boundary mismatch"
        );
        self.consumed = step.end;
        self.cursor = step.cursor;
        self.ready |= step.ready;
        self.complete = step.finish;
        Ok(())
    }
    pub(crate) fn finish(&mut self) -> (usize, Vec<u8>) {
        assert!(self.complete);
        let tail = self.bytes.split_off(self.consumed);
        self.bytes = Vec::new();
        (self.consumed, tail)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn record(tag: u16, payload: &[u8]) -> Vec<u8> {
        let mut b = tag.to_le_bytes().to_vec();
        b.extend((payload.len() as u32).to_le_bytes());
        b.extend([0; 4]);
        b.extend(payload);
        b
    }
    #[test]
    fn framing_survives_every_split_and_preserves_trailing_vt() {
        let mut bytes = b"GHOSTSNP\x01\0".to_vec();
        bytes.extend(record(1, b"terminal"));
        bytes.extend(record(5, b""));
        bytes.extend(record(4, b"manifest"));
        bytes.extend(record(3, b"page"));
        bytes.extend(record(0x4000, b"future"));
        bytes.extend(record(10, b"placements"));
        bytes.extend(record(9, b"pixels"));
        bytes.extend(record(3, b"older page"));
        bytes.extend(record(6, b""));
        let snapshot_len = bytes.len();
        bytes.extend(b"live");
        for split in 0..bytes.len() {
            let mut p = SnapshotBuffer::default();
            let mut finished: Option<(usize, Vec<u8>)> = None;
            for part in [&bytes[..split], &bytes[split..]] {
                if let Some((_, ref mut tail)) = finished {
                    tail.extend_from_slice(part);
                    continue;
                }
                p.append(part).unwrap();
                while let Some(step) = p.next_step().unwrap() {
                    p.advance(step, p.input(step).len()).unwrap();
                    if step.finish {
                        finished = Some(p.finish());
                        break;
                    }
                }
            }
            assert_eq!(finished, Some((snapshot_len, b"live".to_vec())));
        }
    }
    #[test]
    fn rejects_unknown_versions_and_oversized_records() {
        let mut p = SnapshotBuffer::default();
        p.append(b"GHOSTSNP\x02\0").unwrap();
        assert!(p.next_step().is_err());
        let mut p = SnapshotBuffer::default();
        p.append(b"GHOSTSNP\x01\0\x01\0\xff\xff\xff\xff\0\0\0\0")
            .unwrap();
        assert!(p.next_step().is_err());
    }
}
