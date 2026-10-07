// 会话级自愈（自修复 ③）的**执行工具**：定位坏附件 → 只降级那一条消息 → 备份 → 复检。
//
// 与 session-audit.mjs 的关系：那份是**只读体检**（先把"哪一条坏了"变成判据）；
// 这份是**写路径**（设计稿 selfheal-design.md §1 ③ 要求的那一半）。
//
// 为什么必须是"降级那一条消息"而不是"删会话"：
//   坏 PNG（文件头合法、IDAT 损坏）一旦作为 tool_result 进历史，**每次请求都复现**，
//   用户只能放弃整条会话（v1.15.1 记录过两条；根因见 dsh-attachment-local/lib/sharp-shim.js 头注释"症状 A"）。
//   把那一处 image part 换成等价的 text part，会话其余内容（文本、上下文、顺序）一字不动。
//
// 安全边界（写进代码，不得绕过）：
//   1. 只处理**命令行指定的那一个**会话文件；本工具没有"扫全目录然后批量修"的模式。
//   2. 只替换**判定为坏的那一个 part**；该行其余字段、其余行、其余 zstd 帧**字节原样保留**。
//   3. 写盘前**必须先备份**（同目录 `.corrupt-<时间戳>`），备份失败即中止。
//   4. 写盘用"临时文件 + rename"原子替换；写完**立即复检**（坏引用必须归零、行数与 JSON 合法性不变）。
//   5. 默认 dry-run；只有显式 `--apply` 才落盘。
//
// 用法：
//   node session-heal.mjs audit --sessions <sessionsDir> --att-root <attachments/v1 目录> [--json]
//   node session-heal.mjs heal  --file <session.jsonl.zstd> --att-root <dir> [--apply] [--json] [--reason "…"]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

// ─────────────────────────── zstd 多帧 ───────────────────────────
// 内核把会话写成**追加式多帧 zstd**：每次 flush 一帧。实测每帧都落在完整 JSONL 行边界上
// （每条帧都以 '{' 开头、以 '\n' 结尾），所以"只重写含坏消息的那一帧"是安全的。
// ⚠ 不能用 zstdDecompressSync 一次解整个文件 —— 它只解首帧（本项目踩过的坑）。
function splitFrames(buf) {
	const at = [];
	let i = 0;
	for (;;) {
		const p = buf.indexOf(ZSTD_MAGIC, i);
		if (p < 0) break;
		at.push(p);
		i = p + 4;
	}
	const out = [];
	for (let k = 0; k < at.length; k++) {
		out.push({ start: at[k], end: k + 1 < at.length ? at[k + 1] : buf.length });
	}
	return out;
}

function decodeFrame(buf, start, end) {
	return zlib.zstdDecompressSync(buf.subarray(start, end)).toString('utf8');
}

// ─────────────────────────── 附件完整性 ───────────────────────────
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const CRC_TABLE = (() => {
	const t = new Int32Array(256);
	for (let n = 0; n < 256; n++) {
		let c = n;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		t[n] = c;
	}
	return t;
})();
function crc32(buf) {
	let c = 0xffffffff;
	for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
}

/** PNG：签名 + 逐 chunk CRC + IDAT 可 inflate。这正是"文件头合法但内容损坏"的检出手段。 */
function checkPng(buf) {
	if (buf.length < 8 || !buf.subarray(0, 8).equals(PNG_SIG)) return 'PNG 签名不匹配';
	let off = 8, sawIhdr = false, sawIend = false;
	const idat = [];
	while (off + 12 <= buf.length) {
		const len = buf.readUInt32BE(off);
		const type = buf.toString('ascii', off + 4, off + 8);
		const dataEnd = off + 8 + len;
		if (dataEnd + 4 > buf.length) return `PNG chunk ${type} 越界（文件被截断）`;
		const stored = buf.readUInt32BE(dataEnd);
		const actual = crc32(buf.subarray(off + 4, dataEnd));
		if (stored !== actual) return `PNG chunk ${type} 的 CRC 不匹配（存储 ${stored.toString(16)} / 实算 ${actual.toString(16)}）`;
		if (type === 'IHDR') sawIhdr = true;
		if (type === 'IDAT') idat.push(buf.subarray(off + 8, dataEnd));
		if (type === 'IEND') { sawIend = true; break; }
		off = dataEnd + 4;
	}
	if (!sawIhdr) return 'PNG 缺少 IHDR';
	if (!sawIend) return 'PNG 缺少 IEND（文件被截断）';
	if (!idat.length) return 'PNG 缺少 IDAT';
	try {
		zlib.inflateSync(Buffer.concat(idat));
	} catch (e) {
		return `PNG IDAT 无法解压（文件已损坏）：${e.message}`;
	}
	return null;
}

function checkJpeg(buf) {
	if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) return 'JPEG 缺少 SOI 标记';
	if (buf[buf.length - 2] !== 0xff || buf[buf.length - 1] !== 0xd9) return 'JPEG 缺少 EOI 标记（文件被截断）';
	return null;
}

function checkWebp(buf) {
	if (buf.length < 12) return 'WebP 文件过短';
	if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WEBP') return 'WebP 头不匹配';
	return null;
}

function checkGif(buf) {
	if (buf.length < 14) return 'GIF 文件过短';
	const sig = buf.toString('ascii', 0, 6);
	if (sig !== 'GIF87a' && sig !== 'GIF89a') return 'GIF 签名不匹配';
	if (buf[buf.length - 1] !== 0x3b) return 'GIF 缺少结束符 0x3B（文件被截断）';
	return null;
}

/** 按 mediaType 做"能否被 provider 解码"级别的校验；未知类型只查存在与大小。 */
function contentVerdict(buf, mediaType) {
	const mt = String(mediaType || '').toLowerCase();
	if (mt === 'image/png') return checkPng(buf);
	if (mt === 'image/jpeg' || mt === 'image/jpg') return checkJpeg(buf);
	if (mt === 'image/webp') return checkWebp(buf);
	if (mt === 'image/gif') return checkGif(buf);
	return null;
}

/** 附件实体路径：<attRoot>/objects/<sha256 前两位>/<sha256>（见 dsh-attachment-local 的 normalizedImagePath）。 */
function objectPath(attRoot, sha) {
	return path.join(attRoot, 'objects', sha.slice(0, 2), sha);
}

/**
 * 判定一个 attachment 引用是否"坏"。
 * @returns null 表示正常；否则 { reason, detail }
 */
function verifyRef(attRoot, ref) {
	const id = String(ref.attachmentId || '');
	const sha = id.startsWith('sha256:') ? id.slice(7) : '';
	if (!/^[0-9a-f]{64}$/.test(sha)) return { reason: 'INVALID_ATTACHMENT_REF', detail: `attachmentId 形态非法：${id.slice(0, 40)}` };
	const p = objectPath(attRoot, sha);
	let st;
	try {
		st = fs.statSync(p);
	} catch {
		return { reason: 'ATTACHMENT_NOT_FOUND', detail: `实体文件不存在：objects/${sha.slice(0, 2)}/${sha.slice(0, 16)}…` };
	}
	if (!st.isFile()) return { reason: 'ATTACHMENT_READ_FAILED', detail: '实体不是普通文件' };
	if (st.size === 0) return { reason: 'ATTACHMENT_CORRUPT', detail: '实体文件为空' };
	if (ref.bytes && st.size !== ref.bytes) {
		return { reason: 'ATTACHMENT_CORRUPT', detail: `大小与记录不符（记录 ${ref.bytes} / 实际 ${st.size}）` };
	}
	let buf;
	try {
		buf = fs.readFileSync(p);
	} catch (e) {
		return { reason: 'ATTACHMENT_READ_FAILED', detail: `读取失败：${e.message}` };
	}
	const why = contentVerdict(buf, ref.mediaType);
	if (why) return { reason: 'ATTACHMENT_CORRUPT', detail: why };
	return null;
}

// ─────────────────────────── 会话行扫描 ───────────────────────────
/** 收集一行事件里所有 attachment part 的容器引用（含嵌套的 content[]）。 */
function collectParts(node, out) {
	if (!node || typeof node !== 'object') return;
	if (Array.isArray(node)) {
		for (const x of node) collectParts(x, out);
		return;
	}
	if ((node.type === 'image' || node.type === 'file') && node.attachment && typeof node.attachment === 'object') {
		out.push(node);
	}
	for (const k of Object.keys(node)) collectParts(node[k], out);
}

/** 把坏掉的 image/file part 换成等价文本：保留"这里原本有一张图"的事实，去掉引用。 */
function degradePart(part, verdict) {
	const a = part.attachment || {};
	const bits = [];
	if (a.name) bits.push(String(a.name));
	if (a.width && a.height) bits.push(`${a.width}×${a.height}`);
	if (a.bytes) bits.push(`${a.bytes} 字节`);
	if (a.mediaType) bits.push(String(a.mediaType));
	return {
		type: 'text',
		text: `【附件不可用】${bits.join(' · ') || '(无描述)'} —— ${verdict.reason}：${verdict.detail}（原图已损坏或缺失，此处已降级为文字，其余内容未改动）`,
	};
}

/**
 * 体检一个会话文件（只读）。返回坏引用清单 + 结构统计。
 * @param file - session.jsonl.zstd 绝对路径
 * @param attRoot - attachments/v1 根目录；为空时跳过实体校验（只查引用形态）
 */
function inspectSession(file, attRoot) {
	const raw = fs.readFileSync(file);
	const frames = splitFrames(raw);
	const bad = [];
	let lines = 0, jsonBad = 0, framesBad = 0, attRefs = 0;
	const frameInfo = [];

	for (let fi = 0; fi < frames.length; fi++) {
		let text;
		try {
			text = decodeFrame(raw, frames[fi].start, frames[fi].end);
		} catch (e) {
			framesBad++;
			frameInfo.push({ index: fi + 1, bytes: frames[fi].end - frames[fi].start, lines: 0, decodeError: e.message, editable: false });
			continue;
		}
		const arr = text.split('\n');
		const trailing = arr[arr.length - 1] === '';
		frameInfo.push({ index: fi + 1, bytes: frames[fi].end - frames[fi].start, lines: arr.filter((l) => l.trim()).length, aligned: text.startsWith('{') && trailing });
		for (let li = 0; li < arr.length; li++) {
			const line = arr[li];
			if (!line.trim()) continue;
			lines++;
			let o;
			try {
				o = JSON.parse(line);
			} catch {
				jsonBad++;
				continue;
			}
			const parts = [];
			collectParts(o, parts);
			for (const part of parts) {
				attRefs++;
				if (!attRoot) continue;
				const v = verifyRef(attRoot, part.attachment);
				if (v) bad.push({ frame: fi + 1, line: li + 1, seq: o.seq, evType: o.type, part, verdict: v });
			}
		}
	}
	return {
		file,
		bytes: raw.length,
		frames: frames.length,
		framesBad,
		lines,
		jsonBad,
		attRefs,
		bad,
		frameInfo,
		mtime: fs.statSync(file).mtime.toISOString(),
	};
}

/** 递归找出所有 session.jsonl.zstd。 */
function findSessions(dir) {
	const out = [];
	const walk = (d, depth) => {
		if (depth > 4) return;
		let entries;
		try {
			entries = fs.readdirSync(d, { withFileTypes: true });
		} catch {
			return;
		}
		for (const e of entries) {
			const p = path.join(d, e.name);
			if (e.isDirectory()) walk(p, depth + 1);
			else if (e.name === 'session.jsonl.zstd') out.push(p);
		}
	};
	walk(dir, 0);
	return out;
}

// ─────────────────────────── 写路径 ───────────────────────────
/**
 * 只降级**指定文件**里判定为坏的 part。
 * 未改动的帧按原始字节切片保留；改动过的帧重新压缩。返回详细账目。
 */
function healSession(file, attRoot, apply) {
	const before = inspectSession(file, attRoot);
	if (before.jsonBad) {
		return { ok: false, refused: `会话里有 ${before.jsonBad} 行 JSON 无法解析，拒绝改写（避免把可恢复的损坏扩大）` };
	}
	if (!before.bad.length) {
		return { ok: true, applied: false, reason: '没有发现坏附件引用，未改动任何字节', before };
	}
	const uneditable = before.frameInfo.filter((f) => f.decodeError);
	if (uneditable.length) {
		return { ok: false, refused: `有 ${uneditable.length} 个 zstd 帧无法解压，拒绝改写（先处理帧级损坏）` };
	}

	const raw = fs.readFileSync(file);
	const frames = splitFrames(raw);
	const badByFrame = new Map();
	for (const b of before.bad) {
		if (!badByFrame.has(b.frame)) badByFrame.set(b.frame, new Set());
		badByFrame.get(b.frame).add(b.line);
	}

	const pieces = [];
	const actions = [];
	let framesRewritten = 0;

	for (let fi = 0; fi < frames.length; fi++) {
		const fno = fi + 1;
		const slice = raw.subarray(frames[fi].start, frames[fi].end);
		if (!badByFrame.has(fno)) {
			pieces.push(slice); // 未命中的帧：**字节原样**
			continue;
		}
		const text = decodeFrame(raw, frames[fi].start, frames[fi].end);
		const arr = text.split('\n');
		let changed = 0;
		for (let li = 0; li < arr.length; li++) {
			if (!badByFrame.get(fno).has(li + 1)) continue;
			const o = JSON.parse(arr[li]);
			const parts = [];
			collectParts(o, parts);
			let lineChanged = false;
			for (const part of parts) {
				const v = verifyRef(attRoot, part.attachment);
				if (!v) continue;
				const a = part.attachment || {};
				const replacement = degradePart(part, v);
				// 就地替换：先清空该 part 的所有键，再写入 text part 的键（保持它在数组里的位置）
				for (const k of Object.keys(part)) delete part[k];
				for (const k of Object.keys(replacement)) part[k] = replacement[k];
				lineChanged = true;
				changed++;
				actions.push({
					frame: fno,
					line: li + 1,
					seq: o.seq,
					eventType: o.type,
					attachmentId: String(a.attachmentId || '').slice(0, 24) + '…',
					name: a.name || '',
					reason: v.reason,
					detail: v.detail,
					replacedWith: replacement.text.slice(0, 120),
				});
			}
			if (lineChanged) arr[li] = JSON.stringify(o);
		}
		if (!changed) {
			pieces.push(slice);
			continue;
		}
		framesRewritten++;
		pieces.push(zlib.zstdCompressSync(Buffer.from(arr.join('\n'), 'utf8')));
	}

	const outBuf = Buffer.concat(pieces);

	if (!apply) {
		return {
			ok: true,
			applied: false,
			dryRun: true,
			file,
			wouldChangeBytes: outBuf.length,
			framesRewritten,
			actions,
			before,
		};
	}

	// ① 备份（先复制到别处，失败即中止）
	const ts = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
	const backup = `${file}.corrupt-${ts}`;
	try {
		if (fs.existsSync(backup)) return { ok: false, refused: `备份目标已存在：${backup}` };
		fs.copyFileSync(file, backup);
		const st = fs.statSync(backup);
		if (st.size !== raw.length) return { ok: false, refused: `备份大小不符（${st.size} ≠ ${raw.length}），已中止` };
	} catch (e) {
		return { ok: false, refused: `备份失败，已中止：${e.message}` };
	}

	// ② 临时文件 + rename 原子替换
	const tmp = `${file}.heal-tmp`;
	try {
		fs.writeFileSync(tmp, outBuf);
		fs.renameSync(tmp, file);
	} catch (e) {
		try { fs.unlinkSync(tmp); } catch {}
		return { ok: false, refused: `写入失败（原文件未改动，备份在 ${backup}）：${e.message}` };
	}

	// ③ 复检
	const after = inspectSession(file, attRoot);
	const verify = {
		badRefsAfter: after.bad.length,
		linesBefore: before.lines,
		linesAfter: after.lines,
		jsonBadAfter: after.jsonBad,
		framesBefore: before.frames,
		framesAfter: after.frames,
		attRefsBefore: before.attRefs,
		attRefsAfter: after.attRefs,
	};
	const ok = after.bad.length === 0 && after.lines === before.lines && after.jsonBad === 0;
	return {
		ok,
		applied: true,
		file,
		backup,
		framesRewritten,
		bytesBefore: raw.length,
		bytesAfter: outBuf.length,
		actions,
		verify,
		verdict: ok ? 'ok' : 'partial（复检未全过，原文件已备份，可回切）',
	};
}

// ─────────────────────────── CLI ───────────────────────────
function argOf(name, dflt) {
	const i = process.argv.indexOf(name);
	return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt;
}
const asJson = process.argv.includes('--json');
function emit(obj) {
	if (asJson) {
		process.stdout.write(JSON.stringify(obj, null, 2) + '\n');
		return;
	}
	console.log(JSON.stringify(obj, null, 2));
}

// 作为模块被 import（离线判据脚本复用这些函数）时，不执行 CLI。
export { splitFrames, decodeFrame, verifyRef, collectParts, inspectSession, findSessions, healSession, checkPng, checkJpeg, crc32 };

const isMain = Boolean(process.argv[1]) && import.meta.url === pathToFileURL(process.argv[1]).href;
const cmd = process.argv[2];
if (!isMain) {
	// import 模式：什么都不做，只暴露函数
} else if (cmd === 'audit') {
	const sessionsDir = argOf('--sessions');
	const attRoot = argOf('--att-root');
	if (!sessionsDir) { console.error('用法: node session-heal.mjs audit --sessions <dir> [--att-root <dir>]'); process.exit(2); }
	if (!fs.existsSync(sessionsDir)) { console.error(`会话目录不存在: ${sessionsDir}`); process.exit(2); }
	const files = findSessions(sessionsDir);
	const results = files.map((f) => inspectSession(f, attRoot || ''));
	const badOnes = results.filter((r) => r.bad.length);
	emit({
		ok: true,
		mode: 'audit',
		sessionsDir,
		attRoot: attRoot || '(未提供，只查引用形态)',
		sessionCount: results.length,
		badSessionCount: badOnes.length,
		totalBadRefs: badOnes.reduce((n, r) => n + r.bad.length, 0),
		sessions: results.map((r) => ({
			id: path.basename(path.dirname(r.file)),
			file: r.file,
			bytes: r.bytes,
			frames: r.frames,
			lines: r.lines,
			jsonBad: r.jsonBad,
			attRefs: r.attRefs,
			badCount: r.bad.length,
			bad: r.bad.map((b) => ({ frame: b.frame, line: b.line, seq: b.seq, evType: b.evType, name: b.part.attachment?.name || '', reason: b.verdict.reason, detail: b.verdict.detail })),
		})),
	});
} else if (cmd === 'heal') {
	const file = argOf('--file');
	const attRoot = argOf('--att-root');
	const apply = process.argv.includes('--apply');
	if (!file || !fs.existsSync(file)) { console.error(`--file 不存在: ${file}`); process.exit(2); }
	if (!attRoot || !fs.existsSync(attRoot)) { console.error(`--att-root 不存在: ${attRoot}（会话修复必须能核对附件实体）`); process.exit(2); }
	const r = healSession(file, attRoot, apply);
	emit(r);
	process.exit(r.ok ? 0 : 3);
} else {
	console.error('用法:\n  node session-heal.mjs audit --sessions <dir> --att-root <dir> [--json]\n  node session-heal.mjs heal --file <session.jsonl.zstd> --att-root <dir> [--apply] [--json]');
	process.exit(2);
}
