globalThis.plugin = {
  async init(host) {
    host.lyrics('example.fixture.lyrics', async request => request.identity.canonicalId.startsWith('fixture:') ? {
      source:'MetroFuse fixture',
      lines:[
        { text:'A local test', timeMs:0, words:[{text:'A',startMs:0,endMs:100},{text:'local',startMs:100,endMs:500},{text:'test',startMs:500,endMs:1000}] }
      ]
    } : null);
  }
};
