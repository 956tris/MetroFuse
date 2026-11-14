globalThis.plugin = {
  async init(host) {
    host.log('Minimal plugin activated');
    this.unsubscribe = host.on('track_changed', track => host.log('Track changed: ' + track.id));
  },
  dispose() { this.unsubscribe?.(); }
};
