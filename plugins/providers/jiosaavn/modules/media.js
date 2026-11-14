/* Metrolist Project (C) 2026, GPL-3.0. See SOURCE. */
import {parseSong,previewStream} from './catalog.js';

// DES permutations are the public FIPS 46-3 tables; this module only decodes
// the provider's catalog locator, never media content or DRM keys.
const IP = [58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6,64,56,48,40,32,24,16,8,57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7];
const FP = [40,8,48,16,56,24,64,32,39,7,47,15,55,23,63,31,38,6,46,14,54,22,62,30,37,5,45,13,53,21,61,29,36,4,44,12,52,20,60,28,35,3,43,11,51,19,59,27,34,2,42,10,50,18,58,26,33,1,41,9,49,17,57,25];
const PC1 = [57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4];
const PC2 = [14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32];
const E = [32,1,2,3,4,5,4,5,6,7,8,9,8,9,10,11,12,13,12,13,14,15,16,17,16,17,18,19,20,21,20,21,22,23,24,25,24,25,26,27,28,29,28,29,30,31,32,1];
const P = [16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25];
const S = [
  [14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13],
  [15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9],
  [10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12],
  [7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,1,13,8,9,4,5,11,12,7,2,14],
  [2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3],
  [12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13],
  [4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12],
  [13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11]
];
const permute = (bits, table) => table.map(position => bits[position - 1]);
const byteBits = bytes => bytes.flatMap(byte => Array.from({length:8},(_,index) => (byte >>> (7-index)) & 1));
const keyBits = permute(byteBits(Array.from('38346591',character => character.charCodeAt(0))),PC1);
const keys = [];
let leftKey = keyBits.slice(0,28), rightKey = keyBits.slice(28);
for (const shift of [1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1]) {
  leftKey = leftKey.slice(shift).concat(leftKey.slice(0,shift));
  rightKey = rightKey.slice(shift).concat(rightKey.slice(0,shift));
  keys.unshift(permute(leftKey.concat(rightKey),PC2));
}

function decodeBase64(value) {
  if (typeof value !== 'string') return null;
  const text = value.replace(/\s/g,'');
  if (!text.length || text.length > 4096 || text.length % 4 || !/^[A-Za-z0-9+/]+={0,2}$/.test(text)) return null;
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const bytes = [];
  for (let offset = 0; offset < text.length; offset += 4) {
    const digits = Array.from(text.slice(offset,offset+4),character => character === '=' ? 0 : alphabet.indexOf(character));
    const word = (digits[0]<<18) | (digits[1]<<12) | (digits[2]<<6) | digits[3];
    bytes.push((word>>>16)&255);
    if (text[offset+2] !== '=') bytes.push((word>>>8)&255);
    if (text[offset+3] !== '=') bytes.push(word&255);
  }
  return bytes.length % 8 === 0 ? bytes : null;
}

export function decryptMediaUrl(encrypted) {
  const input = decodeBase64(encrypted);
  if (!input) return null;
  const output = [];
  for (let offset = 0; offset < input.length; offset += 8) {
    const bits = permute(byteBits(input.slice(offset,offset+8)),IP);
    let left = bits.slice(0,32), right = bits.slice(32);
    for (const key of keys) {
      const expanded = permute(right,E).map((bit,index) => bit ^ key[index]);
      const substituted = [];
      for (let box = 0; box < 8; box++) {
        const six = expanded.slice(box*6,box*6+6);
        const row = six[0]*2 + six[5], column = six[1]*8 + six[2]*4 + six[3]*2 + six[4];
        const value = S[box][row*16+column];
        for (let bit = 3; bit >= 0; bit--) substituted.push((value>>>bit)&1);
      }
      const next = permute(substituted,P).map((bit,index) => bit ^ left[index]);
      left = right; right = next;
    }
    const plain = permute(right.concat(left),FP);
    for (let index = 0; index < plain.length; index += 8)
      output.push(plain.slice(index,index+8).reduce((value,bit) => value*2+bit,0));
  }
  const padding = output[output.length-1];
  if (padding < 1 || padding > 8 || !output.slice(-padding).every(byte => byte === padding)) return null;
  const bytes = output.slice(0,-padding);
  if (!bytes.every(byte => byte >= 32 && byte < 127)) return null;
  const url = String.fromCharCode(...bytes).trim().replace(/^http:\/\//i,'https://');
  return /^https:\/\/(?:[A-Za-z0-9-]+\.)*saavncdn\.com\/[A-Za-z0-9_./%?=&+~-]+$/i.test(url) ? url : null;
}

export function fullSongStream(song, quality = {}) {
  const track = parseSong(song);
  if (!track) return {error:{code:'TRACK_UNAVAILABLE'}};
  if (quality.lossless) return {error:{code:'LOSSLESS_UNAVAILABLE'}};
  const more = song.more_info ?? {};
  const encrypted = song.encrypted_media_url ?? more.encrypted_media_url;
  if (!encrypted) return {error:{code:'STREAM_UNAVAILABLE'}};
  const base = decryptMediaUrl(encrypted);
  if (!base) return {error:{code:'STREAM_DECODE_FAILED'}};
  const marker = /_(12|48|96|160|320)\.(mp3|mp4)(?=$|[?])/i;
  const match = base.match(marker);
  if (!match) return {error:{code:'STREAM_FORMAT_UNAVAILABLE'}};
  const has320 = String(song['320kbps'] ?? more['320kbps']).toLowerCase() === 'true';
  const preferred = quality.preset === 'LOW' ? 96000 : 320000;
  const ceiling = quality.maxBitrate == null ? preferred : Math.min(preferred,quality.maxBitrate);
  const bitrate = [320000,160000,96000,48000,12000].find(value => value <= ceiling && (value !== 320000 || has320));
  if (!bitrate) return {error:{code:'QUALITY_UNAVAILABLE'}};
  const locator = base.replace(marker,'_'+(bitrate/1000)+'.'+match[2].toLowerCase());
  const mp3 = match[2].toLowerCase() === 'mp3';
  return {stream:{transport:'DIRECT',locator,mimeType:mp3?'audio/mpeg':'audio/mp4',codec:mp3?'mp3':'mp4a.40.2',bitrate,
    ...(track.duration > 0 && {durationSeconds:Math.floor(track.duration/1000)}),
    attributes:{provider:'jiosaavn',content:'full song',trackId:track.id}}};
}

export function resolveSongStream(song, quality = {}, previews = true) {
  const full = fullSongStream(song,quality);
  if (full.stream || quality.lossless || full.error?.code === 'QUALITY_UNAVAILABLE') return full;
  const preview = previewStream(song,previews);
  return preview.stream ? preview : full;
}
