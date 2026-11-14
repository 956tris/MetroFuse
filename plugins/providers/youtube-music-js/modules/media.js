/* MetroFuse contributors, GPL-3.0. See SOURCE. */
const failure = code => ({error:{code}});

export function guestMusicContext(source) {
  let response;
  try { response = JSON.parse(source.slice(source.indexOf('['))); } catch (_) { return null; }
  const pending = [response];
  while (pending.length) {
    const value = pending.pop();
    if (!Array.isArray(value)) continue;
    // Public sw.js_data client tuples carry locale, numeric client profile and version.
    if (value[15] === 67 && typeof value[16] === 'string' && /^1\.\d{8}\.\d{2}\.\d{2}$/.test(value[16])
      && typeof value[0] === 'string' && typeof value[1] === 'string') {
      return {client:{clientName:'WEB_REMIX',clientVersion:value[16],hl:value[0],gl:value[1]},user:{}};
    }
    pending.push(...value);
  }
  return null;
}

export function queryParameters(value) {
  const result = {};
  for (const part of String(value ?? '').split('&')) {
    const split = part.indexOf('='), key = split < 0 ? part : part.slice(0,split), text = split < 0 ? '' : part.slice(split+1);
    try { result[decodeURIComponent(key.replace(/\+/g,' '))] = decodeURIComponent(text.replace(/\+/g,' ')); }
    catch (_) { return null; }
  }
  return result;
}

export function jsonValue(source, key) {
  const match = new RegExp('"'+key+'"\\s*:\\s*').exec(source);
  if (!match) return null;
  const start = match.index+match[0].length;
  let end = start, depth = 0, quoted = false, escaped = false;
  for (; end < source.length; end++) {
    const character = source[end];
    if (quoted) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') { quoted = false; if (depth === 0) { end++; break; } }
    } else if (character === '"') quoted = true;
    else if (character === '{' || character === '[') depth++;
    else if (character === '}' || character === ']') { if (--depth <= 0) { end++; break; } }
    else if (depth === 0 && (character === ',' || character === ')')) break;
  }
  try { return JSON.parse(source.slice(start,end)); } catch (_) { return null; }
}

// Interpret only the well-known array signature operations. Unknown player code
// is rejected rather than evaluated as arbitrary downloaded JavaScript.
export function signaturePlan(source) {
  const candidates = [...source.matchAll(/function\(([A-Za-z_$][\w$]*)\)\{\1=\1\.split\(""\);([^{}]+?)return \1\.join\(""\)\}/g)];
  const plans = [];
  for (const candidate of candidates) {
    const argument = candidate[1], body = candidate[2], plan = [];
    let valid = true;
    for (const statement of body.split(';').map(value => value.trim()).filter(Boolean)) {
      const call = /^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(([A-Za-z_$][\w$]*)(?:,(\d+))?\)$/.exec(statement);
      if (!call || call[3] !== argument) { valid = false; break; }
      const object = new RegExp('(?:var\\s+)?'+call[1].replace(/\$/g,'\\$')+'=\\{([\\s\\S]*?)\\};').exec(source);
      const helper = object && new RegExp('(?:^|,)'+call[2].replace(/\$/g,'\\$')+':function\\(([^)]*)\\)\\{([^}]*)\\}').exec(object[1]);
      if (!helper) { valid = false; break; }
      const parameters = helper[1].split(','), code = helper[2].replace(/\s+/g,'');
      const first = parameters[0], second = parameters[1];
      const escapedFirst = first.replace(/\$/g,'\\$'), escapedSecond = second?.replace(/\$/g,'\\$');
      if (code === first+'.reverse()' || code === first+'.reverse();') plan.push(['reverse',0]);
      else if (code === first+'.splice(0,'+second+')' || code === first+'.splice(0,'+second+');') plan.push(['splice',Number(call[4])]);
      else if (second && new RegExp('^var([\\w$]+)='+escapedFirst+'\\[0\\];'+escapedFirst+'\\[0\\]='+escapedFirst+'\\['+escapedSecond+'%'+escapedFirst+'\\.length\\];'+escapedFirst+'\\['+escapedSecond+'(?:%'+escapedFirst+'\\.length)?\\]=\\1;?$').test(code)) plan.push(['swap',Number(call[4])]);
      else { valid = false; break; }
      if (plan.some(value => !Number.isSafeInteger(value[1]))) { valid = false; break; }
    }
    if (valid && plan.length) plans.push(plan);
  }
  return plans.length === 1 ? plans[0] : null;
}

export function decipherSignature(value, plan) {
  if (typeof value !== 'string' || !value.length || value.length > 10000 || !plan) return null;
  const parts = value.split('');
  for (const [operation,count] of plan) {
    if (operation === 'reverse') parts.reverse();
    else if (operation === 'splice') parts.splice(0,count);
    else if (operation === 'swap' && parts.length) { const index = count%parts.length, first = parts[0]; parts[0] = parts[index]; parts[index] = first; }
    else return null;
  }
  return parts.join('') || null;
}

export function selectAudio(player, quality = {}, plan = null) {
  if (quality.lossless) return failure('LOSSLESS_UNAVAILABLE');
  if (player?.playabilityStatus?.status !== 'OK') return failure('YOUTUBE_'+String(player?.playabilityStatus?.status ?? 'UNAVAILABLE').replace(/[^A-Z_]/g,''));
  const formats = (player.streamingData?.adaptiveFormats ?? []).filter(format =>
    /^audio\//.test(format.mimeType ?? '') && !format.drmFamilies && !format.drmTrackType);
  const candidates = formats.filter(format => {
    const bitrate = Number(format.averageBitrate ?? format.bitrate);
    return (!quality.maxBitrate || (bitrate > 0 && bitrate <= quality.maxBitrate))
      && (quality.preset !== 'MP4' || /^audio\/mp4/.test(format.mimeType));
  }).sort((first,second) => {
    const preferences = quality.preferredCodecs ?? [];
    const rank = format => { const index = preferences.findIndex(codec => format.mimeType?.includes(codec)); return index < 0 ? preferences.length : index; };
    return rank(first)-rank(second) || (quality.preset === 'LOW' ? 1 : -1)*(Number(first.averageBitrate ?? first.bitrate ?? 0)-Number(second.averageBitrate ?? second.bitrate ?? 0));
  });
  let reason = candidates.length ? 'STREAM_UNAVAILABLE' : (formats.length ? 'FORMAT_UNAVAILABLE' : 'STREAM_UNAVAILABLE');
  for (const format of candidates) {
    let locator = format.url;
    if (!locator && (format.signatureCipher || format.cipher)) {
      const cipher = queryParameters(format.signatureCipher ?? format.cipher);
      if (!cipher?.url) { reason = 'INVALID_STREAM_CIPHER'; continue; }
      const signature = cipher.sig ?? cipher.signature ?? decipherSignature(cipher.s,plan);
      if (!signature) { reason = 'SIGNATURE_TRANSFORM_UNAVAILABLE'; continue; }
      locator = cipher.url+(cipher.url.includes('?') ? '&' : '?')+encodeURIComponent(cipher.sp || 'signature')+'='+encodeURIComponent(signature);
    }
    if (typeof locator !== 'string' || !/^https:\/\/[^/?#]+\.googlevideo\.com\//.test(locator)) { reason = 'INVALID_STREAM_URL'; continue; }
    const parameters = queryParameters(locator.split('?')[1] ?? '');
    if (parameters?.n) { reason = 'N_TRANSFORM_UNAVAILABLE'; continue; }
    const expiry = Number(parameters?.expire)*1000;
    if (expiry && expiry <= Date.now()+20000) { reason = 'STREAM_EXPIRED'; continue; }
    const bitrate = Number(format.averageBitrate ?? format.bitrate), sampleRate = Number(format.audioSampleRate), contentLength = Number(format.contentLength);
    return {stream:{transport:'RANGE_HTTP',locator,mimeType:format.mimeType.split(';')[0],codec:/codecs="([^"]+)"/.exec(format.mimeType)?.[1] ?? null,
      bitrate:Number.isSafeInteger(bitrate) && bitrate > 0 ? bitrate : null,
      sampleRate:Number.isSafeInteger(sampleRate) && sampleRate > 0 ? sampleRate : null,
      contentLength:Number.isSafeInteger(contentLength) && contentLength > 0 ? contentLength : null,
      durationSeconds:Number(player.videoDetails?.lengthSeconds) || null,
      expiresAtMillis:Number.isSafeInteger(expiry) && expiry > 0 ? expiry : null,
      attributes:{content:'guest audio',itag:String(format.itag)}}};
  }
  return failure(reason);
}
