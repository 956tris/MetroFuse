/* Adapted from MetroList's YouTubeItemParser and YouTubeSearchOperations, GPL-3.0. */
const ITEMS = new Set(['musicResponsiveListItemRenderer','musicTwoRowItemRenderer','musicMultiRowListItemRenderer','musicCardShelfRenderer']);
const HEADERS = new Set(['musicDetailHeaderRenderer','musicResponsiveHeaderRenderer','musicImmersiveHeaderRenderer','musicVisualHeaderRenderer']);

export function videoId(identity) {
  const identifiers = identity?.identifiers ?? {};
  const owner = identifiers.pluginOwner ?? identifiers.plugin;
  const id = identifiers.youtube ?? (!owner || owner === 'metrofuse.youtube-music' ? identity?.canonicalId : null);
  return typeof id === 'string' && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
}

export function renderers(root, names) {
  const found = [], pending = root == null ? [] : [root];
  while (pending.length) {
    const item = pending.pop();
    if (Array.isArray(item)) pending.push(...item.slice().reverse());
    else if (item && typeof item === 'object') {
      for (const key of Object.keys(item)) if (names.has(key) && item[key] && typeof item[key] === 'object') found.push(item[key]);
      pending.push(...Object.values(item).reverse());
    }
  }
  return found;
}

const runs = value => Array.isArray(value?.runs) ? value.runs : [];
const text = value => typeof value === 'string' ? value.trim() : (value?.simpleText ?? runs(value).map(run => run.text ?? '').join('')).trim();
const endpoint = row => row?.navigationEndpoint ?? row?.onTap ?? row?.playNavigationEndpoint ?? {};
const column = (row, field, index) => row?.[field]?.[index]?.[field === 'flexColumns' ? 'musicResponsiveListItemFlexColumnRenderer' : 'musicResponsiveListItemFixedColumnRenderer']?.text;
const title = row => text(row.title) || text(column(row,'flexColumns',0)) || text(row.secondTitle);
const metadata = row => [row.subtitle,row.secondSubtitle,column(row,'flexColumns',1)].map(runs).find(value => value.length) ?? [];

function kind(browse) {
  const id = browse?.browseId ?? '';
  const page = browse?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
  if (page === 'MUSIC_PAGE_TYPE_ALBUM' || id.startsWith('MPRE')) return 'album';
  if (page === 'MUSIC_PAGE_TYPE_ARTIST' || id.startsWith('UC')) return 'artist';
  if (page === 'MUSIC_PAGE_TYPE_PLAYLIST' || /^(VL|PL|OLAK)/.test(id)) return 'playlist';
  return null;
}

function artwork(row) {
  return [row.thumbnail,row.thumbnailRenderer,row.foregroundThumbnail]
    .flatMap(value => renderers(value,new Set(['musicThumbnailRenderer','croppedSquareThumbnailRenderer','thumbnail'])))
    .flatMap(value => value.thumbnails ?? []).map(value => value.url)
    .filter(value => typeof value === 'string' && value.startsWith('https://')).pop() ?? '';
}

function credit(values) {
  return [...new Set(values.filter(value => kind(endpoint(value).browseEndpoint) === 'artist').map(value => text(value.text)).filter(Boolean))].join(', ');
}

function duration(value) {
  if (typeof value !== 'string' || !/^\d{1,3}:\d{1,2}(?::\d{1,2})?$/.test(value.trim())) return null;
  const parts = value.trim().split(':').map(Number);
  if (parts.slice(1).some(part => part >= 60)) return null;
  return parts.reduce((total,part) => total*60+part,0)*1000;
}

function track(row) {
  const watch = endpoint(row).watchEndpoint ?? endpoint(row).watchPlaylistEndpoint;
  const id = row.playlistItemData?.videoId ?? watch?.videoId ?? renderers(row.overlay,new Set(['watchEndpoint']))[0]?.videoId
    ?? runs(column(row,'flexColumns',0)).map(run => endpoint(run).watchEndpoint?.videoId).find(Boolean);
  if (!videoId({canonicalId:id}) || kind(endpoint(row).browseEndpoint) === 'album' || !title(row)) return null;
  const values = metadata(row), album = values.find(value => kind(endpoint(value).browseEndpoint) === 'album');
  return {id,title:title(row),artist:credit(values),album:text(album?.text),
    duration:[text(column(row,'fixedColumns',0)),...values.map(value => value.text)].map(duration).find(value => value != null) ?? 0,
    artwork:artwork(row),extras:{youtube:id,...(album && {albumId:endpoint(album).browseEndpoint.browseId})}};
}

const unique = values => [...values.reduce((found,value) => { if (!found.has(value.id)) found.set(value.id,value); return found; },new Map()).values()];

function collection(row) {
  const browse = endpoint(row).browseEndpoint, type = kind(browse);
  if (!browse?.browseId || !type || !title(row)) return null;
  return {id:browse.browseId,title:title(row),type,credit:credit(metadata(row)),artwork:artwork(row)};
}

function catalog(value, type, tracks = [], albums = []) {
  if (type === 'artist') return {id:value.id,name:value.title,artwork:value.artwork,tracks,albums};
  if (type === 'album') return {id:value.id,title:value.title,artist:value.credit,artwork:value.artwork,tracks};
  return {id:value.id,title:value.title,author:value.credit,artwork:value.artwork,tracks};
}

export function search(root, limit) {
  limit = Math.max(1,Math.min(Number(limit) || 30,100));
  const rows = renderers(root.contents,ITEMS), collections = unique(rows.map(collection).filter(Boolean));
  return {tracks:unique(rows.map(track).filter(Boolean)).slice(0,limit),
    albums:collections.filter(value => value.type === 'album').slice(0,limit).map(value => catalog(value,'album')),
    artists:collections.filter(value => value.type === 'artist').slice(0,limit).map(value => catalog(value,'artist')),
    playlists:collections.filter(value => value.type === 'playlist').slice(0,limit).map(value => catalog(value,'playlist'))};
}

export function page(root, id, type) {
  const header = [...renderers(root.header,HEADERS),...renderers(root.contents,HEADERS)].find(value => text(value.title));
  if (!header) return null;
  const rows = renderers(root.contents,ITEMS), tracks = rows.map(track).filter(Boolean);
  const value = {id,title:text(header.title),credit:credit([...runs(header.straplineTextOne),...runs(header.subtitle)]),artwork:artwork(header)};
  const albums = type === 'artist' ? unique(rows.map(collection).filter(value => value?.type === 'album')).map(value => catalog(value,'album')) : [];
  return catalog(value,type,type === 'playlist' ? tracks : unique(tracks),albums);
}
