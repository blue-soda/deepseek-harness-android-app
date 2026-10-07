// 会话管理：列表 / 删除到回收站 / 恢复 / 彻底删除。
//
// ⚠ 用户 2026-10-04 明确要的功能：**App 里原来根本没法删会话**，话题只能一直堆着。
//
// 安全设计（不得绕过）：
//   1. 「删除」默认是**移到回收站**（`<dshHome>/sessions-deleted/<时间>-<会话id>/`），**不是 rm** —— 随时能恢复；
//   2. 回收站放在 `sessions/` **之外**（内核扫不到，不会被当成会话加载）；
//   3. 移动用 `rename`（同分区、原子、瞬间完成）；跨分区失败才退回复制+删除；
//   4. 「彻底删除」只在回收站里提供，且必须显式调用（`purge` / `purge-all`）；
//   5. 每个回收站条目写一个 `.origin` 文件记住**原来的 slug** —— 恢复时才能放回原位；
//   6. 只动 `<sessionsDir>` 与 `<回收站>` 这两棵树里的东西，别处一个字节都不碰。
//
// 用法（App 侧一律带 --json）：
//   node session-admin.mjs list       --sessions <sessionsDir> [--json]
//   node session-admin.mjs trash      --sessions <sessionsDir> --file <会话目录> [--json]
//   node session-admin.mjs trash-list --sessions <sessionsDir> [--json]
//   node session-admin.mjs restore    --sessions <sessionsDir> --trash <回收站条目目录> [--json]
//   node session-admin.mjs purge      --sessions <sessionsDir> --trash <回收站条目目录> [--json]
//   node session-admin.mjs purge-all  --sessions <sessionsDir> [--json]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';

const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/** 多帧 zstd 解（⚠ 不能只用 zstdDecompressSync 解首帧 —— 本项目踩过的坑）。 */
function decodeAll(buf) {
  const at = [];
  let i = 0;
  for (;;) { const p = buf.indexOf(MAGIC, i); if (p < 0) break; at.push(p); i = p + 4; }
  let out = '';
  let bad = 0;
  for (let k = 0; k < at.length; k++) {
    const end = k + 1 < at.length ? at[k + 1] : buf.length;
    try { out += zlib.zstdDecompressSync(buf.subarray(at[k], end)).toString('utf8'); } catch { bad++; }
  }
  return { text: out, frames: at.length, bad };
}

/**
 * 从会话文本里提一个"人看的标题"。
 * 依次尝试：session/title 事件 → 会话头里的标题字段 → 第一条用户消息的纯文本。
 * （结构随内核版本可能变，所以三种都试，全失败就退回空串，由调用方显示会话 id。）
 */
function titleOf(text) {
  const lines = text.split('\n');
  let firstUser = '';
  for (const l of lines) {
    if (!l.trim()) continue;
    let o;
    try { o = JSON.parse(l); } catch { continue; }
    if (o.type === 'session/title') {
      const d = o.data;
      if (typeof d === 'string' && d.trim()) return d.trim();
      if (d && typeof d === 'object') {
        for (const k of ['title', 'text', 'value']) if (typeof d[k] === 'string' && d[k].trim()) return d[k].trim();
      }
    }
    if (o.type === 'session' && o.data && typeof o.data === 'object' && typeof o.data.title === 'string' && o.data.title.trim()) {
      return o.data.title.trim();
    }
    if (!firstUser && (o.type === 'user/message' || o.role === 'user')) {
      const msg = (o.data && o.data.message) ? o.data.message : o.data;
      const parts = msg && msg.content;
      if (Array.isArray(parts)) {
        for (const p of parts) if (p && p.type === 'text' && typeof p.text === 'string') { firstUser += p.text + ' '; }
      } else if (typeof msg === 'string') firstUser += msg + ' ';
    }
  }
  return firstUser.replace(/\s+/g, ' ').trim().slice(0, 60);
}

function dirSize(dir) {
  let n = 0;
  const walk = (d) => {
    let kids;
    try { kids = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const k of kids) {
      const p = path.join(d, k.name);
      if (k.isDirectory()) walk(p);
      else { try { n += fs.statSync(p).size; } catch {} }
    }
  };
  walk(dir);
  return n;
}

/** 扫 sessions/ 下的全部会话条目。 */
function listSessions(sessionsDir) {
  const out = [];
  let slugs;
  try { slugs = fs.readdirSync(sessionsDir, { withFileTypes: true }); } catch { return out; }
  for (const slug of slugs) {
    if (!slug.isDirectory()) continue;
    const slugDir = path.join(sessionsDir, slug.name);
    let ids;
    try { ids = fs.readdirSync(slugDir, { withFileTypes: true }); } catch { continue; }
    for (const idDir of ids) {
      if (!idDir.isDirectory()) continue;
      const dir = path.join(slugDir, idDir.name);
      const file = path.join(dir, 'session.jsonl.zstd');
      if (!fs.existsSync(file)) continue;
      let bytes = 0, mtime = 0, title = '', lines = 0, frames = 0, bad = 0;
      try {
        const st = fs.statSync(file);
        bytes = st.size;
        mtime = st.mtimeMs;
        const dec = decodeAll(fs.readFileSync(file));
        frames = dec.frames;
        bad = dec.bad;
        lines = dec.text.split('\n').filter((x) => x.trim()).length;
        title = titleOf(dec.text);
      } catch {}
      out.push({
        id: idDir.name.replace(/^session-/, ''),
        dirName: idDir.name,
        slug: slug.name,
        dir,
        file,
        bytes,
        mtime,
        title,
        lines,
        frames,
        badFrames: bad,
      });
    }
  }
  out.sort((a, b) => b.mtime - a.mtime);
  return out;
}

const TRASH_DIRNAME = 'sessions-deleted';
function trashRoot(sessionsDir) { return path.join(path.dirname(sessionsDir), TRASH_DIRNAME); }

function stamp() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${String(d.getFullYear()).slice(2)}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

/** 移到回收站（rename 优先，跨分区才退回复制）。 */
function trashOne(sessionsDir, sessionDir) {
  if (!sessionDir || !fs.existsSync(sessionDir)) return { ok: false, error: '会话目录不存在：' + sessionDir };
  const root = path.join(sessionsDir, '..', TRASH_DIRNAME);
  const absRoot = path.resolve(root);
  // 只允许删 sessions/ 这棵树里的东西
  const rel = path.relative(path.resolve(sessionsDir), path.resolve(sessionDir));
  if (rel.startsWith('..') || path.isAbsolute(rel)) return { ok: false, error: '拒绝：目标不在 sessions 目录里（' + sessionDir + '）' };
  fs.mkdirSync(absRoot, { recursive: true });
  const id = path.basename(sessionDir);
  let dest = path.join(absRoot, `${stamp()}-${id}`);
  let k = 1;
  while (fs.existsSync(dest)) dest = path.join(absRoot, `${stamp()}-${id}-${k++}`);
  try {
    fs.renameSync(sessionDir, dest);
  } catch (e) {
    // 跨分区：复制 + 删原目录（仍然只动这两棵树）
    fs.cpSync(sessionDir, dest, { recursive: true });
    fs.rmSync(sessionDir, { recursive: true, force: true });
  }
  // 记住原 slug（恢复时要放回原位）
  const slug = path.basename(path.dirname(sessionDir));
  fs.writeFileSync(path.join(dest, '.origin'), JSON.stringify({ slug, dirName: id, at: new Date().toISOString() }), 'utf8');
  return { ok: true, moved: dest, slug, dirName: id };
}

/** 列回收站条目（不含 .origin 之外的处理）。 */
function listTrash(sessionsDir) {
  const root = trashRoot(sessionsDir);
  const out = [];
  if (!fs.existsSync(root)) return out;
  for (const e of fs.readdirSync(root, { withFileTypes: true })) {
    if (!e.isDirectory()) continue;
    const dir = path.join(root, e.name);
    let origin = null;
    try { origin = JSON.parse(fs.readFileSync(path.join(dir, '.origin'), 'utf8')); } catch {}
    const file = path.join(dir, 'session.jsonl.zstd');
    let title = '', bytes = 0, lines = 0, mtime = 0;
    if (fs.existsSync(file)) {
      try {
        const st = fs.statSync(file);
        bytes = st.size;
        mtime = st.mtimeMs;
        const dec = decodeAll(fs.readFileSync(file));
        lines = dec.text.split('\n').filter((x) => x.trim()).length;
        title = titleOf(dec.text);
      } catch {}
    }
    out.push({ entry: e.name, dir, bytes, mtime, title, lines, slug: origin ? origin.slug : '', removedAt: origin ? origin.at : '' });
  }
  out.sort((a, b) => b.mtime - a.mtime);
  return out;
}

function restoreOne(sessionsDir, entryDir) {
  if (!entryDir || !fs.existsSync(entryDir)) return { ok: false, error: '回收站条目不存在：' + entryDir };
  const rel = path.relative(trashRoot(sessionsDir), path.resolve(entryDir));
  if (rel.startsWith('..') || path.isAbsolute(rel)) return { ok: false, error: '拒绝：条目不在回收站里' };
  let origin = null;
  try { origin = JSON.parse(fs.readFileSync(path.join(entryDir, '.origin'), 'utf8')); } catch {}
  const slug = (origin && origin.slug) ? origin.slug : '--restored--';
  const dirName = (origin && origin.dirName) ? origin.dirName : path.basename(entryDir).replace(/^\d{6}-\d{6}-/, '');
  const destDir = path.join(sessionsDir, slug);
  fs.mkdirSync(destDir, { recursive: true });
  let dest = path.join(destDir, dirName);
  if (fs.existsSync(dest)) return { ok: false, error: '原位已存在同名会话，先处理它再恢复：' + dest };
  try {
    fs.renameSync(entryDir, dest);
  } catch (e) {
    fs.cpSync(entryDir, dest, { recursive: true });
    fs.rmSync(entryDir, { recursive: true, force: true });
  }
  try { fs.unlinkSync(path.join(dest, '.origin')); } catch {}
  return { ok: true, restoredTo: dest, slug, dirName };
}

function purgeOne(sessionsDir, entryDir) {
  if (!entryDir || !fs.existsSync(entryDir)) return { ok: false, error: '回收站条目不存在：' + entryDir };
  const rel = path.relative(trashRoot(sessionsDir), path.resolve(entryDir));
  if (rel.startsWith('..') || path.isAbsolute(rel)) return { ok: false, error: '拒绝：条目不在回收站里' };
  const bytes = dirSize(entryDir);
  fs.rmSync(entryDir, { recursive: true, force: true });
  return { ok: true, bytes };
}

function purgeAll(sessionsDir) {
  const root = trashRoot(sessionsDir);
  if (!fs.existsSync(root)) return { ok: true, entries: 0, bytes: 0 };
  let entries = 0, bytes = 0;
  for (const e of fs.readdirSync(root, { withFileTypes: true })) {
    if (!e.isDirectory()) continue;
    const dir = path.join(root, e.name);
    bytes += dirSize(dir);
    fs.rmSync(dir, { recursive: true, force: true });
    entries++;
  }
  return { ok: true, entries, bytes };
}

// ─────────────────────────── CLI ───────────────────────────
function argOf(name, dflt) {
  const i = process.argv.indexOf(name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt;
}
function emit(o) { process.stdout.write(JSON.stringify(o, null, 2) + '\n'); }

export { listSessions, listTrash, trashOne, restoreOne, purgeOne, purgeAll, trashRoot, decodeAll, titleOf };

const isMain = Boolean(process.argv[1]) && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  const cmd = process.argv[2];
  const sessionsDir = argOf('--sessions');
  if (!sessionsDir || !fs.existsSync(sessionsDir)) {
    emit({ ok: false, error: '缺少或找不到 --sessions：' + sessionsDir });
    process.exit(2);
  }
  if (cmd === 'list') {
    const list = listSessions(sessionsDir);
    emit({
      ok: true, mode: 'list', sessionsDir, trashDir: trashRoot(sessionsDir),
      count: list.length,
      totalBytes: list.reduce((n, s) => n + s.bytes, 0),
      sessions: list.map((s) => ({
        id: s.id, dirName: s.dirName, slug: s.slug, dir: s.dir, file: s.file,
        bytes: s.bytes, mtime: s.mtime, title: s.title, lines: s.lines, badFrames: s.badFrames,
      })),
    });
  } else if (cmd === 'trash') {
    emit(Object.assign({ mode: 'trash' }, trashOne(sessionsDir, argOf('--file'))));
  } else if (cmd === 'trash-list') {
    const t = listTrash(sessionsDir);
    emit({ ok: true, mode: 'trash-list', trashDir: trashRoot(sessionsDir), count: t.length, totalBytes: t.reduce((n, s) => n + s.bytes, 0), entries: t });
  } else if (cmd === 'restore') {
    emit(Object.assign({ mode: 'restore' }, restoreOne(sessionsDir, argOf('--trash'))));
  } else if (cmd === 'purge') {
    emit(Object.assign({ mode: 'purge' }, purgeOne(sessionsDir, argOf('--trash'))));
  } else if (cmd === 'purge-all') {
    emit(Object.assign({ mode: 'purge-all' }, purgeAll(sessionsDir)));
  } else {
    emit({ ok: false, error: '未知子命令：' + cmd });
    process.exit(2);
  }
}
