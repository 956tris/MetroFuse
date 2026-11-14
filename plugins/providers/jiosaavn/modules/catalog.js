/*
 * Metrolist Project (C) 2026. Licensed under GPL-3.0.
 * Catalog field mapping adapted from MetroFuse JioSaavnAudioProvider.kt,
 * source archive MetroFuse-main (4).zip, commit e2cca5bf2278f0345568a48adbfbfbd0046680f6.
 * Public-preview identity/registration model reuses the MetroFuse SDK prototype.
 */

export const TRACK_PREFIX = 'jiosaavn:track:';
export function decodeHtml(value) {
  return String(value ?? '').replace(/&(amp|quot|lt|gt|#039|#39);/g,
    token => ({'&amp;':'&','&quot;':'"','&lt;':'<','&gt;':'>','&#039;':"'",'&#39;':"'"}[token]));
}

export function catalogId(identity) {
  const candidate = identity?.identifiers?.jiosaavn ??
    (String(identity?.canonicalId ?? '').startsWith(TRACK_PREFIX) ? identity.canonicalId.slice(TRACK_PREFIX.length) : null);
  return typeof candidate === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(candidate) ? candidate : null;
}

export function parseSong(song) {
  if (!song || (song.type != null && song.type !== 'song')) return null;
  const id = String(song.id ?? '');
  const title = decodeHtml(song.title ?? song.song);
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(id) || !title.trim()) return null;
  const more = song.more_info ?? {};
  const names = typeof song.primary_artists === 'string' ? song.primary_artists.split(',') :
    (more.artistMap?.primary_artists ?? []).map(artist => artist.name);
  const artist = names.length ? names.map(decodeHtml).map(name => name.trim()).filter(Boolean).join(', ') : decodeHtml(more.music);
  const duration = Number(song.duration ?? more.duration);
  const albumId = String(song.albumid ?? more.album_id ?? '');
  return {
    id: TRACK_PREFIX + id,
    title,
    artist,
    album: decodeHtml(song.album ?? more.album),
    duration: Number.isFinite(duration) && duration > 0 ? Math.round(duration * 1000) : 0,
    artwork: String(song.image ?? '').replace('150x150', '500x500'),
    extras: {jiosaavn:id, jiosaavnAlbumId:albumId, ...(albumId && {albumId})}
  };
}

export function parseSongs(songs) { return (Array.isArray(songs) ? songs : []).map(parseSong).filter(Boolean); }

export function parseAlbum(data, id) {
  if (!data || !(data.title || data.name)) return null;
  const tracks = parseSongs(Array.isArray(data.songs) ? data.songs : data.list);
  return {id:String(data.albumid ?? data.id ?? id),title:decodeHtml(data.title ?? data.name),artist:decodeHtml(data.subtitle ?? data.more_info?.music ?? tracks[0]?.artist),artwork:String(data.image ?? ''),tracks};
}

export function parseArtist(data, id) {
  if (!data || !(data.name || data.title)) return null;
  const songs = Array.isArray(data.topSongs) ? data.topSongs : data.topSongs?.songs;
  return {id:String(data.artistId ?? data.id ?? id),name:decodeHtml(data.name ?? data.title),artwork:String(data.image ?? ''),tracks:parseSongs(songs),albums:[]};
}

export function parsePlaylist(data, id) {
  if (!data || !(data.listname || data.title)) return null;
  return {id:String(data.listid ?? data.id ?? id),title:decodeHtml(data.listname ?? data.title),author:decodeHtml(data.firstname ?? data.more_info?.firstname ?? data.subtitle),artwork:String(data.image ?? ''),tracks:parseSongs(data.list)};
}

export function previewStream(song, enabled = true) {
  if (!enabled) return {error:{code:'PREVIEW_DISABLED'}};
  const track = parseSong(song);
  const url = song?.more_info?.vlink;
  if (!track || typeof url !== 'string' || !/^https:\/\/jiotunepreview\.jio\.com\/[A-Za-z0-9_./%?=&+-]+$/.test(url))
    return {error:{code:'PREVIEW_UNAVAILABLE'}};
  // No bitrate, codec or expiry is guessed from a catalog flag or file name.
  return {stream:{transport:'DIRECT',locator:url,attributes:{provider:'jiosaavn',content:'public JioTune preview excerpt',trackId:track.id}}};
}
