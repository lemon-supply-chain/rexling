// Public libghostty-vt C ABI only. The Rust-facing ABI is deliberately small.
#include <ghostty/vt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#define STBI_NO_FAILURE_STRINGS
#include "stb_image.h"

typedef void (*ApcFn)(void *, const uint8_t *, size_t);
typedef void (*CellFn)(void *, uint16_t, uint16_t, const uint32_t *, size_t,
                       const uint8_t *, const uint8_t *, uint32_t, uint8_t, const uint8_t *, size_t);
typedef struct {
  uint32_t image_id;
  int32_t col;
  int32_t row;
  uint32_t cols;
  uint32_t rows;
  uint32_t offset_x;
  uint32_t offset_y;
  uint32_t width;
  uint32_t height;
  uint32_t src_x;
  uint32_t src_y;
  uint32_t src_width;
  uint32_t src_height;
  int32_t z;
} RpPlacement;
typedef struct {
  uint32_t image_id;
  uint64_t generation;
  uint32_t width;
  uint32_t height;
  uint32_t format;
  const uint8_t *pixels;
  size_t pixel_len;
} RpImageData;
typedef void (*PlacementFn)(void *, const RpPlacement *);
typedef void (*ImageFn)(void *, const RpImageData *);
typedef struct {
  uint16_t columns, rows, cursor_x, cursor_y;
  uint8_t cursor_visible, foreground[3], background[3];
  uint64_t scroll_total, scroll_offset, scroll_visible;
  uint8_t input_flags;
  /* 1 when every row was walked, so the caller may replace a whole screen. */
  uint8_t full;
} RpFrame;
/* Image pixels cross the boundary once per generation; the engine diffs by generation. */
typedef struct {
  uint32_t image_id;
  uint64_t generation;
} RpImageGen;
typedef struct {
  GhosttyTerminal terminal;
  GhosttyRenderState render;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  GhosttyRenderStateImageIterator images;
  ApcFn callback;
  void *userdata;
  bool held;
  uint64_t hold_started_ms;
  RpImageGen *sent_generations;
  size_t sent_count, sent_capacity;
  /* The write side already knows what it touched; never diff snapshots for that. */
  bool frame_full_next;
  uint16_t frame_columns, frame_rows;
} RpTerminal;

/* A longer hold renders intermediate frames, as a real terminal does. Never
 * touch terminal modes here: this replica only mirrors the server's terminal. */
#define RP_HOLD_RENDER_MS 25

static uint64_t rp_now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static bool rp_is_holding(const RpTerminal *t) {
  return t->held && rp_now_ms() < t->hold_started_ms + RP_HOLD_RENDER_MS;
}

static bool rp_generation_sent(const RpTerminal *t, uint32_t id, uint64_t generation) {
  for (size_t i = 0; i < t->sent_count; i++) {
    if (t->sent_generations[i].image_id == id)
      return t->sent_generations[i].generation == generation;
  }
  return false;
}

static void rp_mark_generation(RpTerminal *t, uint32_t id, uint64_t generation) {
  for (size_t i = 0; i < t->sent_count; i++) {
    if (t->sent_generations[i].image_id == id) {
      t->sent_generations[i].generation = generation;
      return;
    }
  }
  if (t->sent_count == t->sent_capacity) {
    size_t next = t->sent_capacity ? t->sent_capacity * 2 : 16;
    RpImageGen *grown = realloc(t->sent_generations, next * sizeof(*grown));
    if (!grown) return;
    t->sent_generations = grown;
    t->sent_capacity = next;
  }
  t->sent_generations[t->sent_count].image_id = id;
  t->sent_generations[t->sent_count].generation = generation;
  t->sent_count++;
}

static void on_render_hold(GhosttyTerminal terminal, void *userdata, bool held) {
  (void)terminal;
  RpTerminal *t = userdata;
  if (!t) return;
  if (held) t->hold_started_ms = rp_now_ms();
  t->held = held;
}

static bool rp_decode_png(void *userdata, const GhosttyAllocator *allocator,
                          const uint8_t *data, size_t data_len, GhosttySysImage *out) {
  (void)userdata;
  int w = 0, h = 0, channels = 0;
  stbi_uc *decoded = stbi_load_from_memory(data, (int)data_len, &w, &h, &channels, 4);
  if (!decoded) return false;
  size_t pixel_len = (size_t)w * (size_t)h * 4;
  uint8_t *pixels = ghostty_alloc(allocator, pixel_len);
  if (!pixels) {
    stbi_image_free(decoded);
    return false;
  }
  memcpy(pixels, decoded, pixel_len);
  stbi_image_free(decoded);
  out->width = (uint32_t)w;
  out->height = (uint32_t)h;
  out->data = pixels;
  out->data_len = pixel_len;
  return true;
}

void rp_free(RpTerminal *t) {
  if (!t) return;
  free(t->sent_generations);
  ghostty_render_state_image_iterator_free(t->images);
  ghostty_render_state_row_cells_free(t->cells);
  ghostty_render_state_row_iterator_free(t->rows);
  ghostty_render_state_free(t->render);
  ghostty_terminal_free(t->terminal);
  free(t);
}
static void unknown(GhosttyTerminal terminal, void *userdata,
                    const GhosttyTerminalUnknownSequence *sequence) {
  (void)terminal;
  RpTerminal *t = userdata;
  if (sequence->tag == GHOSTTY_TERMINAL_UNKNOWN_SEQUENCE_APC &&
      !sequence->value.apc.truncated && t->callback) {
    GhosttyString s = sequence->value.apc.content;
    t->callback(t->userdata, (const uint8_t *)s.ptr, s.len);
  }
}
static int configure(RpTerminal *t) {
  size_t limit = 65536;
  int r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_SEQUENCE, (const void *)unknown);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_UNKNOWN_MAX_BYTES, &limit);
  if (r == 0) r = ghostty_terminal_set(t->terminal,
      GHOSTTY_TERMINAL_OPT_CONTINUATION_MAX_BYTES, &limit);
  const GhosttyTerminalData defaults[] = {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND_DEFAULT,
      GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND_DEFAULT};
  const GhosttyTerminalOption color_options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND};
  for (int i=0; r==0 && i<2; i++) {
    GhosttyColorRgb value;
    int present=ghostty_terminal_get(t->terminal,defaults[i],&value);
    if (present==GHOSTTY_NO_VALUE) {
      value=(GhosttyColorRgb){i==0?255:0,i==0?255:0,i==0?255:0};
      r=ghostty_terminal_set(t->terminal,color_options[i],&value);
    } else if(present!=0) r=present;
  }
  // No WRITE_PTY callback: the server's canonical terminal answers queries.
  uint64_t kitty_limit = 64 * 1024 * 1024;
  if (r == 0) r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &kitty_limit);
  // A remote terminal must never read files or shared memory on this phone.
  bool remote = false;
  if (r == 0) r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE, &remote);
  if (r == 0) r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM, &remote);
  if (r == 0) r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE, NULL);
  if (r == 0) r = ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_render_hold);
  if (r == 0) ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)rp_decode_png);
  if (r == 0) r = ghostty_render_state_new(NULL, &t->render);
  if (r == 0) r = ghostty_render_state_row_iterator_new(NULL, &t->rows);
  if (r == 0) r = ghostty_render_state_row_cells_new(NULL, &t->cells);
  if (r == 0) r = ghostty_render_state_image_iterator_new(NULL, &t->images);
  return r;
}
int rp_is_held(RpTerminal *t) {
  if (!t) return 0;
  return rp_is_holding(t) ? 1 : 0;
}
int rp_new(uint16_t cols, uint16_t rows, RpTerminal **out) {
  *out = NULL;
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  int r = ghostty_terminal_new(NULL, &t->terminal, cols, rows);
  if (r == 0) r = ghostty_terminal_resize(t->terminal, cols, rows, 8, 16);
  if (r == 0) r = configure(t);
  if (r == 0) r = ghostty_render_state_update(t->render, t->terminal);
  if (r != 0) { rp_free(t); return r; }
  t->frame_full_next = true;
  *out = t;
  return 0;
}
int rp_decode(const uint8_t *bytes, size_t len, RpTerminal **out, size_t *offset) {
  *out = NULL;
  RpTerminal *t = calloc(1, sizeof(*t));
  if (!t) return GHOSTTY_OUT_OF_MEMORY;
  GhosttySnapshotDecoder decoder = NULL;
  int r = ghostty_snapshot_decoder_new_buf(NULL, &decoder, bytes, len);
  if (r == 0) {
    uint64_t kitty_limit = 64 * 1024 * 1024;
    ghostty_snapshot_decoder_set(decoder, GHOSTTY_SNAPSHOT_DECODER_OPT_KITTY_IMAGE_STORAGE_LIMIT, &kitty_limit);
  }
  if (r == 0) r = ghostty_snapshot_decoder_decode(decoder, &t->terminal);
  if (r == 0) r = ghostty_snapshot_decoder_get(decoder,
      GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, offset);
  ghostty_snapshot_decoder_free(decoder);
  if (r == 0) r = configure(t);
  if (r == 0) r = ghostty_render_state_update(t->render, t->terminal);
  if (r != 0) { rp_free(t); return r; }
  t->frame_full_next = true;
  *out = t;
  return 0;
}
int rp_write(RpTerminal *t, const uint8_t *bytes, size_t len, size_t *consumed,
             ApcFn callback, void *userdata) {
  t->callback = callback;
  t->userdata = userdata;
  GhosttyTerminalWriteStop stop = GHOSTTY_INIT_SIZED(GhosttyTerminalWriteStop);
  int r = ghostty_terminal_vt_write_until(t->terminal, bytes, len,
      GHOSTTY_TERMINAL_BOUNDARY_APC_END, &stop);
  t->callback = NULL;
  t->userdata = NULL;
  if (r == GHOSTTY_NO_VALUE) { *consumed = len; return 0; }
  *consumed = stop.consumed;
  return r;
}
int rp_resize(RpTerminal *t, uint16_t cols, uint16_t rows, uint32_t cw, uint32_t ch) {
  if (cw == 0) cw = 8;
  if (ch == 0) ch = 16;
  return ghostty_terminal_resize(t->terminal, cols, rows, cw, ch);
}
int rp_color(RpTerminal *t, int which, const uint8_t *rgb) {
  const GhosttyTerminalOption options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
      GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR,
      GHOSTTY_TERMINAL_OPT_COLOR_PALETTE};
  if (which < 0 || which > 3) return GHOSTTY_INVALID_VALUE;
  GhosttyColorRgb color;
  // RenderState only updates fg/bg together when both defaults are set.
  // These are Ghostty RenderState.empty's built-in fallback colors.
  const uint8_t fallback[3] = {which == 0 ? 255 : 0, which == 0 ? 255 : 0, which == 0 ? 255 : 0};
  if (!rgb && which < 2) rgb = fallback;
  if (rgb) { color.r=rgb[0]; color.g=rgb[1]; color.b=rgb[2]; }
  return ghostty_terminal_set(t->terminal, options[which], rgb ? &color : NULL);
}
int rp_palette(RpTerminal *t, uint8_t index, const uint8_t *rgb) {
  GhosttyColorRgb palette[256];
  int r = ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, palette);
  if (r != 0) return r;
  palette[index] = (GhosttyColorRgb){rgb[0],rgb[1],rgb[2]};
  return ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
}
int rp_text(RpTerminal *t, uint8_t **out, size_t *len) {
  GhosttyFormatter formatter = NULL;
  int r = ghostty_formatter_terminal_new(NULL, &formatter, t->terminal);
  if (r == 0) {
    GhosttyFormatterFormat emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    bool trim = true;
    ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_EMIT, &emit);
    ghostty_formatter_set(formatter, GHOSTTY_FORMATTER_OPT_TRIM, &trim);
    r = ghostty_formatter_format_alloc(formatter, NULL, out, len);
  }
  ghostty_formatter_free(formatter);
  return r;
}
int rp_encode(RpTerminal *t, uint8_t **out, size_t *len) {
  return ghostty_snapshot_encode_alloc(t->terminal, NULL, out, len);
}
void rp_bytes_free(uint8_t *bytes, size_t len) { ghostty_free(NULL, bytes, len); }

void rp_scroll(RpTerminal *t, int32_t lines, int bottom) {
  GhosttyTerminalScrollViewport behavior = {0};
  behavior.tag = bottom ? GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM : GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA;
  behavior.value.delta = lines;
  ghostty_terminal_scroll_viewport(t->terminal, behavior);
}
int rp_input_flags(RpTerminal *t, uint8_t *flags) {
  bool mouse=false;
  GhosttyTerminalScreen screen=GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  GhosttyTerminalModeConfig alt={.mode=GHOSTTY_MODE_MOUSE_ALTERNATE_SCROLL},cursor={.mode=GHOSTTY_MODE_CURSOR_KEYS};
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,&mouse);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,&screen);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&alt);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_MODE,&cursor);
  *flags=(mouse?1:0)|(screen==GHOSTTY_TERMINAL_SCREEN_ALTERNATE?2:0)|(alt.value?4:0)|(cursor.value?8:0);
  return r;
}
static int rp_mouse(RpTerminal *t,GhosttyMouseButton button,int release,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  GhosttyMouseEncoder encoder=NULL;GhosttyMouseEvent event=NULL;
  uint16_t cols=0,rows=0;*len=0;
  int r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_COLS,&cols);
  if(r==0) r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_ROWS,&rows);
  if(r==0) r=ghostty_mouse_encoder_new(NULL,&encoder);
  if(r==0) r=ghostty_mouse_event_new(NULL,&event);
  if(r==0) {
    if(col<0)col=0;if(col>=cols)col=cols-1;if(row<0)row=0;if(row>=rows)row=rows-1;
    if(cw==0)cw=1;if(ch==0)ch=1;
    GhosttyMouseEncoderSize geometry=GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    geometry.screen_width=cols*cw;geometry.screen_height=rows*ch;geometry.cell_width=cw;geometry.cell_height=ch;
    ghostty_mouse_encoder_set_from_terminal(encoder,t->terminal);
    ghostty_mouse_encoder_set(encoder,GHOSTTY_MOUSE_ENCODER_OPT_SIZE,&geometry);
    ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_PRESS);
    ghostty_mouse_event_set_button(event,button);
    ghostty_mouse_event_set_position(event,(GhosttyMousePosition){(col+.5f)*cw,(row+.5f)*ch});
    r=ghostty_mouse_encoder_encode(encoder,event,(uint8_t *)out,capacity,len);
    if(r==0 && release && *len<=capacity) {
      size_t tail=0;
      ghostty_mouse_event_set_action(event,GHOSTTY_MOUSE_ACTION_RELEASE);
      r=ghostty_mouse_encoder_encode(encoder,event,(uint8_t *)out+*len,capacity-*len,&tail);
      if(r==0) *len+=tail;
    }
  }
  ghostty_mouse_event_free(event);ghostty_mouse_encoder_free(encoder);return r;
}
int rp_wheel(RpTerminal *t,int down,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  return rp_mouse(t,down?GHOSTTY_MOUSE_BUTTON_FIVE:GHOSTTY_MOUSE_BUTTON_FOUR,0,col,row,cw,ch,out,capacity,len);
}
int rp_click(RpTerminal *t,int col,int row,uint32_t cw,uint32_t ch,char *out,size_t capacity,size_t *len) {
  return rp_mouse(t,GHOSTTY_MOUSE_BUTTON_LEFT,1,col,row,cw,ch,out,capacity,len);
}
static int rp_frame_impl(RpTerminal *t, RpFrame *frame, bool dirty_only, CellFn callback, PlacementFn placement_cb, ImageFn image_cb, void *userdata) {
  int r = 0;
  if (!rp_is_holding(t)) {
    r = ghostty_render_state_update(t->render, t->terminal);
    if (r != 0) return r;
  }
  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
#define GET(key,out) do { r=ghostty_render_state_get(t->render,key,out); if(r!=0)return r; } while(0)
  GET(GHOSTTY_RENDER_STATE_DATA_COLS,&frame->columns);
  GET(GHOSTTY_RENDER_STATE_DATA_ROWS,&frame->rows);
  GET(GHOSTTY_RENDER_STATE_DATA_COLORS,&colors);
  GET(GHOSTTY_RENDER_STATE_DATA_CURSOR,&cursor);
  GhosttyTerminalScrollbar scrollbar = {0};
  r=ghostty_terminal_get(t->terminal,GHOSTTY_TERMINAL_DATA_SCROLLBAR,&scrollbar);
  if(r!=0)return r;
  frame->scroll_total=scrollbar.total;
  frame->scroll_offset=scrollbar.offset;
  frame->scroll_visible=scrollbar.len;
  r=rp_input_flags(t,&frame->input_flags);if(r!=0)return r;
  frame->cursor_x=cursor.viewport_x; frame->cursor_y=cursor.viewport_y;
  // Bit 0: drawn cursor. Bit 1: position known even when hidden, as TUIs park it for IMEs.
  frame->cursor_visible=(cursor.visible && cursor.viewport_has_value) | (cursor.viewport_has_value<<1);
  memcpy(frame->foreground,&colors.foreground,3);
  memcpy(frame->background,&colors.background,3);
  GET(GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,&t->rows);
  // A first frame, a forced redraw, or a different grid needs every row; otherwise
  // only the rows Ghostty marked dirty are handed out.
  bool full = !dirty_only || t->frame_full_next || t->frame_columns != frame->columns ||
      t->frame_rows != frame->rows;
  frame->full = full ? 1 : 0;
  uint16_t y=0;
  uint32_t *graphemes=NULL; size_t capacity=0;
  uint8_t *uri=NULL; size_t uri_capacity=0;
  while (full ? ghostty_render_state_row_iterator_next(t->rows)
              : ghostty_render_state_row_iterator_next_dirty(t->rows, &y)) {
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,&t->cells);
    if(r!=0)break;
    GhosttyRow raw_row=0; bool wrapped=false,continuation=false;
    r=ghostty_render_state_row_get(t->rows,GHOSTTY_RENDER_STATE_ROW_DATA_RAW,&raw_row);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP,&wrapped);if(r!=0)break;
    r=ghostty_row_get(raw_row,GHOSTTY_ROW_DATA_WRAP_CONTINUATION,&continuation);if(r!=0)break;
    uint16_t x=0;
    while (ghostty_render_state_row_cells_next(t->cells)) {
      uint32_t count=0;
      GhosttyStyle style = {0};
      GhosttyColorRgb fg=colors.foreground,bg=colors.background;
      GhosttyCell raw=0; GhosttyCellWide wide=GHOSTTY_CELL_WIDE_NARROW;
#define CELL(key,out) do { r=ghostty_render_state_row_cells_get(t->cells,key,out); if(r!=0)goto done; } while(0)
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,&count);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,&style);
      CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,&raw);
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_WIDE,&wide); if(r!=0)goto done;
      // No explicit color is reported as INVALID_VALUE; retain resolved defaults.
      int fg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,&fg) != 0;
      int bg_default = ghostty_render_state_row_cells_get(t->cells,GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,&bg) != 0;
      if(count>4096) { r=GHOSTTY_LIMIT_EXCEEDED; goto done; }
      if(count>capacity) {
        uint32_t *next=realloc(graphemes,count*sizeof(*graphemes));
        if(!next) { r=GHOSTTY_OUT_OF_MEMORY; goto done; }
        graphemes=next; capacity=count;
      }
      if(count) {
        GhosttyCodepointBuffer cp_buf = {.ptr = graphemes, .cap = capacity};
        CELL(GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,&cp_buf);
      }
      // Client-local row metadata occupies bits above the color-role fields.
      uint32_t row_flags=(1u<<29)|((uint32_t)wrapped<<27)|((uint32_t)continuation<<28);
      uint32_t style_flags =
          ((style.flags & GHOSTTY_STYLE_FLAG_BOLD) ? 1 : 0) |
          (((style.flags & GHOSTTY_STYLE_FLAG_ITALIC) ? 1 : 0) << 1) |
          (((style.flags & GHOSTTY_STYLE_FLAG_FAINT) ? 1 : 0) << 2) |
          (((style.flags & GHOSTTY_STYLE_FLAG_INVERSE) ? 1 : 0) << 3) |
          (((style.flags & GHOSTTY_STYLE_FLAG_INVISIBLE) ? 1 : 0) << 4) |
          (((style.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) ? 1 : 0) << 5) |
          ((GHOSTTY_STYLE_UNDERLINE(style) != 0 ? 1 : 0) << 6);
      uint32_t flags=row_flags | style_flags | (fg_default<<7) | (bg_default<<8);
      if (style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE)
        flags |= (1u<<9) | ((uint32_t)style.fg_color.value.palette<<11);
      GhosttyCellContentTag content_tag=GHOSTTY_CELL_CONTENT_CODEPOINT;
      ghostty_cell_get(raw,GHOSTTY_CELL_DATA_CONTENT_TAG,&content_tag);
      if (content_tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
        GhosttyColorPaletteIndex index=0;
        ghostty_cell_get(raw,GHOSTTY_CELL_DATA_COLOR_PALETTE,&index);
        flags |= (1u<<10) | ((uint32_t)index<<19);
      } else if (style.bg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
        flags |= (1u<<10) | ((uint32_t)style.bg_color.value.palette<<19);
      }
      uint8_t width=wide==GHOSTTY_CELL_WIDE_WIDE ? 2 :
          (wide==GHOSTTY_CELL_WIDE_SPACER_TAIL || wide==GHOSTTY_CELL_WIDE_SPACER_HEAD ? 0 : 1);
      size_t uri_len=0; bool has_link=false;
      r=ghostty_cell_get(raw,GHOSTTY_CELL_DATA_HAS_HYPERLINK,&has_link);if(r!=0)goto done;
      if(has_link && width>0) {
        GhosttyGridRef ref = {0};
        GhosttyPoint point={.tag=GHOSTTY_POINT_TAG_VIEWPORT,.value.coordinate={.x=x,.y=y}};
        r=ghostty_terminal_grid_ref(t->terminal,point,&ref);if(r!=0)goto done;
        r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        if(r==GHOSTTY_OUT_OF_SPACE && uri_len<=65536) {
          uint8_t *next=realloc(uri,uri_len);
          if(!next) {r=GHOSTTY_OUT_OF_MEMORY;goto done;}
          uri=next;uri_capacity=uri_len;
          r=ghostty_grid_ref_hyperlink_uri(&ref,uri,uri_capacity,&uri_len);
        }
        if(uri_len>65536) {uri_len=0;r=0;}
        if(r!=0)goto done;
      }
      callback(userdata,x++,y,graphemes,count,(const uint8_t *)&fg,(const uint8_t *)&bg,flags,width,uri,uri_len);
    }
    if (full) y++;
  }
  if (placement_cb) {
    GhosttyKittyGraphics graphics = NULL;
    ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics);
    if (graphics && ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR, &t->images) == 0 && t->images) {
      uint32_t seen_images_buf[256];
      uint32_t *seen_images = seen_images_buf;
      size_t seen_capacity = sizeof(seen_images_buf) / sizeof(seen_images_buf[0]);
      size_t seen_count = 0;
      while (ghostty_render_state_image_iterator_next(t->images)) {
        GhosttyImageProtocol protocol = 0;
        if (ghostty_render_state_image_get(t->images, GHOSTTY_RENDER_STATE_IMAGE_DATA_PROTOCOL, &protocol) != 0 ||
            protocol != GHOSTTY_IMAGE_PROTOCOL_KITTY) continue;
        RpPlacement pl = {0};
        uint64_t generation = 0;
        GhosttyRenderStateImageData keys[] = {
          GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_Z,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_COLS,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_GRID_ROWS,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH,
          GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT,
        };
        void *values[] = {
          &pl.image_id, &generation, &pl.z, &pl.col, &pl.row,
          &pl.cols, &pl.rows, &pl.offset_x, &pl.offset_y,
          &pl.width, &pl.height, &pl.src_x, &pl.src_y, &pl.src_width, &pl.src_height,
        };
        if (ghostty_render_state_image_get_multi(t->images, 15, keys, values, NULL) != 0) continue;

        if (image_cb) {
          bool already_seen = false;
          for (size_t i = 0; i < seen_count; i++) {
            if (seen_images[i] == pl.image_id) {
              already_seen = true;
              break;
            }
          }
          if (!already_seen) {
            if (seen_count >= seen_capacity) {
              size_t new_cap = seen_capacity * 2;
              uint32_t *new_buf = (seen_images == seen_images_buf)
                ? (uint32_t *)malloc(new_cap * sizeof(uint32_t))
                : (uint32_t *)realloc(seen_images, new_cap * sizeof(uint32_t));
              if (new_buf) {
                if (seen_images == seen_images_buf) {
                  memcpy(new_buf, seen_images_buf, seen_count * sizeof(uint32_t));
                }
                seen_images = new_buf;
                seen_capacity = new_cap;
              }
            }
            if (seen_count < seen_capacity) {
              seen_images[seen_count++] = pl.image_id;
            }
            if (rp_generation_sent(t, pl.image_id, generation)) {
              placement_cb(userdata, &pl);
              continue; /* Same pixels as last time: no bytes cross the boundary. */
            }
            GhosttyKittyGraphicsImage kitty_img = ghostty_kitty_graphics_image(graphics, pl.image_id);
            if (kitty_img) {
              RpImageData data = {0};
              data.image_id = pl.image_id;
              data.generation = generation;
              GhosttyKittyImageFormat fmt = 0;
              GhosttyString pixels = {0};
              ghostty_kitty_graphics_image_get_multi(kitty_img, 4,
                (GhosttyKittyGraphicsImageData[]){
                  GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH,
                  GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT,
                  GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT,
                  GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS,
                },
                (void*[]){ &data.width, &data.height, &fmt, &pixels },
                NULL);
              data.format = (uint32_t)fmt;
              data.pixels = (const uint8_t *)pixels.ptr;
              data.pixel_len = pixels.len;
              if (data.pixels && data.pixel_len > 0) {
                image_cb(userdata, &data);
                rp_mark_generation(t, pl.image_id, generation);
              }
            }
          }
        }
        placement_cb(userdata, &pl);
      }
      if (seen_images != seen_images_buf) {
        free(seen_images);
      }
    }
  }
done:
  free(graphemes);free(uri);
  if (r==0) {
    t->frame_full_next = false;
    t->frame_columns = frame->columns;
    t->frame_rows = frame->rows;
    r = ghostty_render_state_clean(t->render);
  }
  return r;
}

int rp_frame(RpTerminal *t, RpFrame *frame, CellFn callback, PlacementFn placement_cb, ImageFn image_cb, void *userdata) {
  return rp_frame_impl(t, frame, false, callback, placement_cb, image_cb, userdata);
}
int rp_frame_dirty(RpTerminal *t, RpFrame *frame, CellFn callback, PlacementFn placement_cb, ImageFn image_cb, void *userdata) {
  return rp_frame_impl(t, frame, true, callback, placement_cb, image_cb, userdata);
}
void rp_next_full(RpTerminal *t) {
  if (t) t->frame_full_next = true;
}
