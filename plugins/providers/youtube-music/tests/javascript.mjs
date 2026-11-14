import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const source = new URL('../../youtube-music-js/',import.meta.url);
const catalogUrl = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/catalog.js',source))).toString('base64');
const mediaUrl = 'data:text/javascript;base64,'+Buffer.from(await fs.readFile(new URL('modules/media.js',source))).toString('base64');
const catalog = await import(catalogUrl);
const media = await import(mediaUrl);
const fixture = JSON.parse(await fs.readFile(new URL('../src/desktopTest/resources/youtube-search.json',import.meta.url)));
const results = catalog.search(fixture,10);
assert.equal(results.tracks.length,1);
assert.equal(results.tracks[0].extras.albumId,'MPREfixture');
assert.equal(results.tracks[0].duration,241000);
assert.equal(results.albums[0].id,'MPREfixture');
assert.equal(results.artists[0].id,'UCfixture');
assert.equal(results.playlists[0].id,'VLPLfixture');
assert.equal(catalog.page(fixture,'PLfixture','playlist').tracks.length,2);
assert.equal(catalog.page(fixture,'MPREfixture','album').tracks.length,1);
assert.equal(catalog.videoId({canonicalId:'abcdefghijk',identifiers:{pluginOwner:'metrofuse.jiosaavn'}}),null);
assert.equal(media.jsonValue('ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB_REMIX","clientVersion":"fixture"}}});','INNERTUBE_CONTEXT').client.clientName,'WEB_REMIX');
const tuple = Array(17).fill(null);
tuple[0] = 'pt-BR'; tuple[1] = 'BR'; tuple[15] = 67; tuple[16] = '1.20261114.00.00';
assert.deepEqual(media.guestMusicContext(")]}'\n"+JSON.stringify([['yt.sw.adr',null,[[tuple]]]])),{client:{clientName:'WEB_REMIX',clientVersion:'1.20261114.00.00',hl:'pt-BR',gl:'BR'},user:{}});
assert.equal(media.guestMusicContext('invalid bootstrap'),null);
tuple[15] = 28;
assert.equal(media.guestMusicContext(JSON.stringify(tuple)),null);
const player = {playabilityStatus:{status:'OK'},videoDetails:{videoId:'abcdefghijk',lengthSeconds:'241'},streamingData:{adaptiveFormats:[
  {itag:140,mimeType:'audio/mp4; codecs="mp4a.40.2"',bitrate:128000,audioSampleRate:'44100',url:'https://fixture.googlevideo.com/videoplayback?expire=4102444800'},
  {itag:251,mimeType:'audio/webm; codecs="opus"',bitrate:160000,audioSampleRate:'48000',url:'https://fixture.googlevideo.com/videoplayback?expire=4102444800'}
]}};
assert.equal(media.selectAudio(player,{preset:'HIGH'}).stream.codec,'opus');
assert.equal(media.selectAudio(player,{preset:'LOW'}).stream.codec,'mp4a.40.2');
assert.equal(media.selectAudio(player,{preset:'MP4'}).stream.codec,'mp4a.40.2');
assert.equal(media.selectAudio(player,{maxBitrate:130000}).stream.bitrate,128000);
assert.equal(media.selectAudio(player,{maxBitrate:100000}).error.code,'FORMAT_UNAVAILABLE');
assert.equal(media.selectAudio(player,{lossless:true}).error.code,'LOSSLESS_UNAVAILABLE');
const ciphered = structuredClone(player);
ciphered.streamingData.adaptiveFormats = [{itag:140,mimeType:'audio/mp4',signatureCipher:'url='+encodeURIComponent('https://fixture.googlevideo.com/videoplayback?expire=4102444800')+'&sp=sig&s=abcdef'}];
assert.equal(media.selectAudio(ciphered).error.code,'SIGNATURE_TRANSFORM_UNAVAILABLE');
const script = 'var XY={rv:function(a){a.reverse()},sp:function(a,b){a.splice(0,b)},sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};function(a){a=a.split("");XY.sw(a,2);XY.rv(a);XY.sp(a,1);return a.join("")}';
const plan = media.signaturePlan(script);
assert.deepEqual(plan,[['swap',2],['reverse',0],['splice',1]]);
assert.equal(media.decipherSignature('abcdef',plan),'edabc');
assert.match(media.selectAudio(ciphered,{},plan).stream.locator,/sig=edabc$/);
const blocked = structuredClone(player);
blocked.streamingData.adaptiveFormats.forEach(format => format.url += '&n=throttled');
assert.equal(media.selectAudio(blocked).error.code,'N_TRANSFORM_UNAVAILABLE');
assert.equal(media.signaturePlan('unsupported-player-code'),null);
const entry = (await fs.readFile(new URL('index.js',source),'utf8')).replace("'./modules/catalog.js'",JSON.stringify(catalogUrl)).replace("'./modules/media.js'",JSON.stringify(mediaUrl));
await import('data:text/javascript;base64,'+Buffer.from(entry).toString('base64'));
tuple[15] = 67;
const settings = {audioEnabled:true,contentLanguage:'es-ES',contentCountry:'ES'};
const providers = {}, requests = [];
const host = {
  search(id,callback) { providers.search = callback; },
  catalog(id,callback) { providers.catalog = callback; },
  audioSource(id,capabilities,callback) { providers.audio = callback; },
  metadataSource(id,callback) { providers.metadata = callback; },
  settings:{async get(key) { return settings[key]; }},
  async http(request) {
    requests.push(request);
    if (request.url.endsWith('/sw.js_data')) return {status:200,headers:{},body:")]}'\n"+JSON.stringify([['yt.sw.adr',null,[[tuple]]]])};
    const body = JSON.parse(request.body);
    assert.equal(body.context.client.hl,'es-ES');
    assert.equal(body.context.client.gl,'ES');
    assert.equal(request.headers.Cookie,undefined);
    return {status:200,headers:{},body:JSON.stringify(request.url.includes('/player') ? player : fixture)};
  }
};
await globalThis.plugin.init(host);
assert.equal((await providers.search({query:'Fixture',limit:10})).tracks[0].title,'Fixture track');
assert.equal((await providers.catalog.playlist('PLfixture')).tracks.length,2);
assert.equal((await providers.audio({identity:{canonicalId:'abcdefghijk'},quality:{preset:'HIGH'}})).stream.codec,'opus');
assert.equal(requests.filter(request => request.url.endsWith('/sw.js_data')).length,1);
settings.audioEnabled = false;
const requestCount = requests.length;
assert.equal((await providers.audio({identity:{canonicalId:'abcdefghijk'}})).error.code,'AUDIO_DISABLED');
assert.equal(requests.length,requestCount);
globalThis.plugin.dispose();
console.log('YouTube Music JS production callbacks, locale, catalog, identity, direct audio, quality and supported/unsupported signature fixtures passed.');
