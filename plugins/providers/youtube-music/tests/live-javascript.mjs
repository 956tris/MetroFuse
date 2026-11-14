import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
if (process.env.METROFUSE_YOUTUBE_MUSIC_LIVE !== '1') throw Error('Set METROFUSE_YOUTUBE_MUSIC_LIVE=1 for the opt-in public guest service test');
const source = new URL('../../youtube-music-js/',import.meta.url);
const manifest = JSON.parse(await fs.readFile(new URL('manifest.json',source)));
const modules = {};
for (const name of ['catalog','media']) modules[name] = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/'+name+'.js',source))).toString('base64');
let entry = await fs.readFile(new URL('index.js',source),'utf8');
for (const [name,url] of Object.entries(modules)) entry = entry.replace("'./modules/"+name+".js'",JSON.stringify(url));
await import('data:text/javascript;base64,'+Buffer.from(entry).toString('base64'));
const defaults = Object.fromEntries(manifest.settings.map(setting => [setting.key,setting.default]));
const providers = {};
const host = {
  search(id,callback) { providers.search = callback; },
  catalog(id,callback) { providers.catalog = callback; },
  metadataSource(id,callback) { providers.metadata = callback; },
  audioSource(id,capabilities,callback) { providers.audio = callback; },
  settings:{async get(key) { return defaults[key]; }},
  async http(request) {
    const response = await fetch(request.url,{method:request.method ?? 'GET',headers:request.headers,body:request.body,redirect:'manual',signal:AbortSignal.timeout(request.timeoutMs)});
    const chunks = [];
    let total = 0;
    for await (const chunk of response.body) {
      total += chunk.byteLength;
      if (total > request.maxBytes) throw Error('HTTP_RESPONSE_LIMIT');
      chunks.push(chunk);
    }
    const body = Buffer.concat(chunks).toString('utf8');
    if (request.url.includes('/youtubei/v1/player') && response.status === 200) {
      const value = JSON.parse(body), client = JSON.parse(request.body).context.client;
      console.log(JSON.stringify({clientName:client.clientName,playability:value.playabilityStatus?.status,reason:value.playabilityStatus?.reason}));
    }
    if (response.status >= 400) {
      const client = request.body ? JSON.parse(request.body).context?.client : null;
      if (client) console.log(JSON.stringify({clientName:client.clientName,clientVersion:client.clientVersion}));
      try { const error = JSON.parse(body).error; console.log(JSON.stringify({stage:new URL(request.url).pathname,status:response.status,message:error?.message})); }
      catch (_) { console.log(JSON.stringify({stage:new URL(request.url).pathname,status:response.status,json:false})); }
    }
    return {status:response.status,headers:Object.fromEntries(response.headers),body};
  }
};
let summary;
try {
  await globalThis.plugin.init(host);
  const result = await providers.search({query:'Adele Hello',limit:10});
  const track = result.tracks.find(value => value.title.toLowerCase() === 'hello' && value.artist.toLowerCase().includes('adele'));
  assert.ok(track,'Guest catalog did not return the original recording');
  const audio = await providers.audio({identity:{canonicalId:track.id,identifiers:{youtube:track.id,pluginOwner:manifest.id}},platform:'desktop',quality:{preset:'AUTO'}});
  if (!audio.stream) {
    summary = {catalogVerified:true,title:track.title,artist:track.artist,audioVerified:false,error:audio.error?.code ?? 'STREAM_UNAVAILABLE',decoderVerified:false};
  } else {
    const response = await fetch(audio.stream.locator,{headers:{...audio.stream.headers,Range:'bytes=0-65535'},redirect:'manual',signal:AbortSignal.timeout(10000)});
    assert.ok([200,206].includes(response.status),'Audio CDN HTTP '+response.status);
    const reader = response.body.getReader(), chunks = [];
    let total = 0;
    while (total < 65536) {
      const {done,value} = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
    }
    await reader.cancel();
    const prefix = Buffer.concat(chunks).subarray(0,65536);
    assert.ok(prefix.length > 2048,'Audio CDN supplied no usable media prefix');
    await fs.writeFile(new URL('../../../../build/youtube-music-js-live-media-prefix.bin',import.meta.url),prefix);
    summary = {catalogVerified:true,title:track.title,artist:track.artist,audioVerified:true,mimeType:audio.stream.mimeType,codec:audio.stream.codec,cdnBytesRead:prefix.length,decoderVerified:false};
  }
} catch (error) {
  summary = {catalogVerified:false,audioVerified:false,error:error.message?.startsWith('YouTube Music') ? error.message : error.code ?? error.name,decoderVerified:false};
} finally {
  globalThis.plugin.dispose();
}
await fs.writeFile(new URL('../../../../build/youtube-music-js-live-verification.json',import.meta.url),JSON.stringify(summary,null,2));
console.log(JSON.stringify(summary));
if (!summary.catalogVerified || !summary.audioVerified) process.exitCode = 1;
