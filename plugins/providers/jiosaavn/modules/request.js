/* Metrolist Project (C) 2026, GPL-3.0. See SOURCE. */
const API = 'https://www.jiosaavn.com/api.php';

export function catalogUrl(call, parameters) {
  const query = {__call:call,_format:'json',_marker:'0',api_version:'4',ctx:'web6dot0',...parameters};
  return API + '?' + Object.keys(query).map(key => encodeURIComponent(key) + '=' + encodeURIComponent(query[key])).join('&');
}

// Keep the upstream provider's catalog routing stable for one activation.
// These headers belong only to api.php requests, never CDN audio requests.
export function catalogRoutingIp() {
  return '103.57.226.' + (1 + Math.floor(Math.random() * 254));
}

export function catalogHeaders(regionalCatalog, routingIp) {
  return {Accept:'application/json',Referer:'https://www.jiosaavn.com/',
    ...(regionalCatalog && {'X-Forwarded-For':routingIp,'X-Real-IP':routingIp})};
}
