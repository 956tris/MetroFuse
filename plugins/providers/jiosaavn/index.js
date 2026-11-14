/* Metrolist Project (C) 2026, GPL-3.0. See SOURCE for source attribution. */
import {catalogId,parseSong,parseSongs,parseAlbum,parseArtist,parsePlaylist,previewStream} from './modules/catalog.js';
import {decryptMediaUrl,fullSongStream,resolveSongStream} from './modules/media.js';
import {catalogUrl,catalogHeaders,catalogRoutingIp} from './modules/request.js';
import {homeShelves} from './modules/home.js';

const cache = new Map();
let host;
let routingIp;

async function api(call, parameters, regionalCatalog) {
  if (regionalCatalog == null) regionalCatalog = await host.settings.get('regionalCatalog');
  const response = await host.http({url:catalogUrl(call,parameters),headers:catalogHeaders(regionalCatalog,routingIp),maxBytes:1048576,timeoutMs:10000});
  if (response.status !== 200) throw Error('JioSaavn HTTP ' + response.status);
  return JSON.parse(response.body);
}

async function song(identity) {
  const id = catalogId(identity);
  if (!id) return null;
  const regionalCatalog = await host.settings.get('regionalCatalog');
  const key = String(regionalCatalog) + ':' + id;
  const existing = cache.get(key);
  if (existing && existing.expires > Date.now()) return existing.value;
  const data = await api('song.getDetails',{pids:id},regionalCatalog);
  const value = (data.songs ?? []).find(candidate => String(candidate.id) === id && parseSong(candidate));
  if (value) {
    cache.set(key,{value,expires:Date.now()+60000});
    while (cache.size > 80) cache.delete(cache.keys().next().value);
  }
  return value ?? null;
}

globalThis.plugin = {
  async init(context) {
    host = context;
    routingIp = catalogRoutingIp();
    host.home(async request => homeShelves(await api('webapi.getLaunchData',{hl:request.language,gl:request.country})));
    host.search('jiosaavn.search', async request => {
      if (!String(request.query ?? '').trim()) return {tracks:[]};
      const params = {q:request.query,n:String(Math.max(1,Math.min(request.limit ?? 30,30))),p:'1'};
      const responses = await Promise.allSettled([
        api('search.getResults', params),
        api('search.getAlbumResults', params),
        api('search.getArtistResults', params),
        api('search.getPlaylistResults', params)
      ]);
      if (responses.every(result => result.status === 'rejected')) throw responses[0].reason;
      const [tracks, albums, artists, playlists] = responses.map(result =>
        Array.isArray(result.value?.results) ? result.value.results : []
      );
      return {
        tracks:parseSongs(tracks),
        albums:albums.map(item => parseAlbum(item, '')).filter(item => item?.id),
        artists:artists.map(item => parseArtist(item, '')).filter(item => item?.id),
        playlists:playlists.map(item => parsePlaylist(item, '')).filter(item => item?.id)
      };
    });
    host.metadataSource('jiosaavn.metadata', async request => {
      const value = parseSong(await song(request.identity));
      if (!value) return null;
      const {id,extras,...fields} = value;
      return {identity:{...request.identity,identifiers:{...request.identity.identifiers,jiosaavn:extras.jiosaavn}},fields,confidence:1};
    });
    host.catalog('jiosaavn.catalog', {
      album: async id => parseAlbum(await api('content.getAlbumDetails',{albumid:id}),id),
      artist: async id => parseArtist(await api('artist.getArtistPageDetails',{artistId:id,n_song:'25',n_album:'0'}),id),
      playlist: async id => parsePlaylist(await api('playlist.getDetails',{listid:id}),id)
    });
    host.audioSource('jiosaavn.audio',{transports:['DIRECT']},async request => {
      if (!catalogId(request.identity)) return {error:{code:'IDENTITY_REQUIRED'}};
      return resolveSongStream(await song(request.identity),request.quality,await host.settings.get('previewEnabled'));
    });
  },
  dispose() { cache.clear(); host = null; routingIp = null; }
};
