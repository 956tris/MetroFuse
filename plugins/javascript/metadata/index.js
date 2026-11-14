globalThis.plugin = {
  async init(host) {
    host.metadataSource('example.fixture.metadata', async request => ({
      identity: request.identity,
      fields: { genre: 'Ambient', title: 'Local fixture' },
      confidence: 1
    }));
    host.search('example.fixture.search', async request => ({
      tracks: request.query.toLowerCase().includes('fixture')
        ? [{ id:'fixture:one', title:'Local fixture', artist:'MetroFuse fixtures', album:'Test media', duration:1000 }]
        : []
    }));
  }
};
