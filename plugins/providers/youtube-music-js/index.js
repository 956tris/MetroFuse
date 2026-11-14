/* MetroFuse contributors, GPL-3.0. See SOURCE.md for provenance and limits. */
import {videoId,search,page} from './modules/catalog.js';
import {jsonValue,guestMusicContext,selectAudio,signaturePlan} from './modules/media.js';

let host, bootstrapPromise, playerScriptPromise, playerScriptUrl;
const players = new Map();
// Guest player profile from InnerTubeX v0.11.7, without account credentials.
const GUEST_PLAYER = {clientName:'ANDROID_VR',clientVersion:'1.65.10',clientId:'28',osName:'Android',osVersion:'12L',
  deviceMake:'Oculus',deviceModel:'Quest 3',androidSdkVersion:'32',
  userAgent:'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'};

async function bootstrap() {
  if (!bootstrapPromise) bootstrapPromise = (async () => {
    const response = await host.http({url:'https://music.youtube.com/sw.js_data',maxBytes:2097152,timeoutMs:10000});
    if (response.status !== 200) throw Error('YouTube Music bootstrap HTTP '+response.status);
    const context = guestMusicContext(response.body);
    if (!context?.client?.clientName || !context.client.clientVersion) throw Error('BOOTSTRAP_UNAVAILABLE');
    return {context};
  })().catch(error => { bootstrapPromise = null; throw error; });
  return bootstrapPromise;
}

async function api(method, fields, profile = null) {
  const config = await bootstrap(), context = JSON.parse(JSON.stringify(config.context));
  if (profile) {
    const {clientId,...client} = profile;
    context.client = {...context.client,...client};
  }
  const language = await host.settings.get('contentLanguage'), country = await host.settings.get('contentCountry');
  if (language) context.client.hl = language;
  if (country) context.client.gl = country;
  const response = await host.http({url:'https://music.youtube.com/youtubei/v1/'+method+'?prettyPrint=false',
    method:'POST',headers:{'Content-Type':'application/json','X-Goog-Api-Format-Version':'1','X-YouTube-Client-Name':profile?.clientId ?? '67',
      'X-YouTube-Client-Version':context.client.clientVersion,Origin:'https://music.youtube.com','X-Origin':'https://music.youtube.com',Referer:'https://music.youtube.com/',
      ...(profile?.userAgent && {'User-Agent':profile.userAgent})},
    body:JSON.stringify({context,...fields}),maxBytes:2097152,timeoutMs:10000});
  if (response.status !== 200) throw Error('YouTube Music '+method+' HTTP '+response.status);
  return JSON.parse(response.body);
}

async function player(id) {
  const existing = players.get(id);
  if (existing && existing.expires > Date.now()) return existing.value;
  const fields = {videoId:id,contentCheckOk:true,racyCheckOk:true};
  let value = await api('player',fields);
  if (value.playabilityStatus?.status !== 'OK' || !value.streamingData?.adaptiveFormats?.some(format => /^audio\//.test(format.mimeType ?? ''))) {
    const guest = await api('player',fields,GUEST_PLAYER);
    value = guest;
  }
  if (value.videoDetails?.videoId && value.videoDetails.videoId !== id) throw Error('PLAYER_ID_MISMATCH');
  players.set(id,{value,expires:Date.now()+30000});
  while (players.size > 40) players.delete(players.keys().next().value);
  return value;
}

async function decipherPlan(value) {
  let path = value.assets?.js;
  if (!path && videoId({canonicalId:value.videoDetails?.videoId})) {
    const response = await host.http({url:'https://www.youtube-nocookie.com/embed/'+value.videoDetails.videoId,maxBytes:2097152,timeoutMs:10000});
    if (response.status === 200) path = jsonValue(response.body,'jsUrl');
  }
  if (typeof path !== 'string') return null;
  const url = path.startsWith('/') ? 'https://www.youtube.com'+path : path;
  if (!/^https:\/\/(?:music\.|www\.)?youtube\.com\/s\/player\//.test(url)) return null;
  if (!playerScriptPromise || playerScriptUrl !== url) {
    playerScriptUrl = url;
    playerScriptPromise = (async () => {
      const response = await host.http({url,maxBytes:2097152,timeoutMs:10000});
      return response.status === 200 ? signaturePlan(response.body) : null;
    })().catch(() => { playerScriptPromise = null; playerScriptUrl = null; return null; });
  }
  return playerScriptPromise;
}

async function browse(id, type) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,240}$/.test(id)) return null;
  const browseId = type === 'playlist' && !id.startsWith('VL') ? 'VL'+id : id;
  return page(await api('browse',{browseId}),id,type);
}

globalThis.plugin = {
  async init(context) {
    host = context;
    host.search('youtube-music.search',async request => String(request.query ?? '').trim()
      ? search(await api('search',{query:request.query}),request.limit) : {tracks:[]});
    host.catalog('youtube-music.catalog',{album:id => browse(id,'album'),artist:id => browse(id,'artist'),playlist:id => browse(id,'playlist')});
    host.metadataSource('youtube-music.metadata',async request => {
      const id = videoId(request.identity);
      if (!id) return null;
      const details = (await player(id)).videoDetails;
      if (!details || details.videoId !== id) return null;
      const fields = {};
      if (typeof details.title === 'string' && details.title.trim()) fields.title = details.title;
      if (typeof details.author === 'string' && details.author.trim()) fields.artist = details.author;
      const duration = Number(details.lengthSeconds)*1000;
      if (Number.isSafeInteger(duration) && duration > 0) fields.duration = duration;
      if (!Object.keys(fields).length) return null;
      return {identity:{...request.identity,identifiers:{...request.identity.identifiers,youtube:id}},
        fields,confidence:1};
    });
    host.audioSource('youtube-music.audio',{transports:['RANGE_HTTP']},async request => {
      const id = videoId(request.identity);
      if (!id) return {error:{code:'IDENTITY_REQUIRED'}};
      if (!await host.settings.get('audioEnabled')) return {error:{code:'AUDIO_DISABLED'}};
      if (request.quality?.lossless) return {error:{code:'LOSSLESS_UNAVAILABLE'}};
      const value = await player(id), result = selectAudio(value,request.quality);
      return result.error?.code === 'SIGNATURE_TRANSFORM_UNAVAILABLE'
        ? selectAudio(value,request.quality,await decipherPlan(value)) : result;
    });
  },
  dispose() { players.clear(); bootstrapPromise = null; playerScriptPromise = null; playerScriptUrl = null; host = null; }
};
