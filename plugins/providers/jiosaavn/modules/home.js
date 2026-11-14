/* Metrolist Project (C) 2026, GPL-3.0. See SOURCE. */
import {decodeHtml,parseSong,parseAlbum,parsePlaylist} from './catalog.js';

export function homeShelves(data) {
  return Object.values(data.modules ?? {}).filter(module => module && typeof module === 'object')
    .sort((a,b) => Number(a.position) - Number(b.position))
    .map(module => {
      const title = decodeHtml(module.title);
      const items = (Array.isArray(data[module.source]) ? data[module.source] : []).map(value => {
        if (value.type === 'song') { const track = parseSong(value); return track ? {track} : null; }
        if (value.type === 'album') { const album = parseAlbum(value,''); return album?.id ? {album} : null; }
        if (value.type === 'playlist') { const playlist = parsePlaylist(value,''); return playlist?.id ? {playlist} : null; }
        return null;
      }).filter(Boolean);
      return title && items.length ? {title,items,layout:items.every(item => item.track) ? 'ROWS_GRID' : 'CARDS'} : null;
    }).filter(Boolean);
}
