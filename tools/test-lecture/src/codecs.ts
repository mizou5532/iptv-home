export interface CodecInfo { container: 'mkv' | 'mp4' | 'ts' | 'inconnu'; video: string[]; audio: string[] }

const MKV_IDS: [string, 'video' | 'audio', string][] = [
  ['V_MPEG4/ISO/AVC', 'video', 'h264'], ['V_MPEGH/ISO/HEVC', 'video', 'hevc'], ['V_AV1', 'video', 'av1'],
  ['V_VP9', 'video', 'vp9'], ['V_MPEG2', 'video', 'mpeg2'], ['V_MPEG4/ISO/', 'video', 'mpeg4'],
  ['A_AAC', 'audio', 'aac'], ['A_EAC3', 'audio', 'eac3'], ['A_AC3', 'audio', 'ac3'], ['A_DTS', 'audio', 'dts'],
  ['A_OPUS', 'audio', 'opus'], ['A_MPEG/L3', 'audio', 'mp3'], ['A_TRUEHD', 'audio', 'truehd'], ['A_FLAC', 'audio', 'flac'],
];
const MP4_CODES: [string, 'video' | 'audio', string][] = [
  ['avc1', 'video', 'h264'], ['avc3', 'video', 'h264'], ['hvc1', 'video', 'hevc'], ['hev1', 'video', 'hevc'], ['av01', 'video', 'av1'],
  ['mp4a', 'audio', 'aac'], ['ac-3', 'audio', 'ac3'], ['ec-3', 'audio', 'eac3'], ['Opus', 'audio', 'opus'],
];
const TS_TYPES: Record<number, ['video' | 'audio', string]> = {
  0x1b: ['video', 'h264'], 0x24: ['video', 'hevc'], 0x02: ['video', 'mpeg2'], 0x0f: ['audio', 'aac'], 0x11: ['audio', 'aac'],
  0x03: ['audio', 'mp3'], 0x04: ['audio', 'mp3'], 0x81: ['audio', 'ac3'], 0x87: ['audio', 'eac3'],
};

const latin1 = new TextDecoder('latin1');
const ascii = (b: Uint8Array, from: number, len: number) => latin1.decode(b.subarray(from, from + len));

function push(out: CodecInfo, kind: 'video' | 'audio', name: string) {
  if (!out[kind].includes(name)) out[kind].push(name);
}

function mkv(b: Uint8Array): CodecInfo {
  const out: CodecInfo = { container: 'mkv', video: [], audio: [] };
  // Élément CodecID : identifiant 0x86, taille codée sur un octet (0x80 | longueur), puis la chaîne ASCII.
  for (let i = 0; i + 2 < b.length; i++) {
    if (b[i] !== 0x86 || (b[i + 1] & 0x80) === 0) continue;
    const len = b[i + 1] & 0x7f;
    if (len < 3 || len > 40 || i + 2 + len > b.length) continue;
    const id = ascii(b, i + 2, len);
    if (!/^[VA]_/.test(id)) continue;
    const hit = MKV_IDS.find(([prefix]) => id.startsWith(prefix));
    if (hit) push(out, hit[1], hit[2]);
  }
  return out;
}

function mp4(b: Uint8Array): CodecInfo {
  const out: CodecInfo = { container: 'mp4', video: [], audio: [] };
  const text = ascii(b, 0, b.length);
  for (const [code, kind, name] of MP4_CODES) if (text.includes(code)) push(out, kind, name);
  return out;
}

function ts(b: Uint8Array): CodecInfo {
  const out: CodecInfo = { container: 'ts', video: [], audio: [] };
  let pmtPid = -1;
  for (let i = 0; i + 188 <= b.length && b[i] === 0x47; i += 188) {
    const pid = ((b[i + 1] & 0x1f) << 8) | b[i + 2];
    const pusi = (b[i + 1] & 0x40) !== 0;
    const afc = (b[i + 3] >> 4) & 3;
    let p = i + 4;
    if (afc & 2) p += 1 + b[p];
    if (!(afc & 1) || !pusi) continue;
    p += 1 + b[p]; // pointer_field
    const sectionLength = ((b[p + 1] & 0x0f) << 8) | b[p + 2];
    const end = Math.min(p + 3 + sectionLength - 4, i + 188);
    if (pid === 0 && b[p] === 0x00) {
      for (let q = p + 8; q + 4 <= end; q += 4) {
        const program = (b[q] << 8) | b[q + 1];
        if (program !== 0) { pmtPid = ((b[q + 2] & 0x1f) << 8) | b[q + 3]; break; }
      }
    } else if (pid === pmtPid && b[p] === 0x02) {
      const programInfo = ((b[p + 10] & 0x0f) << 8) | b[p + 11];
      for (let q = p + 12 + programInfo; q + 5 <= end;) {
        const t = TS_TYPES[b[q]];
        if (t) push(out, t[0], t[1]);
        q += 5 + (((b[q + 3] & 0x0f) << 8) | b[q + 4]);
      }
      break;
    }
  }
  return out;
}

/** Devine le conteneur et les codecs à partir des premiers octets d'un fichier ou d'un flux. */
export function detectCodecs(head: Uint8Array): CodecInfo {
  if (head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3) return mkv(head);
  if (head.length >= 12 && ascii(head, 4, 4) === 'ftyp') return mp4(head);
  if (head.length >= 188 && head[0] === 0x47 && (head.length < 376 || head[188] === 0x47)) return ts(head);
  return { container: 'inconnu', video: [], audio: [] };
}
